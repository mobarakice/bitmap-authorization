# System Architecture

## High-Level Architecture

```mermaid
graph TB
    Client[Client Application] -->|HTTP Request + JWT| APISIX[APISIX Gateway]
    APISIX -->|1. Decode JWT → uid, sid, roleId| APISIX
    APISIX -->|2. HGET perm:index| Redis[(Redis)]
    APISIX -->|3a. GETBIT deny bitmap| Redis
    APISIX -->|3b. GETBIT role bitmap| Redis
    APISIX -->|3c. GETBIT allow bitmap| Redis
    APISIX -->|4. Proxy if allowed| SpringBoot[Spring Boot Service]
    APISIX -->|4. 403 if denied| Client

    SpringBoot -->|Login: Lazy-build bitmaps| Redis
    SpringBoot -->|Read permissions| PostgreSQL[(PostgreSQL)]

    subgraph "Runtime Path (O(1))"
        APISIX
        Redis
    end

    subgraph "Login Path (Lazy / One-time per role)"
        SpringBoot
        PostgreSQL
    end
```

## Login Flow Sequence

```mermaid
sequenceDiagram
    participant C as Client
    participant A as APISIX
    participant S as Spring Boot
    participant DB as PostgreSQL
    participant R as Redis

    C->>A: POST /api/auth/login {username, password}
    A->>S: Proxy (bypass auth)
    S->>DB: Find user by username
    DB-->>S: User (with role_id)
    S->>S: Validate password
    S->>S: Generate sessionId, build JWT {uid, sid, roleId}

    Note over S,R: Tier 1 — Role Bitmap (PERMANENT, lazy)
    S->>R: EXISTS role:{roleId}:perm:0
    alt Role bitmap missing
        S->>R: SET lock:role_bitmap:{roleId} NX EX 30
        S->>DB: SELECT bit_indexes WHERE role_id = ?
        DB-->>S: Role bit indexes [0,1,2,...]
        S->>R: SETBIT role:{roleId}:perm:{chunk} offset 1 (pipelined, no TTL)
        S->>R: DEL lock:role_bitmap:{roleId}
    end

    Note over S,R: Tier 2 — User Allow/Deny Bitmaps (TTL = token expiry)
    S->>R: EXISTS user:{uid}:allow:0 / user:{uid}:deny:0
    alt Bitmaps missing
        S->>DB: SELECT allow bit_indexes WHERE user_id = ? (virtual thread)
        S->>DB: SELECT deny bit_indexes WHERE user_id = ? (virtual thread)
        DB-->>S: Allow indexes / Deny indexes
        opt User has allow overrides
            S->>R: SETBIT user:{uid}:allow:{chunk} offset 1 (pipelined)
            S->>R: EXPIRE user:{uid}:allow:{chunk} {ttl}
        end
        opt User has deny overrides
            S->>R: SETBIT user:{uid}:deny:{chunk} offset 1 (pipelined)
            S->>R: EXPIRE user:{uid}:deny:{chunk} {ttl}
        end
    else Bitmaps exist — extend TTL only
        S->>R: EXPIRE user:{uid}:allow:{chunk} {ttl} (pipelined)
        S->>R: EXPIRE user:{uid}:deny:{chunk} {ttl} (pipelined)
    end

    Note over S,R: Session Tracking
    S->>R: SADD user:{uid}:sessions {sessionId}
    S->>R: EXPIRE user:{uid}:sessions {ttl}

    S->>R: HSET perm:index (sync if needed)
    S-->>A: JWT {uid, sid, roleId, exp}
    A-->>C: {access_token, token_type, expires_in}
```

## Authorization Check Flow

```mermaid
sequenceDiagram
    participant C as Client
    participant A as APISIX
    participant R as Redis

    C->>A: GET /api/orders/123 (Bearer JWT)

    A->>A: Decode JWT → uid=1, sid=abc123, roleId=2
    A->>A: Normalize path → /api/orders/{id}
    A->>A: Build perm key → GET:/api/orders/{id}

    alt LRU Cache Hit
        A->>A: perm_id = 16 (from ngx.shared.perm_cache)
    else LRU Cache Miss
        A->>R: HGET perm:index "GET:/api/orders/{id}"
        R-->>A: "16"
        A->>A: Cache perm_id=16 (TTL=60s)
    end

    A->>A: chunk = floor(16 / 1024) = 0, offset = 16 % 1024 = 16

    Note over A,R: Step 1 — Deny-first check (fast rejection)
    A->>R: GETBIT user:1:deny:0 16
    R-->>A: 0

    Note over A,R: Step 2 — Role and Allow check
    A->>R: GETBIT role:2:perm:0 16
    R-->>A: 1
    A->>R: GETBIT user:1:allow:0 16
    R-->>A: 0

    Note over A: roleBit=1 OR allowBit=0 → ALLOW

    alt (roleBit OR allowBit) = 1
        A->>C: Proxy to upstream (200) + X-User-Id, X-Session-Id headers
    else (roleBit OR allowBit) = 0
        A->>C: 403 Forbidden
    end
```

## Data Model

