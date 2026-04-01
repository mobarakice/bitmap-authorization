package com.pss.auth.service;

import com.pss.auth.config.BitmapConfig;
import com.pss.auth.repository.PermissionRepository;
import com.pss.auth.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for SessionPermissionService with separate allow/deny bitmap architecture.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SessionPermissionServiceTest {

    @Mock
    private PermissionRepository permissionRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private BitmapService bitmapService;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private HashOperations<String, Object, Object> hashOperations;

    @Mock
    private SetOperations<String, String> setOperations;

    private SessionPermissionService service;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);
        when(stringRedisTemplate.opsForSet()).thenReturn(setOperations);
        // Stub buildChunkKey to return expected key format: prefix:chunkId
        when(bitmapService.buildChunkKey(anyString(), anyInt()))
                .thenAnswer(inv -> inv.getArgument(0) + ":" + inv.getArgument(1));
        service = new SessionPermissionService(
                permissionRepository,
                userRepository,
                bitmapService,
                stringRedisTemplate
        );
    }

    @Test
    void testCheckPermission_RoleHasPermission_ReturnsTrue() {
        // Given: Role has permission 10, user has no overrides
        Long userId = 1L;
        Integer roleId = 2;
        int bitIndex = 10;

        when(bitmapService.getBit("role:2:perm", 10)).thenReturn(true);
        when(bitmapService.getBit("user:1:allow", 10)).thenReturn(false);
        when(bitmapService.getBit("user:1:deny", 10)).thenReturn(false);

        // When
        boolean result = service.checkPermission(userId, roleId, bitIndex);

        // Then
        assertTrue(result, "Should allow when role has permission");
        verify(bitmapService).getBit("role:2:perm", 10);
        verify(bitmapService).getBit("user:1:allow", 10);
        verify(bitmapService).getBit("user:1:deny", 10);
    }

    @Test
    void testCheckPermission_AllowOverride_ReturnsTrue() {
        // Given: Role lacks permission 10, user allow has 10
        Long userId = 1L;
        Integer roleId = 2;
        int bitIndex = 10;

        when(bitmapService.getBit("role:2:perm", 10)).thenReturn(false);
        when(bitmapService.getBit("user:1:allow", 10)).thenReturn(true);
        when(bitmapService.getBit("user:1:deny", 10)).thenReturn(false);

        // When
        boolean result = service.checkPermission(userId, roleId, bitIndex);

        // Then
        assertTrue(result, "Should allow when allow list has permission");
    }

    @Test
    void testCheckPermission_DenyOverride_ReturnsFalse() {
        // Given: Role has permission 10, user deny has 10
        Long userId = 1L;
        Integer roleId = 2;
        int bitIndex = 10;

        when(bitmapService.getBit("role:2:perm", 10)).thenReturn(true);
        when(bitmapService.getBit("user:1:allow", 10)).thenReturn(false);
        when(bitmapService.getBit("user:1:deny", 10)).thenReturn(true);

        // When
        boolean result = service.checkPermission(userId, roleId, bitIndex);

        // Then
        assertFalse(result, "Should deny when deny list has permission");
    }

    @Test
    void testCheckPermission_DenyWinsOverAllow_ReturnsFalse() {
        // Given: Role lacks 10, user allow has 10, user deny has 10
        Long userId = 1L;
        Integer roleId = 2;
        int bitIndex = 10;

        when(bitmapService.getBit("role:2:perm", 10)).thenReturn(false);
        when(bitmapService.getBit("user:1:allow", 10)).thenReturn(true);
        when(bitmapService.getBit("user:1:deny", 10)).thenReturn(true);

        // When
        boolean result = service.checkPermission(userId, roleId, bitIndex);

        // Then
        assertFalse(result, "Deny should win over allow");
    }

    @Test
    void testCheckPermission_NoPermission_ReturnsFalse() {
        // Given: No bitmap has permission 10
        Long userId = 1L;
        Integer roleId = 2;
        int bitIndex = 10;

        when(bitmapService.getBit("role:2:perm", 10)).thenReturn(false);
        when(bitmapService.getBit("user:1:allow", 10)).thenReturn(false);
        when(bitmapService.getBit("user:1:deny", 10)).thenReturn(false);

        // When
        boolean result = service.checkPermission(userId, roleId, bitIndex);

        // Then
        assertFalse(result, "Should deny by default when no permission found");
    }

    @Test
    void testInvalidateUserBitmap_DeletesAllowAndDeny() {
        // Given: User has allow/deny bitmaps
        Long userId = 1L;
        when(permissionRepository.findMaxBitIndex()).thenReturn(Optional.of(2047));
        when(bitmapService.getTotalChunks(2047)).thenReturn(2);

        // When
        service.invalidateUserBitmap(userId);

        // Then
        verify(bitmapService).deleteAll("user:1:allow", 2);
        verify(bitmapService).deleteAll("user:1:deny", 2);
        verify(stringRedisTemplate).delete("user:1:sessions");
    }

    @Test
    void testOnLogin_BuildsAllowDenyBitmaps_WhenMissing() {
        // Given: User with allow=[1,2,3], deny=[4,5]
        Long userId = 1L;
        String sessionId = "session123";
        Integer roleId = 2;
        long ttlSeconds = 3600;

        when(permissionRepository.findMaxBitIndex()).thenReturn(Optional.of(1023));
        when(stringRedisTemplate.hasKey("role:2:perm:0")).thenReturn(true);
        when(stringRedisTemplate.hasKey("user:1:allow:0")).thenReturn(false);
        when(stringRedisTemplate.hasKey("user:1:deny:0")).thenReturn(false);
        when(permissionRepository.findAllowBitIndexesByUserId(userId))
                .thenReturn(List.of(1, 2, 3));
        when(permissionRepository.findDenyBitIndexesByUserId(userId))
                .thenReturn(List.of(4, 5));

        // When
        service.onLogin(userId, sessionId, roleId,"", ttlSeconds);

        // Then
        verify(bitmapService).buildBitmap("user:1:allow", List.of(1, 2, 3));
        verify(bitmapService).buildBitmap("user:1:deny", List.of(4, 5));
        verify(bitmapService).setTtl("user:1:allow", 1023, 3600);
        verify(bitmapService).setTtl("user:1:deny", 1023, 3600);
    }

    @Test
    void testOnLogin_ExtendsTtl_WhenBitmapsExist() {
        // Given: Allow/deny bitmaps already exist
        Long userId = 1L;
        String sessionId = "session123";
        Integer roleId = 2;
        long ttlSeconds = 3600;

        when(permissionRepository.findMaxBitIndex()).thenReturn(Optional.of(1023));
        when(stringRedisTemplate.hasKey("role:2:perm:0")).thenReturn(true);
        when(stringRedisTemplate.hasKey("user:1:allow:0")).thenReturn(true);
        when(stringRedisTemplate.hasKey("user:1:deny:0")).thenReturn(true);

        // When
        service.onLogin(userId, sessionId, roleId, "",ttlSeconds);

        // Then
        verify(bitmapService, never()).buildBitmap(anyString(), anyList());
        verify(bitmapService).setTtl("user:1:allow", 1023, 3600);
        verify(bitmapService).setTtl("user:1:deny", 1023, 3600);
    }
}
