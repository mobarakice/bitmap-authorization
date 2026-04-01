# API Flow Diagrams

Developer reference — sequence and flow diagrams for all core APIs.

---

## 1. User Create Without Initial Role

> **API:** `POST /api/admin/users`
> **Precondition:** User does not exist. Admin provides `username` and `password` only.
> Role is assigned separately after creation.

```mermaid
sequenceDiagram
    participant Admin as Admin Client
    participant UC as UserController
    participant US as UserService
    participant DB as PostgreSQL

    Admin->>UC: POST /api/admin/users {username, password}

    Note over UC,US: Step 1 — Validate
    US->>DB: SELECT EXISTS FROM users WHERE username = :username
    DB-->>US: false (unique)

    Note over US: Step 2 — Hash password
    US->>US: BCrypt.encode(password)

    Note over US,DB: Step 3 — Persist (role_id = 1 for default)
    US->>DB: INSERT INTO users (username, password_hash, role_id, enabled)<br/>VALUES (:username, :hash, NULL, true)
    DB-->>US: User created (userId)

    Note over US: No Redis interaction.<br/>User cannot login until role is assigned.

    US-->>UC: UserResponse {id, username, role: default, is_active: true}
    UC-->>Admin: 201 Created
```
---

## 2. User Role Assign and Allow/Deny Permission

> **API:** `PUT /api/admin/users/{userId}/role` — assign role to user who has none
> **API:** `PUT /api/admin/users/{userId}/permissions` — assign allow/deny overrides
> **Precondition:** User was created without a role (Flow 1). Admin now assigns role and optionally sets allow/deny permissions.

### 2a. First-Time Role Assignment

```mermaid
sequenceDiagram
    participant Admin as Admin Client
    participant UC as UserController
    participant US as UserService
    participant DB as PostgreSQL

    Admin->>UC: PUT /api/admin/users/{userId}/role {roleId}

    Note over UC,US: Step 1 — Validate
    US->>DB: SELECT * FROM users WHERE id = :userId
    DB-->>US: User (role = NULL)
    US->>DB: SELECT * FROM roles WHERE id = :roleId
    DB-->>US: Role exists

    Note over US,DB: Step 2 — Simple assignment (no old role to clear)
    US->>DB: UPDATE users SET role_id = :roleId WHERE id = :userId
    DB-->>US: Updated

    Note over US: No Redis invalidation needed.<br/>User had no bitmaps (never logged in with a role).<br/>Bitmaps will be lazy-built on first login.

    US-->>UC: UserResponse {id, username, role: roleName, enabled: true}
    UC-->>Admin: 200 OK
```

### 2b. Allow/Deny Permission Assignment

```mermaid
sequenceDiagram
    participant Admin as Admin Client
    participant UC as UserController
    participant US as UserService
    participant DB as PostgreSQL
    participant CI as CacheInvalidationService
    participant SP as SessionPermissionService
    participant R as Redis
    participant PS as Pub/Sub

    Admin->>UC: PUT /api/admin/users/{userId}/permissions<br/>{allowPermissionIds: [3,7], denyPermissionIds: [12]}

    Note over UC,US: Step 1 — Validate
    US->>DB: SELECT * FROM users WHERE id = :userId
    DB-->>US: User exists (has role)
    US->>DB: SELECT * FROM permissions WHERE id IN (:allIds)
    DB-->>US: All permission IDs valid

    Note over US,DB: Step 2 — DB Transaction (atomic)
    rect rgb(255, 245, 230)
        US->>DB: DELETE FROM user_allow_permissions WHERE user_id = :userId
        US->>DB: DELETE FROM user_deny_permissions WHERE user_id = :userId
        US->>DB: INSERT INTO user_allow_permissions VALUES (:userId, 3), (:userId, 7)
        US->>DB: INSERT INTO user_deny_permissions VALUES (:userId, 12)
    end

    Note over US,CI: Step 3 — Conditional Redis rebuild
    US->>CI: onUserPermissionUpdateWithRebuild(userId)
    CI->>SP: rebuildUserBitmapsIfExist(userId)

    SP->>R: EXISTS user:{uid}:allow:0
    SP->>R: EXISTS user:{uid}:deny:0

    alt At least one bitmap exists in Redis
        SP->>R: GET TTL of existing key (preserve remaining TTL)
        SP->>R: DEL user:{uid}:allow:* (all chunks)
        SP->>R: DEL user:{uid}:deny:* (all chunks)
        Note over SP,R: Rebuild immediately from new DB data
        SP->>DB: SELECT allow bit_indexes (virtual thread)
        SP->>DB: SELECT deny bit_indexes (virtual thread)
        SP->>R: PIPELINE SETBIT user:{uid}:allow:{chunk} (new allows)
        SP->>R: PIPELINE SETBIT user:{uid}:deny:{chunk} (new denies)
        SP->>R: PIPELINE EXPIRE (restore TTL)
    else No bitmaps exist
        Note over SP: Skip — lazy build on next login
    end

    SP->>R: DEL user:{uid}:sessions (force re-auth)

    Note over CI,PS: Step 4 — Pub/Sub
    CI->>PS: PUBLISH auth:invalidation "USER_UPDATED:{userId}"

    US-->>UC: {userId, allowCount: 2, denyCount: 1}
    UC-->>Admin: 200 OK
```
---

