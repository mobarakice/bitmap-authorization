# Testing Plan: Separate Allow/Deny Bitmap Architecture

## Test Strategy

### 1. Unit Tests (Java)

#### BitmapService Tests
```java
@Test
void testBuildBitmap_AllowList() {
    // Given: User has allow permissions [5, 10, 15]
    // When: buildBitmap("user:1:allow", [5, 10, 15])
    // Then: Verify GETBIT returns 1 for those indexes
}

@Test
void testBuildBitmap_DenyList() {
    // Given: User has deny permissions [7, 12]
    // When: buildBitmap("user:1:deny", [7, 12])
    // Then: Verify GETBIT returns 1 for those indexes
}

@Test
void testSetTtl_MultipleChunks() {
    // Given: Bitmap spans 3 chunks
    // When: setTtl("user:1:allow", maxBitIndex, 3600)
    // Then: Verify all 3 chunk keys have TTL = 3600
}
```

#### SessionPermissionService Tests
```java
@Test
void testOnLogin_BuildsAllowDenyBitmaps() {
    // Given: User with allow=[1,2,3], deny=[4,5]
    // When: onLogin(userId, sessionId, roleId, ttl)
    // Then: 
    //   - Verify user:1:allow:0 exists with bits 1,2,3 set
    //   - Verify user:1:deny:0 exists with bits 4,5 set
    //   - Verify TTL is set correctly
}

@Test
void testOnLogin_ExtendsTtlIfExists() {
    // Given: Allow/deny bitmaps already exist with TTL=1800
    // When: onLogin(userId, sessionId, roleId, 3600)
    // Then: Verify TTL extended to 3600
}

@Test
void testInvalidateUserBitmap_DeletesAllowAndDeny() {
    // Given: User has allow/deny bitmaps
    // When: invalidateUserBitmap(userId)
    // Then: 
    //   - Verify user:1:allow:* deleted
    //   - Verify user:1:deny:* deleted
    //   - Verify sessions invalidated
}

@Test
void testCheckPermission_RoleHasPermission() {
    // Given: Role has permission 10, user has no overrides
    // When: checkPermission(userId, roleId, 10)
    // Then: Return true
}

@Test
void testCheckPermission_AllowOverride() {
    // Given: Role lacks permission 10, user allow has 10
    // When: checkPermission(userId, roleId, 10)
    // Then: Return true
}

@Test
void testCheckPermission_DenyOverride() {
    // Given: Role has permission 10, user deny has 10
    // When: checkPermission(userId, roleId, 10)
    // Then: Return false
}

@Test
void testCheckPermission_AllowAndDeny() {
    // Given: Role lacks 10, user allow has 10, user deny has 10
    // When: checkPermission(userId, roleId, 10)
    // Then: Return false (deny wins)
}
```

#### JwtService Tests
```java
@Test
void testGenerateToken_IncludesRoleId() {
    // When: generateToken(userId=1, sessionId="abc", roleId=5)
    // Then: Decoded JWT contains uid=1, sid="abc", roleId=5
}

@Test
void testExtractRoleId() {
    // Given: JWT with roleId=5
    // When: extractRoleId(claims)
    // Then: Return 5
}
```

### 2. Integration Tests (Java + Redis)

#### Login Flow Tests
```java
@Test
@Transactional
void testLoginFlow_FirstLogin() {
    // Given: User with role=ADMIN, allow=[100], deny=[200]
    // When: POST /api/auth/login
    // Then:
    //   - JWT contains uid, sid, roleId
    //   - Redis has role:1:perm:* (role bitmap)
    //   - Redis has user:1:allow:* (allow bitmap)
    //   - Redis has user:1:deny:* (deny bitmap)
    //   - Redis has user:1:sessions (session tracking)
}

@Test
@Transactional
void testLoginFlow_SubsequentLogin() {
    // Given: User already logged in, bitmaps exist
    // When: POST /api/auth/login (new session)
    // Then:
    //   - Bitmaps not rebuilt (TTL extended)
    //   - New session added to user:1:sessions
}
```

#### Invalidation Tests
```java
@Test
@Transactional
void testInvalidation_RolePermissionChange() {
    // Given: User logged in with role=ADMIN
    // When: Admin adds permission to ADMIN role
    // Then:
    //   - role:1:perm:* deleted
    //   - user:1:allow:* and user:1:deny:* remain
    //   - Next request rebuilds role bitmap
}

@Test
@Transactional
void testInvalidation_UserAllowChange() {
    // Given: User logged in
    // When: Admin adds permission to user's allow list
    // Then:
    //   - user:1:allow:* deleted
    //   - role:1:perm:* and user:1:deny:* remain
    //   - user:1:sessions deleted (force re-login)
}

@Test
@Transactional
void testInvalidation_UserDenyChange() {
    // Given: User logged in
    // When: Admin adds permission to user's deny list
    // Then:
    //   - user:1:deny:* deleted
    //   - role:1:perm:* and user:1:allow:* remain
    //   - user:1:sessions deleted (force re-login)
}
```

