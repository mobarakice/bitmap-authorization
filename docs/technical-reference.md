# Technical Reference

## Performance

### Runtime Authorization Check (APISIX → Redis)

| Operation | Latency | Notes |
|---|---|---|
| JWT decode (local) | ~0.05 ms | In-memory, no network |
| Path normalization | ~0.01 ms | String manipulation |
| Perm index lookup — LRU hit | ~0.001 ms | `ngx.shared.perm_cache` |
| Perm index lookup — LRU miss | ~0.1–0.3 ms | `HGET perm:index` |
| `EVAL check_permission` | ~0.1–0.2 ms | Single round trip; deny + role + allow inside one server-side script |
| **Total (cache warm)** | **~0.2 ms** | |
| **Total (cache cold)** | **~0.45 ms** | |

The three `GETBIT` calls (`deny`, `role`, `allow`) are consolidated into a single `EVAL` executed server-side on Redis. This eliminates two extra network round trips compared to sequential calls, keeping P99 within ~0.12 ms of the old single-bitmap baseline.

#### Lua EVAL Script (`check_permission.lua`)

Canonical source at `src/main/resources/scripts/check_permission.lua`; inlined as `CHECK_PERM_SCRIPT` in `apisix/plugins/bitmap-auth.lua`.

##### Version 1
```lua
-- KEYS[1] = role chunk key   e.g. {user:1}:role:2:perm:0
-- KEYS[2] = allow chunk key  e.g. {user:1}:allow:0
-- KEYS[3] = deny chunk key   e.g. {user:1}:deny:0
-- ARGV[1] = bit offset within chunk
-- Returns: 1 (ALLOWED) or 0 (DENIED)

local denyBit = redis.call('GETBIT', KEYS[3], tonumber(ARGV[1]))
if denyBit == 1 then return 0 end
local roleBit  = redis.call('GETBIT', KEYS[1], tonumber(ARGV[1]))
if roleBit == 1 then return 1 end
local allowBit = redis.call('GETBIT', KEYS[2], tonumber(ARGV[1]))
if allowBit == 1 then return 1 end
return 0
```

#### Lua EVAL Script V2 — Full Request Evaluation Inside Redis

> **Proposed -- not implemented.** V1 is the current production script. V2 is documented for comparison.

V2 consolidates **every** per-request Redis operation — token validation, permission index resolution, chunk math, and all three bitmap checks — into a single server-side `EVAL`. APISIX sends one command; Redis returns the authorization decision.

```lua
-- KEYS[1] = role key prefix    e.g. {user:1}:role:2:perm:
-- KEYS[2] = allow key prefix   e.g. {user:1}:allow:
-- KEYS[3] = deny key prefix    e.g. {user:1}:deny:
-- KEYS[4] = permission key     e.g. GET:/api/orders/{id}
-- KEYS[5] = token store key    e.g. user:1:token
-- ARGV[1] = token hash field   e.g. "sid"
-- ARGV[2] = token value        (sid from JWT)
-- ARGV[3] = chunk_size         e.g. "1024"
-- Returns: 1 (ALLOW), 0 (DENY), -1 (perm not found), -2 (token invalid)

local auth = redis.call('HGET', KEYS[5], ARGV[1])
if not auth or auth ~= ARGV[2] then return -2 end
local perm_id = redis.call('HGET', 'perm:index', KEYS[4])
if not perm_id then return -1 end
perm_id = tonumber(perm_id)
local chunk_size = tonumber(ARGV[3])
local chunk  = tostring(math.floor(perm_id / chunk_size))
local offset = perm_id % chunk_size
local denyBit  = redis.call('GETBIT', KEYS[3] .. chunk, offset)
if denyBit == 1 then return 0 end
local roleBit  = redis.call('GETBIT', KEYS[1] .. chunk, offset)
if roleBit == 1 then return 1 end
local allowBit = redis.call('GETBIT', KEYS[2] .. chunk, offset)
if allowBit == 1 then return 1 end return 0
```