## 3. User Role Change and Allow/Deny Permission Update

> **API:** `PUT /api/admin/users/{userId}/role`
> **Precondition:** User already has a role assigned. Admin changes to a different role.
> **Effect:** Changes role. Clears ALL user-level allow/deny permissions
> (they were granted relative to the old role and are no longer valid).

```mermaid
sequenceDiagram
    participant Admin as Admin Client
    participant UC as UserController
    participant US as UserService
    participant DB as PostgreSQL
    participant SP as SessionPermissionService
    participant R as Redis
    participant PS as Pub/Sub

    Admin->>UC: PUT /api/admin/users/{userId}/role {newRoleId}

    Note over UC,US: Step 1 — Validate
    US->>DB: SELECT * FROM users WHERE id = :userId
    DB-->>US: User (current roleId = oldRoleId, oldRoleId != NULL)
    US->>DB: SELECT * FROM roles WHERE id = :newRoleId
    DB-->>US: New role exists

    alt oldRoleId == newRoleId
        US-->>UC: 200 OK (no-op, role unchanged)
    end

    Note over US,DB: Step 2 — DB Transaction (atomic)
    rect rgb(255, 245, 230)
        US->>DB: UPDATE users SET role_id = :newRoleId WHERE id = :userId
        US->>DB: DELETE FROM user_allow_permissions WHERE user_id = :userId
        US->>DB: DELETE FROM user_deny_permissions WHERE user_id = :userId
    end

    Note over US,R: Step 3 — Invalidate Redis caches
    US->>SP: invalidateUserBitmap(userId)
    SP->>R: DEL user:{uid}:allow:* (all chunks via SCAN)
    SP->>R: DEL user:{uid}:deny:* (all chunks via SCAN)
    SP->>R: DEL user:{uid}:sessions (force re-login)

    Note over US,R: Step 4 — Ensure new role bitmap exists (lazy build)
    US->>SP: ensureRoleBitmap(newRoleId)
    SP->>R: EXISTS role:{newRoleId}:perm:0
    alt New role bitmap missing
        SP->>R: SET lock:role_bitmap:{newRoleId} NX EX 30
        SP->>DB: SELECT bit_indexes WHERE role_id = :newRoleId
        DB-->>SP: [bit indexes]
        SP->>R: PIPELINE SETBIT role:{newRoleId}:perm:{chunk} offset 1
        SP->>R: DEL lock:role_bitmap:{newRoleId}
    end

    Note over US,PS: Step 5 — Pub/Sub for distributed coordination
    US->>PS: PUBLISH auth:invalidation "USER_UPDATED:{userId}"
    US->>PS: PUBLISH auth:invalidation "FORCE_REAUTH:{userId}"

    US-->>UC: UserResponse {id, username, newRole, enabled}
    UC-->>Admin: 200 OK {message: "Role changed, allow/deny cleared, re-login required"}
```

---

## 5. User Allow/Deny Permission Change (Recompute Bitmap If Exists)

