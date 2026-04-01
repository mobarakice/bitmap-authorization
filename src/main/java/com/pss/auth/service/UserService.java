package com.pss.auth.service;

import com.pss.auth.domain.entity.User;
import com.pss.auth.domain.entity.UserAllowPermission;
import com.pss.auth.domain.entity.UserDenyPermission;
import com.pss.auth.dto.UserCreateRequest;
import com.pss.auth.dto.UserPermissionRequest;
import com.pss.auth.dto.UserResponse;
import com.pss.auth.dto.UserUpdateRequest;
import com.pss.auth.exception.BusinessRuleException;
import com.pss.auth.exception.ResourceNotFoundException;
import com.pss.auth.repository.PermissionRepository;
import com.pss.auth.repository.RoleRepository;
import com.pss.auth.repository.UserAllowPermissionRepository;
import com.pss.auth.repository.UserDenyPermissionRepository;
import com.pss.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;
    private final UserAllowPermissionRepository userAllowPermissionRepository;
    private final UserDenyPermissionRepository userDenyPermissionRepository;
    private final PasswordEncoder passwordEncoder;
    private final SessionPermissionService sessionPermissionService;
    private final CacheInvalidationService cacheInvalidationService;

    public List<UserResponse> listUsers() {
        return userRepository.findAll().stream()
                .map(UserResponse::from)
                .toList();
    }

    public UserResponse getUser(Long id) {
        var user = findUserOrThrow(id);
        return UserResponse.from(user);
    }

    @Transactional
    public UserResponse createUser(UserCreateRequest request) {
        if (userRepository.existsByUsername(request.username())) {
            throw new BusinessRuleException("Username '" + request.username() + "' already exists");
        }

        var user = new User();
        user.setUsername(request.username());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setFirstName(request.firstName());
        user.setLastName(request.lastName());
        user.setEmail(request.email());
        user.setMobileNo(request.mobileNo());
        user.setSigncode(generateSigncode());
        user.setRole(null); // no role until explicitly assigned
        user.setEnabled(true);
        user.setIsActive(true);
        user.setIsSystem(false);

        var saved = userRepository.save(user);
        log.info("User created: id={}, username={}", saved.getId(), saved.getUsername());

        return UserResponse.from(saved);
    }

    @Transactional
    public UserResponse updateUser(Long id, UserUpdateRequest request) {
        var user = findUserOrThrow(id);

        if (request.firstName() != null) user.setFirstName(request.firstName());
        if (request.lastName() != null) user.setLastName(request.lastName());
        if (request.email() != null) user.setEmail(request.email());
        if (request.mobileNo() != null) user.setMobileNo(request.mobileNo());
        if (request.isActive() != null) user.setIsActive(request.isActive());

        var saved = userRepository.save(user);
        return UserResponse.from(saved);
    }

    /**
     * Assign or change a user's role.
     * Two code paths per architecture docs:
     * 1. First-time (current role null): just set role_id. No Redis invalidation.
     * 2. Role change: atomic DB {update role, clear allow/deny} → invalidate → ensure new role bitmap.
     */
    @Transactional
    public UserResponse assignRole(Long userId, Integer roleId) {
        var user = findUserOrThrow(userId);
        var newRole = roleRepository.findById(roleId)
                .orElseThrow(() -> new ResourceNotFoundException("Role not found: " + roleId));

        var oldRole = user.getRole();

        // No-op if same role
        if (oldRole != null && oldRole.getId().equals(roleId)) {
            return UserResponse.from(user);
        }

        if (oldRole == null) {
            // First-time role assignment — no Redis invalidation needed
            user.setRole(newRole);
            var saved = userRepository.save(user);
            log.info("First-time role assignment: userId={}, roleId={}", userId, roleId);
            return UserResponse.from(saved);
        }

        // Role change — clear allow/deny overrides (they were relative to old role)
        user.setRole(newRole);
        userRepository.save(user);
        userAllowPermissionRepository.deleteByUserId(userId);
        userDenyPermissionRepository.deleteByUserId(userId);

        // Invalidate user bitmaps + sessions
        sessionPermissionService.invalidateUserBitmap(userId);

        // Ensure new role bitmap exists (lazy build if missing)
        sessionPermissionService.ensureRoleBitmap(roleId);

        // Pub/Sub for distributed coordination
        cacheInvalidationService.onUserPermissionUpdate(userId);
        cacheInvalidationService.forceReauth(userId);

        log.info("Role changed: userId={}, oldRole={}, newRole={}", userId, oldRole.getId(), roleId);
        return UserResponse.from(user);
    }

    /**
     * Set user allow/deny permission overrides (full replace).
     * Atomic DB transaction → cache invalidation.
     */
    @Transactional
    public Map<String, Object> setUserPermissions(Long userId, UserPermissionRequest request) {
        var user = findUserOrThrow(userId);
        if (user.getRole() == null) {
            throw new BusinessRuleException("Cannot set permissions for user without a role");
        }

        // Validate all permission IDs exist
        var allIds = new java.util.ArrayList<>(request.allowPermissionIds());
        allIds.addAll(request.denyPermissionIds());
        if (!allIds.isEmpty()) {
            var existingCount = permissionRepository.countByIdIn(allIds);
            if (existingCount != allIds.size()) {
                throw new ResourceNotFoundException("One or more permission IDs are invalid");
            }
        }

        // Atomic DB replace
        userAllowPermissionRepository.deleteByUserId(userId);
        userDenyPermissionRepository.deleteByUserId(userId);

        request.allowPermissionIds().forEach(permId ->
                userAllowPermissionRepository.save(new UserAllowPermission(userId, permId, null, null)));
        request.denyPermissionIds().forEach(permId ->
                userDenyPermissionRepository.save(new UserDenyPermission(userId, permId, null, null)));

        // Conditional rebuild: if bitmaps exist, rebuild in-place with preserved TTL
        cacheInvalidationService.onUserPermissionUpdateWithRebuild(userId);

        log.info("User permissions updated: userId={}, allow={}, deny={}",
                userId, request.allowPermissionIds().size(), request.denyPermissionIds().size());

        return Map.of(
                "userId", userId,
                "allowCount", request.allowPermissionIds().size(),
                "denyCount", request.denyPermissionIds().size()
        );
    }

    private User findUserOrThrow(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + id));
    }

    private String generateSigncode() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
    }
}