**Server-side commands per call:** `HGET` (token) + `HGET` (perm:index) + up to 3× `GETBIT` = **5 ops**, all executed without leaving Redis.

#### V1 vs V2 Performance Comparison

| Metric | V1 (LRU warm) | V1 (LRU cold) | V2 |
|---|---|---|---|
| APISIX → Redis round trips | 1 | 2 | 1 |
| Redis commands per request | 3 (server-side EVAL) | 1 HGET + 3 EVAL | 5 (server-side EVAL) |
| EVAL server-side execution | ~3–5 µs | ~3–5 µs | ~10–15 µs |
| Request latency (LAN Redis) | ~0.20 ms | ~0.45 ms | ~0.25 ms |
| Redis TPS ceiling (single instance) | ~200 K | ~100 K | ~67 K–100 K |
| **APISIX TPS per node** | **50 K–100 K** | **20 K–50 K** | **30 K–60 K** |
| `perm:index` caching | LRU in APISIX (60 s TTL) | LRU miss | None — server-side always |
| Token validation | HMAC-SHA256 in APISIX | HMAC-SHA256 in APISIX | Redis HGET comparison |
| Native token revocation | No | No | Yes — delete key |

**TPS ceiling derivation for V2:**

Redis single instance executes scripts serially. At ~10–15 µs per V2 EVAL:

```
1 s / 12.5 µs (midpoint) = 80 K EVAL/s  →  80 K auth checks/s (Redis bound)
```

Compare V1 LRU-warm: `1 s / 4 µs = 250 K EVAL/s` — Redis CPU usage is ~3× higher per request in V2.

APISIX worker TPS is not the bottleneck: async I/O means each worker handles thousands of concurrent in-flight requests. Redis throughput is the ceiling at scale.

#### When V2 Wins

- Permission set is large or volatile → LRU hit rate is low → V1 cold path is frequent → V2's single round trip advantage materialises
- Native token revocation is required without a JWT blacklist
- Redis is co-located with APISIX (`< 0.1 ms` RTT) — the 1 vs 2 round trip gap widens on higher latency networks

#### When V1 Wins

- Permission set is finite and stable → LRU hit rate is high → V1 warm is faster and uses 3× less Redis CPU
- Stateless JWT must be preserved (security posture) — V2 requires storing tokens in Redis, making session validity dependent on Redis key integrity
- High-throughput target (> 60 K TPS) — V2's Redis CPU cost becomes the limiting factor earlier

#### ⚠️ Architectural Trade-off in V2

V2 replaces HMAC-SHA256 JWT signature verification (in-memory, in APISIX) with a Redis `HGET` equality check. This shifts the trust boundary:

- On login, the session token must be written to Redis (`HSET user:{uid}:token sid <value>`)
- Security now depends on Redis key integrity — a write-access breach to Redis allows forging any session
- The architecture becomes **stateful**: token revocation is native but the stateless JWT guarantee is lost

V1 keeps JWT verification in APISIX (cryptographic, no Redis dependency); V2 trades that for a single round trip and native revocation.

### Throughput Estimates

| Scenario | Expected QPS | Bottleneck |
|---|---|---|
| LRU warm, single Redis | 50 K–100 K | APISIX worker concurrency |
| LRU cold, single Redis | 20 K–50 K | `HGET perm:index` + `EVAL` (3 GETBIT) |
| With connection pooling | 30 K–80 K | Redis pipeline |

Redis single-instance limits: `GETBIT` ~100 K ops/s per core; `HGET` ~80 K ops/s per core.

### Login Overhead