> **API:** `PUT /api/admin/users/{userId}/permissions`
> **Effect:** Replaces the user's allow and/or deny permission overrides.
> If bitmaps exist in Redis, they are deleted and rebuilt immediately (not deferred to login).

```mermaid
sequenceDiagram
    participant Admin as Admin Client
    participant UC as UserController
    participant US as UserService
    participant DB as PostgreSQL
    participant CI as CacheInvalidationService
    participant SP as SessionPermissionService
    participant BM as BitmapService
    participant R as Redis
    participant PS as Pub/Sub

    Admin->>UC: PUT /api/admin/users/{userId}/permissions<br/>{allowPermissionIds: [3,7], denyPermissionIds: [12]}

    Note over UC,US: Step 1 — Validate
    US->>DB: SELECT * FROM users WHERE id = :userId
    DB-->>US: User exists (roleId)
    US->>DB: SELECT * FROM permissions WHERE id IN (:allIds)
    DB-->>US: All permission IDs valid

    Note over US,DB: Step 2 — DB Transaction (atomic)
    rect rgb(255, 245, 230)
        US->>DB: DELETE FROM user_allow_permissions WHERE user_id = :userId
        US->>DB: DELETE FROM user_deny_permissions WHERE user_id = :userId
        US->>DB: INSERT INTO user_allow_permissions (user_id, permission_id)<br/>VALUES (:userId, 3), (:userId, 7)
        US->>DB: INSERT INTO user_deny_permissions (user_id, permission_id)<br/>VALUES (:userId, 12)
    end

    Note over US,CI: Step 3 — Conditional bitmap rebuild
    US->>CI: onUserPermissionUpdateWithRebuild(userId)
    CI->>SP: rebuildUserBitmapsIfExist(userId)

    Note over SP,R: Step 3a — Check if bitmaps exist
    SP->>R: EXISTS user:{uid}:allow:0
    R-->>SP: 1 (exists)
    SP->>R: EXISTS user:{uid}:deny:0
    R-->>SP: 0 (absent)

    Note over SP,R: Step 3b — Preserve remaining TTL
    SP->>R: TTL user:{uid}:allow:0
    R-->>SP: 2400 (seconds remaining)

    Note over SP,R: Step 3c — Delete old bitmaps
    SP->>BM: deleteAll("user:{uid}:allow", totalChunks)
    BM->>R: SCAN + DEL user:{uid}:allow:*
    SP->>BM: deleteAll("user:{uid}:deny", totalChunks)
    BM->>R: SCAN + DEL user:{uid}:deny:*

    Note over SP,DB: Step 3d — Rebuild from new DB data
    par Virtual Thread 1
        SP->>DB: SELECT bit_indexes FROM user_allow_permissions<br/>WHERE user_id = :userId
        DB-->>SP: allowIndexes = [3, 7]
    and Virtual Thread 2
        SP->>DB: SELECT bit_indexes FROM user_deny_permissions<br/>WHERE user_id = :userId
        DB-->>SP: denyIndexes = [12]
    end

    Note over SP,R: Step 3e — Write new bitmaps with preserved TTL
    SP->>BM: buildBitmap("user:{uid}:allow", [3, 7])
    BM->>R: PIPELINE {<br/>  SETBIT user:{uid}:allow:0 3 1<br/>  SETBIT user:{uid}:allow:0 7 1<br/>} EXEC
    SP->>BM: setTtl("user:{uid}:allow", maxBitIndex, 2400)
    BM->>R: PIPELINE { EXPIRE user:{uid}:allow:0 2400 } EXEC

    SP->>BM: buildBitmap("user:{uid}:deny", [12])
    BM->>R: PIPELINE { SETBIT user:{uid}:deny:0 12 1 } EXEC
    SP->>BM: setTtl("user:{uid}:deny", maxBitIndex, 2400)
    BM->>R: PIPELINE { EXPIRE user:{uid}:deny:0 2400 } EXEC

    Note over SP,R: Step 3f — Invalidate sessions
    SP->>R: DEL user:{uid}:sessions

    Note over CI,PS: Step 4 — Pub/Sub
    CI->>PS: PUBLISH auth:invalidation "USER_UPDATED:{userId}"

    US-->>UC: {userId, allowCount: 2, denyCount: 1}
    UC-->>Admin: 200 OK
```
---