```mermaid
erDiagram
    ROLES ||--o{ USERS : "has many"
    ROLES ||--o{ ROLE_PERMISSIONS : "has"
    PERMISSIONS ||--o{ ROLE_PERMISSIONS : "assigned to"
    USERS ||--o{ USER_ALLOW_PERMISSIONS : "has extra"
    USERS ||--o{ USER_DENY_PERMISSIONS : "has denied"
    PERMISSIONS ||--o{ USER_ALLOW_PERMISSIONS : "granted to"
    PERMISSIONS ||--o{ USER_DENY_PERMISSIONS : "denied from"

    ROLES {
        int id PK
        varchar name UK
        varchar description
    }

    USERS {
        bigint id PK
        varchar username UK
        varchar password_hash
        int role_id FK
        boolean enabled
    }

    PERMISSIONS {
        int id PK
        varchar resource
        varchar action
        int bit_index UK
        varchar description
    }

    ROLE_PERMISSIONS {
        int role_id FK
        int permission_id FK
    }

    USER_ALLOW_PERMISSIONS {
        bigint user_id FK
        int permission_id FK
    }

    USER_DENY_PERMISSIONS {
        bigint user_id FK
        int permission_id FK
    }
```

## Redis Key Patterns

| Key Pattern                        | Type          | TTL              | Description                                       |
|------------------------------------|---------------|------------------|---------------------------------------------------|
| `perm:index`                       | HASH          | None (permanent) | Maps `METHOD:path` → permission `bit_index`       |
| `role:{roleId}:perm:{chunk}`       | Binary STRING | None (permanent) | Role permission bitmap, chunk N (1024 bits each)  |
| `user:{uid}:allow:{chunk}`         | Binary STRING | Token expiry     | User extra-grant bitmap; created only if needed   |
| `user:{uid}:deny:{chunk}`          | Binary STRING | Token expiry     | User deny bitmap; created only if needed          |
| `user:{uid}:sessions`              | SET           | Token expiry     | Active session IDs for this user                  |
| `lock:role_bitmap:{roleId}`        | STRING        | 30 s             | Distributed build lock; prevents thundering herd  |

### Chunk Sizing

Bitmaps are split into chunks of `app.bitmap.chunk-size` bits (default: 1024).
A permission with `bit_index = N` maps to:
- key:    `{prefix}:{floor(N / 1024)}`
- offset: `N % 1024`

With 40 permissions (indexes 0–39) all bits fit in chunk 0. With 2000 permissions, chunks 0 and 1 are used.

### Cache Invalidation

Coordinated via Redis Pub/Sub on channel `auth:invalidation`:

| Event                    | Pub/Sub Message          | Effect                                                    |
|--------------------------|--------------------------|-----------------------------------------------------------|
| Role permissions changed | `ROLE_UPDATED:{roleId}`  | Delete `role:{roleId}:perm:*`; invalidate affected users  |
| User override changed    | `USER_UPDATED:{uid}`     | Delete `user:{uid}:allow:*`, `user:{uid}:deny:*`, sessions|
| Permission added/removed | `PERM_INDEX_REFRESHED`   | Atomically re-sync `perm:index` from DB                   |
| Force re-auth            | `FORCE_REAUTH:{uid}`     | Delete user bitmaps + sessions; forces re-login           |

## Bitmap Computation

```
Permission Formula:  (role_perm OR allow_perm) AND (NOT deny_perm)
```

The formula is evaluated at **request time** by the APISIX plugin via three sequential `GETBIT` calls.
No BITOP precomputation occurs at login — bitmaps are written (`SETBIT`) at login and read at every request.

### Evaluation Order (per request, in APISIX)

```
Step 1 — Deny check (fast rejection):
  GETBIT user:{uid}:deny:{chunk} {offset}
  → If 1: return 403 immediately

Step 2 — Role and allow check:
  GETBIT role:{roleId}:perm:{chunk} {offset}   → roleBit
  GETBIT user:{uid}:allow:{chunk} {offset}      → allowBit
  → If (roleBit OR allowBit) = 1: 200 OK
  → Else: 403 Forbidden
```

### Example (uid=1, roleId=2, bit positions 0–7)

```
Role (roleId=2):  1 0 1 0 0 0 1 0    (bits 0,2,6 — PERMANENT, shared by all users in role)
Allow (uid=1):    0 0 0 0 0 1 0 0    (bit 5 = extra grant — TTL-scoped, user-specific)
Deny  (uid=1):    0 0 0 0 0 0 0 0    (no denials — key absent, GETBIT returns 0)

For bit 5 (extra resource):
  deny=0 → not rejected
  role=0 → role does not grant this
  allow=1 → user-specific grant applies
  Result: ALLOW

For bit 3 (admin-only resource):
  deny=0 → not rejected
  role=0 → role does not grant this
  allow=0 → no user-specific grant
  Result: DENY (403)
```

### Optimization Notes

- **Role bitmaps are permanent.** Built once per role on first login (under a 30 s distributed lock).
  Invalidated only when role-permission mappings change via the admin API.
- **Allow/deny bitmaps are conditional.** Only created when the user has overrides in the DB.
  Users with no overrides have no allow/deny keys; `GETBIT` on a missing key returns 0 by Redis spec.
- **TTL is extended on each login** to equal the new token expiry — no bitmap rebuild needed for returning users.
- **Deny-first** short-circuits evaluation: a denied user never hits the role/allow checks.
