package com.pss.auth.controller;

import com.pss.auth.dto.UserCreateRequest;
import com.pss.auth.dto.UserPermissionRequest;
import com.pss.auth.dto.UserResponse;
import com.pss.auth.dto.UserRoleRequest;
import com.pss.auth.dto.UserUpdateRequest;
import com.pss.auth.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;
import java.util.Map;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/admin/users")
@Tag(name = "Users Admin", description = "Endpoints for managing users, role assignments, and permission overrides")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping
    @Operation(summary = "List Users", description = "Retrieve all users.")
    public ResponseEntity<List<UserResponse>> listUsers() {
        return ResponseEntity.ok(userService.listUsers());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get User", description = "Get a user by ID.")
    public ResponseEntity<UserResponse> getUser(@PathVariable Long id) {
        return ResponseEntity.ok(userService.getUser(id));
    }

    @PostMapping
    @Operation(summary = "Create User", description = "Create a new user without a role. User cannot login until a role is assigned.")
    public ResponseEntity<UserResponse> createUser(@Valid @RequestBody UserCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(userService.createUser(request));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update User", description = "Update user profile fields.")
    public ResponseEntity<UserResponse> updateUser(
            @PathVariable Long id,
            @Valid @RequestBody UserUpdateRequest request) {
        return ResponseEntity.ok(userService.updateUser(id, request));
    }

    @PutMapping("/{id}/role")
    @Operation(summary = "Assign/Change Role", description = "Assign a role to a user or change their existing role. Role change clears all allow/deny overrides.")
    public ResponseEntity<UserResponse> assignRole(
            @PathVariable Long id,
            @Valid @RequestBody UserRoleRequest request) {
        return ResponseEntity.ok(userService.assignRole(id, request.roleId()));
    }

    @PutMapping("/{id}/permissions")
    @Operation(summary = "Set Permission Overrides", description = "Replace all allow/deny permission overrides for a user.")
    public ResponseEntity<Map<String, Object>> setPermissions(
            @PathVariable Long id,
            @RequestBody UserPermissionRequest request) {
        return ResponseEntity.ok(userService.setUserPermissions(id, request));
    }
}