## 6. Role Permission Assignment (Add, Remove, In-Place Bitmap Update)

> **APIs:**
> - `POST /api/admin/roles/{roleId}/permissions` — Add individual permissions to a role
> - `DELETE /api/admin/roles/{roleId}/permissions` — Remove individual permissions from a role
> - `PUT /api/admin/roles/{roleId}/permissions` — Full replace (existing, unchanged)
>
> **Key detail:** If the role bitmap exists in Redis, individual add/remove updates it in-place
> via `SETBIT` (1 for add, 0 for remove) instead of full invalidation + lazy rebuild.

### 6a. Add Permissions to Role (In-Place Bitmap Update)

```mermaid
sequenceDiagram
    participant Admin as Admin Client
    participant RC as RoleController
    participant DB as PostgreSQL
    participant CI as CacheInvalidationService
    participant SP as SessionPermissionService
    participant BM as BitmapService
    participant R as Redis
    participant PS as Pub/Sub

    Admin->>RC: POST /api/admin/roles/{roleId}/permissions<br/>{permissionIds: [8, 16]}

    Note over RC,DB: Step 1 — Validate + filter duplicates
    RC->>DB: SELECT permission_id FROM role_permissions WHERE role_id = :roleId
    DB-->>RC: existing = [1, 3, 5]
    RC->>RC: newOnly = [8, 16] - [1, 3, 5] = [8, 16]

    Note over RC,DB: Step 2 — DB Insert new entries only
    RC->>DB: INSERT INTO role_permissions (role_id, permission_id)<br/>VALUES (:roleId, 8), (:roleId, 16)

    Note over RC,DB: Step 3 — Resolve permission IDs → bit_indexes
    RC->>DB: SELECT bit_index FROM permissions WHERE id IN (8, 16)
    DB-->>RC: bitIndexes = [22, 45]

    Note over RC,CI: Step 4 — In-place bitmap update
    RC->>CI: onRolePermissionAdd(roleId, [22, 45])
    CI->>SP: addRolePermissionBits(roleId, [22, 45])

    SP->>R: EXISTS role:{roleId}:perm:0
    alt Role bitmap EXISTS in Redis
        Note over SP,R: In-place SETBIT (no full rebuild)
        SP->>BM: setBit("role:{roleId}:perm", 22)
        BM->>R: SETBIT role:{roleId}:perm:0 22 1
        SP->>BM: setBit("role:{roleId}:perm", 45)
        BM->>R: SETBIT role:{roleId}:perm:0 45 1
    else Role bitmap ABSENT
        Note over SP: Skip — bitmap will include new permissions<br/>when lazy-built on next login
    end

    Note over SP,R: Step 5 — Cascade: invalidate ALL users with this role
    SP->>DB: SELECT id FROM users WHERE role_id = :roleId
    DB-->>SP: userIds = [1, 5, 42, ...]
    loop For each userId
        SP->>BM: deleteAll("user:{uid}:allow", totalChunks)
        SP->>BM: deleteAll("user:{uid}:deny", totalChunks)
        SP->>R: DEL user:{uid}:sessions
    end

    Note over CI,PS: Step 6 — Pub/Sub
    CI->>PS: PUBLISH auth:invalidation "ROLE_UPDATED:{roleId}"

    RC-->>Admin: 200 OK {roleId, added: 2, totalPermissions: 5}
```

### 6b. Remove Permissions from Role (In-Place Bitmap Clear)