| Operation | Latency | Notes |
|---|---|---|
| DB: fetch role bit indexes | ~2–5 ms | Single indexed query |
| DB: fetch allow + deny indexes | ~1–2 ms each | Parallel virtual threads |
| Redis: pipelined SETBIT (role) | ~5–15 ms | Once per role, then cached |
| Redis: pipelined SETBIT (allow/deny) | ~2–5 ms | Only if user has overrides |
| Redis: EXPIRE on allow/deny | ~0.5 ms | |
| **Total — first login (role bitmap miss)** | **~15–30 ms** | Role bitmap built + locked |
| **Total — subsequent logins** | **~5–10 ms** | Role bitmap hit, TTL extend only |

### Admin Operations Overhead

| Operation | Latency | Notes |
|---|---|---|
| Role perm add (in-place, N perms) | ~0.1 ms + N×0.05 ms | `EXISTS` + N×`SETBIT` |
| Role perm remove (in-place, N perms) | ~0.1 ms + N×0.05 ms | `EXISTS` + N×`SETBIT 0` |
| Role perm full replace | ~5–50 ms | `SCAN + DEL` + cascade all users |
| User perm change (bitmap rebuild) | ~3–10 ms | `DEL` + parallel DB + pipelined `SETBIT` + `EXPIRE` |
| User perm change (lazy, no bitmap) | ~0 ms Redis | DB only, Redis deferred to login |
| User role change | ~2–15 ms | `DEL` bitmaps + sessions + lazy role build |
| User create (no role) | ~0 ms Redis | DB only |

### In-Place vs Full Invalidation

| Strategy | When Used | Redis Cost | Consistency |
|---|---|---|---|
| In-place SETBIT | Role perm add/remove (incremental) | O(N) per permission changed | Immediate on originating instance |
| Full invalidation (SCAN + DEL) | Role perm full replace | O(chunks) + cascade all users | Lazy rebuild on next login |
| Conditional rebuild | User perm change (bitmap exists) | O(chunks) delete + O(N) rebuild | Immediate |
| Lazy (skip) | User perm change (no bitmap) | 0 | Built on next login |

---

## Design Rationale

### Why Separate Bitmaps

A single pre-computed user bitmap cannot represent the three-state override model:

| State | Meaning |
|---|---|
| No override | Inherit role permission |
| Allow override | Grant permission beyond role |
| Deny override | Revoke permission from role |

Two states (0, 1) cannot encode three states — a separate `allow` and `deny` bitmap is the minimum correct representation.

### Approach Comparison

| Metric | Pre-computed (old) | Separate Bitmaps (current) |
|---|---|---|
| Memory (10 K users, 100 K perms) | ~125 MB | ~37.5 MB (**–70%**) |
| Redis keys | 10 K | ~2 500 (conditional creation) |
| Auth check Redis calls | 1 GETBIT | 3 GETBIT (~+0.02 ms with EVAL) |
| Role invalidation cost | Delete N user bitmaps | Delete 1 role bitmap |
| Login Lua script required | Yes (BITOP OR/NOT/AND) | No |
| Granular user invalidation | No (full recompute) | Yes (allow or deny independently) |

### Memory Breakdown (10 K users, 100 K permissions)

| User type | % | Count | Allow keys | Deny keys | Memory |
|---|---|---|---|---|---|
| No overrides | 75% | 7 500 | 0 | 0 | **0 MB** |
| Allow only | 15% | 1 500 | 1 500 | 0 | **18.75 MB** |
| Deny only | 5% | 500 | 0 | 500 | **6.25 MB** |
| Both | 5% | 500 | 500 | 500 | **12.5 MB** |
| **Total** | | **10 000** | **2 000** | **1 000** | **37.5 MB** |

75% of users generate zero user-level bitmap keys. `GETBIT` on a missing key returns `0` by Redis specification — no error handling required.

### Invalidation Cost

| Event | Keys deleted | Recomputation |
|---|---|---|
| Role permission changed | `role:{roleId}:perm:*` (1 key/chunk) | Lazy rebuild on next request |
| User allow list changed | `user:{uid}:allow:*` | Rebuild on next login |
| User deny list changed | `user:{uid}:deny:*` | Rebuild on next login |
| Force re-auth | `user:{uid}:allow:*` + `user:{uid}:deny:*` + sessions | Full re-login |