### 3. APISIX Plugin Tests (Lua)

#### Unit Tests (using busted framework)
```lua
describe("bitmap-auth plugin", function()
    it("should allow when role has permission", function()
        -- Mock Redis: role bitmap has bit 10 set
        -- Mock Redis: allow/deny bitmaps empty
        -- Assert: request passes (200)
    end)

    it("should allow when allow list has permission", function()
        -- Mock Redis: role bitmap lacks bit 10
        -- Mock Redis: allow bitmap has bit 10 set
        -- Assert: request passes (200)
    end)

    it("should deny when deny list has permission", function()
        -- Mock Redis: role bitmap has bit 10 set
        -- Mock Redis: deny bitmap has bit 10 set
        -- Assert: request denied (403)
    end)

    it("should deny when no bitmap has permission", function()
        -- Mock Redis: all bitmaps lack bit 10
        -- Assert: request denied (403)
    end)

    it("should deny when deny overrides allow", function()
        -- Mock Redis: allow has bit 10, deny has bit 10
        -- Assert: request denied (403)
    end)
end)
```

#### Integration Tests (using APISIX test framework)
```yaml
# Test: Role permission allows access
- request:
    uri: /api/users
    method: GET
    headers:
      Authorization: Bearer <jwt_with_admin_role>
  response:
    status: 200

# Test: Allow list grants access
- request:
    uri: /api/admin/settings
    method: POST
    headers:
      Authorization: Bearer <jwt_with_user_role_but_allow_override>
  response:
    status: 200

# Test: Deny list blocks access
- request:
    uri: /api/users
    method: DELETE
    headers:
      Authorization: Bearer <jwt_with_admin_role_but_deny_override>
  response:
    status: 403
```

### 4. End-to-End Tests

#### Scenario 1: Normal User Flow
```gherkin
Given a user "john" with role "USER"
And role "USER" has permissions [READ_USERS, READ_ORDERS]
And user "john" has allow list [CREATE_ORDERS]
And user "john" has deny list [READ_USERS]

When user "john" logs in
Then JWT contains uid, sid, roleId

When user "john" requests GET /api/users
Then response is 403 (denied by deny list)

When user "john" requests GET /api/orders
Then response is 200 (allowed by role)

When user "john" requests POST /api/orders
Then response is 200 (allowed by allow list)

When user "john" requests DELETE /api/orders/123
Then response is 403 (not in any list)
```

#### Scenario 2: Role Permission Update
```gherkin
Given 100 users with role "USER"
And all users are logged in

When admin adds permission "DELETE_ORDERS" to role "USER"
Then role:2:perm:* is deleted from Redis
And user:*:allow:* and user:*:deny:* remain in Redis

When any user requests DELETE /api/orders/123
Then role bitmap is rebuilt with new permission
And request is allowed (200)
```

#### Scenario 3: User Allow List Update
```gherkin
Given user "john" is logged in
And user "john" has active sessions [session1, session2]

When admin adds permission "ADMIN_ACCESS" to john's allow list
Then user:1:allow:* is deleted from Redis
And user:1:sessions is deleted (sessions invalidated)

When user "john" makes any request with old JWT
Then response is 401 (session invalid)

When user "john" logs in again
Then new allow bitmap is built with "ADMIN_ACCESS"
And request to /api/admin is allowed (200)
```

### 5. Performance Tests

#### Latency Benchmark
```java
@Test
void benchmarkPermissionCheck_Sequential() {
    // Measure: 1000 permission checks with 3 sequential GETBIT calls
    // Expected: ~0.3ms per check (0.1ms × 3)
}

@Test
void benchmarkPermissionCheck_Pipelined() {
    // Measure: 1000 permission checks with pipelined GETBIT
    // Expected: ~0.15ms per check
}

@Test
void benchmarkPermissionCheck_LuaScript() {
    // Measure: 1000 permission checks with Lua EVAL
    // Expected: ~0.12ms per check
}
```

#### Load Test
```yaml
# Scenario: 10K concurrent users, 100 req/sec each
# Duration: 5 minutes
# Metrics:
#   - P50 latency < 10ms
#   - P99 latency < 50ms
#   - Error rate < 0.1%
#   - Redis CPU < 50%
#   - Redis memory stable
```

#### Memory Test
```java
@Test
void testMemoryUsage_10KUsers() {
    // Given: 10K users, 100K permissions
    // When: All users log in
    // Then:
    //   - Role bitmaps: ~1.25 MB (10 roles × 125 KB)
    //   - User allow bitmaps: ~25 MB (20% have overrides)
    //   - User deny bitmaps: ~6.25 MB (5% have overrides)
    //   - Total: ~32.5 MB
}
```