```mermaid
sequenceDiagram
    participant Admin as Admin Client
    participant RC as RoleController
    participant DB as PostgreSQL
    participant CI as CacheInvalidationService
    participant SP as SessionPermissionService
    participant BM as BitmapService
    participant R as Redis
    participant PS as Pub/Sub

    Admin->>RC: DELETE /api/admin/roles/{roleId}/permissions<br/>{permissionIds: [3, 5]}

    Note over RC,DB: Step 1 — Resolve permission IDs → bit_indexes (before delete)
    RC->>DB: SELECT bit_index FROM permissions WHERE id IN (3, 5)
    DB-->>RC: bitIndexes = [7, 14]

    Note over RC,DB: Step 2 — DB Delete
    RC->>DB: DELETE FROM role_permissions<br/>WHERE role_id = :roleId AND permission_id IN (3, 5)

    Note over RC,CI: Step 3 — In-place bitmap clear
    RC->>CI: onRolePermissionRemove(roleId, [7, 14])
    CI->>SP: removeRolePermissionBits(roleId, [7, 14])

    SP->>R: EXISTS role:{roleId}:perm:0
    alt Role bitmap EXISTS in Redis
        Note over SP,R: In-place SETBIT 0 (clear bits, no full rebuild)
        SP->>BM: clearBit("role:{roleId}:perm", 7)
        BM->>R: SETBIT role:{roleId}:perm:0 7 0
        SP->>BM: clearBit("role:{roleId}:perm", 14)
        BM->>R: SETBIT role:{roleId}:perm:0 14 0
    else Role bitmap ABSENT
        Note over SP: Skip — already absent
    end

    Note over SP,R: Step 4 — Cascade: invalidate ALL users with this role
    SP->>DB: SELECT id FROM users WHERE role_id = :roleId
    DB-->>SP: userIds = [1, 5, 42, ...]
    loop For each userId
        SP->>BM: deleteAll("user:{uid}:allow", totalChunks)
        SP->>BM: deleteAll("user:{uid}:deny", totalChunks)
        SP->>R: DEL user:{uid}:sessions
    end

    Note over CI,PS: Step 5 — Pub/Sub
    CI->>PS: PUBLISH auth:invalidation "ROLE_UPDATED:{roleId}"

    RC-->>Admin: 200 OK {roleId, removed: 2, remainingPermissions: 3}
```

### 6c. Full Replace (Existing — Unchanged)

```mermaid
sequenceDiagram
    participant Admin as Admin Client
    participant RC as RoleController
    participant DB as PostgreSQL
    participant CI as CacheInvalidationService
    participant SP as SessionPermissionService
    participant BM as BitmapService
    participant R as Redis
    participant PS as Pub/Sub

    Admin->>RC: PUT /api/admin/roles/{roleId}/permissions<br/>{permissionIds: [1, 3, 5, 8, 16]}

    Note over RC,DB: Step 1 — DB Transaction (full replace)
    rect rgb(255, 245, 230)
        RC->>DB: DELETE FROM role_permissions WHERE role_id = :roleId
        RC->>DB: INSERT INTO role_permissions (role_id, permission_id)<br/>VALUES (:roleId, 1), (:roleId, 3), (:roleId, 5), (:roleId, 8), (:roleId, 16)
    end

    Note over RC,CI: Step 2 — Full invalidation (not in-place)
    RC->>CI: onRolePermissionUpdate(roleId)

    Note over CI,R: Step 3 — Invalidate role bitmap
    CI->>SP: invalidateRoleBitmap(roleId)
    SP->>BM: deleteAll("role:{roleId}:perm", totalChunks)
    BM->>R: SCAN + DEL role:{roleId}:perm:* (all chunks)

    Note over SP,R: Step 4 — Cascade: invalidate ALL users with this role
    SP->>DB: SELECT id FROM users WHERE role_id = :roleId
    DB-->>SP: userIds = [1, 5, 42, 99, ...]

    loop For each userId in userIds
        SP->>BM: deleteAll("user:{uid}:allow", totalChunks)
        BM->>R: DEL user:{uid}:allow:*
        SP->>BM: deleteAll("user:{uid}:deny", totalChunks)
        BM->>R: DEL user:{uid}:deny:*
        SP->>R: DEL user:{uid}:sessions
    end

    Note over CI,PS: Step 5 — Pub/Sub for other JVM instances
    CI->>PS: PUBLISH auth:invalidation "ROLE_UPDATED:{roleId}"

    Note over PS,R: On OTHER Service instances:
    PS-->>SP: CacheInvalidationListener receives "ROLE_UPDATED:{roleId}"
    SP->>R: Repeat steps 3-4 (invalidate role + users locally)

    RC-->>Admin: 200 OK {roleId, permissionsAssigned: 5}

    Note over Admin: All affected users must re-login.<br/>On next login:<br/>1. Role bitmap rebuilt from new permissions<br/>2. User allow/deny bitmaps rebuilt from DB
```