For a role with 1 000 users, a role permission update now invalidates **1 key** instead of 1 000.

### Permission Formula Truth Table

```
result = (role OR allow) AND (NOT deny)
```

| Role | Allow | Deny | Result |
|------|-------|------|--------|
| 0 | 0 | 0 | DENY |
| 0 | 0 | 1 | DENY |
| 0 | 1 | 0 | ALLOW |
| 0 | 1 | 1 | DENY |
| 1 | 0 | 0 | ALLOW |
| 1 | 0 | 1 | DENY |
| 1 | 1 | 0 | ALLOW |
| 1 | 1 | 1 | DENY |

**Deny always wins.**

---

## Scaling

### Horizontal

```
[APISIX Node 1] ──┐
[APISIX Node 2] ──┼──→ [Redis Cluster / Sentinel]
[APISIX Node N] ──┘
          │
          └──→ [Spring Boot ×N]  (login path only)
```

- **APISIX**: Stateless, scale behind any load balancer. LRU cache is per-worker — cold start adds one `HGET` per new permission key.
- **Spring Boot**: Multiple instances are safe. Distributed lock (`SET NX EX 30` on `lock:role_bitmap:{roleId}`) prevents thundering herd during concurrent first-logins for the same role.
- **Redis**: Single instance sufficient up to ~100 K concurrent sessions. Use Redis Cluster beyond that.

### Redis Cluster Sharding

Use hash tags to co-locate chunks for the same user on one shard:

```
{user:{uid}}:role:{roleId}:perm:{chunk}
{user:{uid}}:allow:{chunk}
{user:{uid}}:deny:{chunk}
```

All keys for a given user hash to the same slot, enabling atomic Lua EVAL across them.

### Capacity Planning

| Active sessions | Redis memory | Instances |
|---|---|---|
| 1 K | ~3 MB | 1 |
| 10 K | ~37 MB | 1 |
| 100 K | ~370 MB | 1–2 |
| 1 M | ~3.7 GB | 6+ (cluster) |

---

## Edge Cases

### Permission Update During Active Session

Role or user permissions change while a session is live. Handled via Pub/Sub on `auth:invalidation`:

- **Lazy** (default): Invalidate bitmap keys; stale JWTs remain valid until expiry but new permission checks fail once the bitmap is gone — GETBIT returns 0 → 403.
- **Eager** (force reauth): Call `invalidateAllSessions(userId)` which deletes bitmaps + session hash; next request returns 403 forcing re-login.

### Token Revocation Before Expiry

Delete `user:{uid}:allow:*`, `user:{uid}:deny:*`, and remove from `user:{uid}:sessions`. Subsequent GETBIT calls on missing role bitmap chunks return 0 → 403. No JWT blacklist is needed.

### Multi-Device Login

Each device receives a unique `sessionId` in its JWT. Session membership is tracked in `user:{uid}:sessions` (a Redis HASH mapping sessionId -> token). Revoking one session (via `HDEL`) does not affect others. Bitmaps are per-user, not per-session, so all devices for one user share the same allow/deny state.

> **Known bug:** `SessionPermissionService` writes sessions using `opsForHash().putIfAbsent()` (HSET)
> but validates/removes using `opsForSet().isMember()` / `opsForSet().remove()` (SET operations).
> These operations are incompatible. The code should consistently use HASH operations throughout.

### Unregistered API Path

`HGET perm:index "GET:/unknown"` returns `nil`. The APISIX plugin returns 403 (deny-by-default). Logged as a warning for admin review. Register the path via `POST /api/admin/permissions` or `POST /api/admin/permissions/sync`.

### Sparse Permission Sets

A role with 10 permissions out of 100 K total has only 10 bits set across its bitmap chunks. Redis allocates memory only for chunks that have been written. Chunks that have never been SET occupy 0 bytes; GETBIT returns 0.

