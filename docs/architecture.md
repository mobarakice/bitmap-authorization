# System Architecture

## High-Level Architecture

```mermaid
graph TB
    Client[Client Application] -->|HTTP Request + JWT| APISIX[APISIX Gateway]
    APISIX -->|1. Decode JWT| APISIX
    APISIX -->|2. HGET perm:index| Redis[(Redis)]
    APISIX -->|3. GETBIT session bitmap| Redis
    APISIX -->|4. Proxy if allowed| SpringBoot[Spring Boot Service]
    APISIX -->|4. 403 if denied| Client

    SpringBoot -->|Login: Compute bitmap| Redis
    SpringBoot -->|Read permissions| PostgreSQL[(PostgreSQL)]

    subgraph "Runtime Path (O(1))"
        APISIX
        Redis
    end

    subgraph "Login Path (One-time)"
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

    S->>DB: SELECT bit_indexes WHERE role_id = ?
    DB-->>S: Role bit indexes [0,1,2,...]
    S->>DB: SELECT allow bit_indexes WHERE user_id = ?
    DB-->>S: Allow bit indexes [21]
    S->>DB: SELECT deny bit_indexes WHERE user_id = ?
    DB-->>S: Deny bit indexes [15]

    S->>R: SETBIT temp:role:* (build role bitmap)
    S->>R: SETBIT temp:allow:* (build allow bitmap)
    S->>R: SETBIT temp:deny:* (build deny bitmap)
    S->>R: EVAL compute_session_bitmap.lua
    Note over R: BITOP OR tmp = role + allow<br/>BITOP NOT inv = deny<br/>BITOP AND result = tmp + inv<br/>EXPIRE result TTL

    S->>R: HSET perm:index (sync if needed)
    S-->>A: JWT {uid, sid, exp}
    A-->>C: {access_token, expires_in}
```

## Authorization Check Flow

```mermaid
sequenceDiagram
    participant C as Client
    participant A as APISIX
    participant R as Redis

    C->>A: GET /api/orders/123 (Bearer JWT)

    A->>A: Decode JWT → uid=1, sid=abc123
    A->>A: Normalize path → /api/orders/{id}
    A->>A: Build perm key → GET:/api/orders/{id}

    alt LRU Cache Hit
        A->>A: perm_id = 16 (from cache)
    else LRU Cache Miss
        A->>R: HGET perm:index "GET:/api/orders/{id}"
        R-->>A: "16"
        A->>A: Cache perm_id=16
    end

    A->>A: chunk=16/1024=0, offset=16%1024=16
    A->>R: GETBIT user:1:session:abc123:perm:0 16
    R-->>A: 1

    alt bit = 1
        A->>C: Proxy to upstream (200)
    else bit = 0
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

## Bitmap Computation

```
Permission Formula:  final = (role_perm OR allow_perm) AND (NOT deny_perm)

Example (staff1, bit positions 0-7):

Role (STAFF):     1 0 1 0 0 0 1 0    (bits 0, 2, 6 set)
Allow (user):     0 0 0 0 0 1 0 0    (bit 5 = extra perm)
Deny  (user):     0 0 0 0 0 0 0 0    (no denials)

Step 1 - OR:      1 0 1 0 0 1 1 0    (role + allow)
Step 2 - NOT:     1 1 1 1 1 1 1 1    (not deny = all 1s)
Step 3 - AND:     1 0 1 0 0 1 1 0    (final permissions)
```