---

### 7b. AuthService Authorization Check (Fallback/Testing)

```mermaid
sequenceDiagram
    participant C as Client
    participant AC as AuthController
    participant JWT as JwtService
    participant PI as PermissionIndexService
    participant SP as SessionPermissionService
    participant BM as BitmapService
    participant R as Redis

    C->>AC: POST /api/auth/check<br/>Authorization: Bearer <token><br/>{action: "GET", resource: "/api/orders/{id}"}

    Note over AC,JWT: Step 1 — Validate JWT
    AC->>JWT: validateToken(token)
    JWT-->>AC: Claims {uid=1, sid=abc123, roleId=2}

    Note over AC,PI: Step 2 — Resolve bit_index
    AC->>PI: getPermissionIndex("GET", "/api/orders/{id}")
    PI->>R: HGET perm:index "GET:/api/orders/{id}"
    R-->>PI: "16"
    PI-->>AC: bitIndex = 16

    alt bitIndex is null (permission not registered)
        AC-->>C: {allowed: false, permissionIndex: null}
    end

    Note over AC,SP: Step 3 — Check bitmap formula
    AC->>SP: checkPermission(userId=1, roleId=2, bitIndex=16)

    Note over SP,R: Step 3a — Deny check (fast rejection)
    SP->>BM: getBit("user:1:deny", 16)
    BM->>R: GETBIT user:1:deny:0 16
    R-->>BM: 0
    BM-->>SP: false (not denied)

    Note over SP,R: Step 3b — Role + Allow check
    SP->>BM: getBit("role:2:perm", 16)
    BM->>R: GETBIT role:2:perm:0 16
    R-->>BM: 1
    BM-->>SP: true (role grants)

    SP->>BM: getBit("user:1:allow", 16)
    BM->>R: GETBIT user:1:allow:0 16
    R-->>BM: 0
    BM-->>SP: false

    Note over SP: result = (role=true OR allow=false) AND (NOT deny=false) = true
    SP-->>AC: true

    AC-->>C: {allowed: true, action: "GET",<br/>resource: "/api/orders/{id}", permissionIndex: 16}
```

---

## 8. Module, Feature & Capability Management