---

## Operations

### Deployment

```bash
# 1. Build
./gradlew clean bootJar

# 2. Start dependencies
docker compose up -d postgres redis

# 3. Run (Flyway applies V1–V6 migrations automatically)
./gradlew bootRun

# 4. Start APISIX (standalone mode)
docker compose up -d apisix
```

### Redis Key Inspection

```bash
# Count bitmaps by type
redis-cli --scan --pattern "role:*:perm:*"  | wc -l
redis-cli --scan --pattern "user:*:allow:*" | wc -l
redis-cli --scan --pattern "user:*:deny:*"  | wc -l
redis-cli --scan --pattern "user:*:sessions"| wc -l

# Inspect a specific user (uid=1, roleId=2, bit=5)
redis-cli GETBIT "role:2:perm:0"   5   # role grant
redis-cli GETBIT "user:1:allow:0"  5   # user allow override
redis-cli GETBIT "user:1:deny:0"   5   # user deny override
redis-cli HGETALL "user:1:sessions"     # active sessions (HASH: sessionId → token)

# Check permission index
redis-cli HGET perm:index "GET:/api/orders/{id}"

# Memory summary
redis-cli INFO memory | grep used_memory_human
redis-cli --bigkeys
```

### Cache Invalidation (manual)

```bash
# Invalidate a role bitmap (triggers lazy rebuild on next request)
redis-cli --scan --pattern "role:2:perm:*" | xargs redis-cli DEL

# Invalidate a user's allow/deny (triggers rebuild on next login)
redis-cli --scan --pattern "user:1:allow:*" | xargs redis-cli DEL
redis-cli --scan --pattern "user:1:deny:*"  | xargs redis-cli DEL

# Force user re-auth (delete sessions + bitmaps)
redis-cli --scan --pattern "user:1:*" | xargs redis-cli DEL

# Re-sync permission index from DB (via admin API)
curl -X POST http://localhost:8080/api/admin/permissions/sync \
  -H "Authorization: Bearer <admin_token>"
```

### Monitoring

```bash
# Redis ops/sec
redis-cli INFO stats | grep instantaneous_ops_per_sec

# Redis latency histogram
redis-cli --latency-history

# APISIX error logs
docker logs apisix 2>&1 | grep "bitmap-auth"

# Spring Boot health
curl http://localhost:8080/actuator/health
```

---

## Troubleshooting

### 403 on a path that should be allowed

```bash
# 1. Verify JWT contains roleId
echo "<jwt_payload_base64>" | base64 -d | python3 -m json.tool

# 2. Check deny bit (should be 0)
redis-cli GETBIT "user:{uid}:deny:0" {offset}

# 3. Check role bit
redis-cli GETBIT "role:{roleId}:perm:0" {offset}

# 4. Check allow bit
redis-cli GETBIT "user:{uid}:allow:0" {offset}

# 5. Verify permission is registered
redis-cli HGET perm:index "{METHOD}:{normalized_path}"
```

### Unexpected memory growth

```bash
# Check if old single-session keys exist from a previous deployment
redis-cli --scan --pattern "user:*:session:*:perm:*" | wc -l
# If non-zero, clean up:
redis-cli --scan --pattern "user:*:session:*:perm:*" | xargs redis-cli DEL
```

### High auth latency

```bash
# 1. Check Redis latency
redis-cli --latency

# 2. Check APISIX LRU hit rate (add timing in plugin if needed)
docker logs apisix 2>&1 | grep "perm_cache"

# 3. Verify connection pool is not exhausted
redis-cli CLIENT LIST | wc -l   # compare against redis_pool_size (default 100)
```

### Role bitmap not rebuilding after invalidation

```bash
# Verify the distributed lock is not stuck (TTL should be < 30s)
redis-cli TTL "lock:role_bitmap:{roleId}"
# If stuck (returns -1), delete it:
redis-cli DEL "lock:role_bitmap:{roleId}"
```