### 6. Regression Tests

#### Backward Compatibility
```java
@Test
void testOldJwtFormat_Rejected() {
    // Given: JWT without roleId claim
    // When: Request to APISIX
    // Then: 401 Unauthorized
}

@Test
void testOldRedisKeys_Ignored() {
    // Given: Old user:1:perm:* keys exist in Redis
    // When: Permission check
    // Then: New keys used, old keys ignored
}
```

### 7. Security Tests

#### Authorization Tests
```java
@Test
void testDenyAlwaysWins() {
    // Given: Role has perm, allow has perm, deny has perm
    // When: checkPermission()
    // Then: Return false (deny wins)
}

@Test
void testNoPermissionByDefault() {
    // Given: Permission not in role, allow, or deny
    // When: checkPermission()
    // Then: Return false (deny by default)
}

@Test
void testJwtTampering() {
    // Given: JWT with modified roleId
    // When: Request to APISIX
    // Then: 401 Unauthorized (signature invalid)
}
```

## Test Execution Plan

### Phase 1: Unit Tests (Day 1)
- Run all Java unit tests
- Target: 100% code coverage for new methods
- Fix any failures

### Phase 2: Integration Tests (Day 2)
- Run Redis integration tests
- Verify bitmap creation/invalidation
- Verify TTL behavior

### Phase 3: APISIX Tests (Day 3)
- Deploy APISIX with new plugin
- Run Lua unit tests
- Run APISIX integration tests

### Phase 4: E2E Tests (Day 4)
- Deploy full stack (Spring Boot + Redis + APISIX)
- Run end-to-end scenarios
- Verify all flows work correctly

### Phase 5: Performance Tests (Day 5)
- Run latency benchmarks
- Run load tests
- Verify memory usage

### Phase 6: Production Validation (Day 6-7)
- Deploy to staging environment
- Run smoke tests
- Monitor for 24 hours
- Deploy to production (canary)

## Success Criteria

- ✅ All unit tests pass (100% coverage)
- ✅ All integration tests pass
- ✅ All E2E tests pass
- ✅ Latency increase < 0.2ms (P99)
- ✅ Memory usage reduced by > 50%
- ✅ Zero authorization errors
- ✅ Zero security vulnerabilities
- ✅ Successful canary deployment

## Test Data Setup

### Roles
```sql
INSERT INTO roles (id, name) VALUES
(1, 'ADMIN'),
(2, 'USER'),
(3, 'GUEST');
```

### Permissions (100 test permissions)
```sql
INSERT INTO permissions (id, resource, action, bit_index) VALUES
(1, '/api/users', 'GET', 0),
(2, '/api/users', 'POST', 1),
(3, '/api/users', 'DELETE', 2),
-- ... 97 more
(100, '/api/admin/settings', 'DELETE', 99);
```

### Role Permissions
```sql
-- ADMIN: all permissions
INSERT INTO role_permissions (role_id, permission_id)
SELECT 1, id FROM permissions;

-- USER: read-only permissions
INSERT INTO role_permissions (role_id, permission_id)
SELECT 2, id FROM permissions WHERE action = 'GET';

-- GUEST: public endpoints only
INSERT INTO role_permissions (role_id, permission_id)
SELECT 3, id FROM permissions WHERE resource LIKE '/api/public%';
```

### Test Users
```sql
INSERT INTO users (id, username, password_hash, role_id) VALUES
(1, 'admin', '$2a$10$...', 1),
(2, 'user_with_allow', '$2a$10$...', 2),
(3, 'user_with_deny', '$2a$10$...', 2),
(4, 'user_with_both', '$2a$10$...', 2);

-- User 2: Allow override (can create users)
INSERT INTO user_allow_permissions (user_id, permission_id) VALUES (2, 2);

-- User 3: Deny override (cannot read users)
INSERT INTO user_deny_permissions (user_id, permission_id) VALUES (3, 1);

-- User 4: Both (allow create, deny delete)
INSERT INTO user_allow_permissions (user_id, permission_id) VALUES (4, 2);
INSERT INTO user_deny_permissions (user_id, permission_id) VALUES (4, 3);
```

## Monitoring & Observability

### Metrics to Track
- Redis GETBIT latency (P50, P95, P99)
- APISIX authorization latency
- Redis memory usage (by key pattern)
- Cache hit rate (role/allow/deny bitmaps)
- Invalidation frequency (by type)
- Error rate (401, 403, 500)

### Alerts
- Authorization latency > 50ms (P99)
- Redis memory > 80% capacity
- Error rate > 1%
- Invalidation rate > 100/sec

### Dashboards
- Authorization Performance (latency, throughput)
- Redis Health (memory, CPU, connections)
- Security Events (403 denials, 401 failures)
- Invalidation Activity (by type, frequency)