> These APIs, entities, and the capability resolution flow do not exist in the codebase.
> Diagrams below are design references. See [Entity Relationships](entity_relationships.md#2-presentation-layer-planned).

> **APIs:** `/api/admin/modules`, `/api/admin/features`, `/api/admin/capabilities`
> **Purpose:** Manage the presentation layer — modules organize features, features group
> capabilities, capabilities map business actions to HTTP-level permissions.
> **Hierarchy:** Module → ModuleFeature → Capability → Permission

### 8a. Capability → Permission Mapping

```mermaid
sequenceDiagram
    participant Admin as Admin Client
    participant MC as ModuleController
    participant DB as PostgreSQL
    participant CI as CacheInvalidationService
    participant PS as Pub/Sub

    Admin->>MC: PUT /api/admin/capabilities/{capId}/permissions<br/>{permissionIds: [14, 15, 16]}

    Note over MC,DB: Step 1 — Validate
    MC->>DB: SELECT * FROM capabilities WHERE id = :capId
    DB-->>MC: Capability exists (BOOKING_VIEW)
    MC->>DB: SELECT * FROM permissions WHERE id IN (14, 15, 16)
    DB-->>MC: All permission IDs valid

    Note over MC,DB: Step 2 — DB Transaction (atomic)
    rect rgb(255, 245, 230)
        MC->>DB: DELETE FROM capability_permissions WHERE capability_id = :capId
        MC->>DB: INSERT INTO capability_permissions (capability_id, permission_id)<br/>VALUES (:capId, 14), (:capId, 15), (:capId, 16)
    end

    Note over MC,CI: Step 3 — Invalidate capability cache
    MC->>CI: onCapabilityUpdate()
    CI->>PS: PUBLISH auth:invalidation "CAPABILITY_UPDATED"

    Note over PS: All JVM instances clear their<br/>in-memory capability→permission cache.<br/>Next login will resolve with new mappings.

    MC-->>Admin: 200 OK {capabilityId, code: "BOOKING_VIEW", permissionCount: 3}
```

### 8b. Capability Resolution at Login (Detail)

```mermaid
sequenceDiagram
    participant S as AuthService
    participant CS as CapabilityService
    participant DB as PostgreSQL
    participant SP as SessionPermissionService
    participant R as Redis

    S->>CS: resolveCapabilities(userId=1, roleId=2)

    Note over CS,DB: Step 1 — Load capability→permission map (cached)
    alt Cache hit
        CS->>CS: Use in-memory capabilityPermMap
    else Cache miss
        CS->>DB: SELECT c.id, c.code, c.feature_id, p.bit_index<br/>FROM capabilities c<br/>JOIN capability_permissions cp ON cp.capability_id = c.id<br/>JOIN permissions p ON p.id = cp.permission_id
        DB-->>CS: capabilityPermMap (cached via AtomicReference)
    end

    Note over CS: Step 2 — Evaluate each capability
    Note over CS: BOOKING_VIEW → bit_indexes [14, 15, 16]

    loop For each capability
        loop For each bit_index in capability
            CS->>SP: checkPermission(userId=1, roleId=2, bitIndex)
            SP->>R: GETBIT user:1:deny:0 {offset}
            R-->>SP: 0 (not denied)
            SP->>R: GETBIT role:2:perm:0 {offset}
            R-->>SP: 1 (role grants)
            SP-->>CS: true
        end
        Note over CS: ALL permissions pass → BOOKING_VIEW granted
    end

    Note over CS: Step 3 — Build module → feature → capability tree
    CS->>CS: Load modules + features (cached)
    CS->>CS: Attach granted capabilities to their features
    CS->>CS: Filter features with 0 granted capabilities
    CS->>CS: Filter modules with 0 features
    CS->>CS: Build parent→children hierarchy

    CS-->>S: ResolvedCapabilities {<br/>  capabilities: ["BOOKING_VIEW", "BOOKING_CREATE", ...],<br/>  modules: [{code: "COMMERCIAL", children: [{code: "BOOKING",<br/>    features: [{code: "BOOKING_MGMT", capabilities: [...]}]}]}]<br/>}
```

---

## Redis Pipeline Usage Summary

| API Flow | Pipeline Operations | Round-trips |
|---|---|---|
| Login -- role bitmap build | `SETBIT * N` (all role permissions) | 1 pipeline |
| Login -- allow bitmap build | `SETBIT * N` (allow indexes) | 1 pipeline |
| Login -- deny bitmap build | `SETBIT * N` (deny indexes) | 1 pipeline |
| Login -- allow TTL set | `EXPIRE * chunks` | 1 pipeline |
| Login -- deny TTL set | `EXPIRE * chunks` | 1 pipeline |
| Login -- TTL extend | `EXPIRE * chunks` (allow + deny) | 1 pipeline |
| Login -- capability resolution | 3x `GETBIT` per permission per capability | Pipelineable *(planned)* |
| Auth check (APISIX) | `EVAL` (3x GETBIT inside Lua) | 1 round-trip |
| Auth check (Spring) | 3x `GETBIT` (deny, role, allow) | 3 round-trips |
| Role perm add (in-place) | `EXISTS` + `SETBIT * N` (1 per new perm) | 1 + N round-trips |
| Role perm remove (in-place) | `EXISTS` + `SETBIT * N` (0 per removed perm) | 1 + N round-trips |
| Role perm full replace | `SCAN + DEL` (full invalidation) | 1+ per prefix |
| User perm change (rebuild) | `EXISTS` + `TTL` + `SCAN + DEL` + `SETBIT * N` + `EXPIRE` | 3+ pipelines |
| User perm change (lazy) | None (bitmaps absent) | 0 |
| Invalidation | `SCAN + DEL` per prefix | 1+ per prefix |
