# Architectural Review — Redis-Based Authorization Service

> **Reviewer role**: Principal Software Architect (15+ years)
> **Codebase**: `redis-base-auth` — O(1) bitmap authorization via Redis + APISIX Lua plugin
> **Date**: 2026-03-25

---

## Executive Summary

The core idea is architecturally sound: pre-compute permissions at login time, encode them as a compact Redis bitmap, and perform sub-millisecond authorization at the gateway without waking the JVM. This is a legitimate and high-value pattern used in production at scale. The two-tier (role + user) bitmap design and Lua server-side computation are well-conceived. However, there are **critical correctness bugs**, **significant security gaps**, and several **scalability bottlenecks** that must be addressed before any production deployment.

---

## ✅ Strengths

| Area | What is done well |
|---|---|
| **Core Algorithm** | Permission formula [(role OR allow) AND (NOT deny)](file:///home/technonext/PSS/redis-base-auth/src/main/java/com/pss/auth/controller/AuthController.java#101-123) is correct and expressive |
| **Bitmap Chunking** | 1024-bit chunked strategy avoids Redis 512 MB string limit and enables efficient memory allocation |
| **Lua Atomicity** | Server-side Lua script for bitmap computation is atomic and avoids race conditions during multi-step BITOP |
| **Two-Tier Cache** | Role bitmap (permanent) + User bitmap (TTL-scoped) avoids recomputing shared role permissions per-user |
| **Session Tracking** | Redis SET per user enables efficient bulk revocation and multi-device support |
| **Gateway-First Auth** | APISIX plugin performs GETBIT without proxying to JVM — correct zero-latency authorization |
| **LRU Cache in Lua** | `resty.lrucache` for `perm:index` avoids HGET on every request |
| **Jedis Pipeline** | Pipelined SETBITs in [buildBitmap()](file:///home/technonext/PSS/redis-base-auth/src/main/java/com/pss/auth/service/BitmapService.java#81-97) is correctly used to reduce round-trips |
| **Virtual Threads** | Parallel allow/deny DB fetch using `Executors.newVirtualThreadPerTaskExecutor()` is idiomatic for I/O |
| **Clean Config** | [BitmapConfig](file:///home/technonext/PSS/redis-base-auth/src/main/java/com/pss/auth/config/BitmapConfig.java#5-11), `JwtConfig`, [RedisConfig](file:///home/technonext/PSS/redis-base-auth/src/main/java/com/pss/auth/config/RedisConfig.java#13-61) as typed records are clean and maintainable |

---

## 🔴 Critical Issues (Must Fix Before Production)

### CRIT-1: Hardcoded Password in [AuthService](file:///home/technonext/PSS/redis-base-auth/src/main/java/com/pss/auth/service/AuthService.java#24-87)

```java
// AuthService.java:46
if (!"password123".equals(request.password())) {
    throw new RuntimeException("Invalid credentials");
}
```

**Problem**: Password validation is completely bypassed — anyone with a valid username can log in. There is no BCrypt or hash comparison against `password_hash`.

**Fix**: Inject `PasswordEncoder` and compare against `user.getPasswordHash()`.

```java
if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
    throw new RuntimeException("Invalid credentials");
}
```

---

### CRIT-2: Key Mismatch Between Java and Lua Plugin

The Java service writes user bitmaps to:
```
user:{uid}:perm:{chunk}          ← Java (SessionPermissionService.java:46)
```

But the APISIX Lua plugin reads from:
```
user:{uid}:session:{sid}:perm:{chunk}   ← Lua plugin (bitmap-auth.lua:239)
```

**Problem**: The Lua plugin will **always get a GETBIT result of 0** (key doesn't exist), causing every authorization check to return **403 Forbidden** in production. This is the single most severe correctness bug in the system.

**Fix**: Standardize on one key schema. The session-scoped key (`user:{uid}:session:{sid}:perm:{chunk}`) is architecturally superior for multi-device support but requires the Java side to build per-session bitmaps. The current Java implementation uses a user-level key for bitmap sharing efficiency, which is also a sound approach. Decide on one and align both sides.

---

### CRIT-3: Session Validation Commented Out

```java
// AuthController.java:77-82
// // Verify session is still tracked (not invalidated)
// if (!sessionPermissionService.isSessionValid(userId, sessionId)) { ... }
```

**Problem**: The session revocation check on the `/api/auth/check` endpoint is disabled. A JWT whose session was invalidated (logout, force-reauth) will still pass the permission check endpoint. The gateway GETBIT direction also has no session validity gate beyond bitmap key existence.

**Fix**: Re-enable the session validation guard. If it was disabled for a reason, document it explicitly.

---

### CRIT-4: `perm:index` Sync is Non-Atomic (Race Condition on Startup)

```java
// PermissionIndexService.java:56-57
stringRedisTemplate.delete(PERM_INDEX_KEY);
stringRedisTemplate.opsForHash().putAll(PERM_INDEX_KEY, indexMap);
```

**Problem**: Between `DELETE` and `putAll`, any concurrent APISIX request calling `HGET perm:index` will get `nil` and return **403** for every valid path. Under rolling restart or parallel deployments this becomes a recurring outage.

**Fix**: Use Lua or a Redis pipeline with a temporary key + `RENAME` (atomic swap):

```lua
-- Atomic replace pattern
redis.call('HMSET', 'perm:index:tmp', ...)
redis.call('RENAME', 'perm:index:tmp', 'perm:index')
```

---

### CRIT-5: [getPermissionCount()](file:///home/technonext/PSS/redis-base-auth/src/main/java/com/pss/auth/service/PermissionIndexService.java#94-101) Bug — Always Returns 0

```java
// PermissionIndexService.java:99
return size == null ? size : 0;  // ← logic is inverted
```

**Problem**: When `size != null` (the normal path), it returns `0`. Should be:

```java
return size != null ? size : 0;
```

---

## 🟠 Security Issues

### SEC-1: JWT Secret Stored in Plugin Config as Plaintext

The `jwt_secret` is passed as a plain string in [apisix/config.yaml](file:///home/technonext/PSS/redis-base-auth/apisix/config.yaml). APISIX stores plugin config in etcd — this secret is visible to anyone with etcd or admin API access.

**Fix**: Use APISIX's secret manager (Vault/AWS SSM integration), or store the secret in an environment variable and reference it via `$ENV::JWT_SECRET` syntax.

---

### SEC-2: No Rate Limiting on Login Endpoint

The login endpoint performs 3 DB queries + multiple Redis writes. There's no brute-force protection.

**Fix**: Apply APISIX's `limit-count` or `limit-req` plugin on the login route.

---

### SEC-3: `bypass_paths` Prefix Match is Overly Permissive

```lua
-- bitmap-auth.lua:144
if path == bp or path:find(bp, 1, true) == 1 then
```

**Problem**: If a bypass path is `/api/auth`, then `/api/auth-admin/nuke` would also bypass authorization.

**Fix**: Use exact match or explicit trailing-slash anchoring:

```lua
if path == bp or path:sub(1, #bp + 1) == bp .. "/" then
```

---

### SEC-4: Missing `PasswordEncoder` Bean

There is no `PasswordEncoder` bean declared in any `@Configuration` class. Even if CRIT-1 is fixed, BCrypt comparison requires a registered bean.

---

### SEC-5: Error Response Leaks Sensitive Internal State

```java
// AuthController.java:128
throw new RuntimeException("Missing or invalid Authorization header");
```

Unhandled `RuntimeException`s will produce Spring Boot's default error response including stack traces in non-prod profiles. The `GlobalExceptionHandler` should be confirmed to catch all auth-related exceptions.

---

## 🟡 Architectural & Design Concerns

### ARCH-1: Bitmap Key Schema Must Be Documented as a Contract

The key format `user:{uid}:perm:{chunk}` is the single most critical interface between the Java service and the Lua plugin. It is currently only implied by code. It must be the first entry in [redis-key-design.md](file:///home/technonext/PSS/redis-base-auth/docs/redis-key-design.md) and version-controlled as a formal contract.

---

### ARCH-2: [ensureRoleBitmap](file:///home/technonext/PSS/redis-base-auth/src/main/java/com/pss/auth/service/SessionPermissionService.java#94-106) Race Condition (Check-Then-Act)

```java
// SessionPermissionService.java:100-104
if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(checkKey))) { return; }
buildRoleBitmap(prefix, roleId);
```

**Problem**: Two concurrent login requests for the same role will both miss the `hasKey` check simultaneously and both attempt to build the role bitmap. The result is redundant DB queries and a SETBIT collision (harmless but wasteful). Under high load this pattern creates a **thundering herd** on the first login per role.

**Fix**: Use Redis `SET NX` (set if not exists) as a distributed lock or a Lua check-and-set to make the check atomic.

---

### ARCH-3: [setTtl](file:///home/technonext/PSS/redis-base-auth/src/main/java/com/pss/auth/service/BitmapService.java#98-107) in [BitmapService](file:///home/technonext/PSS/redis-base-auth/src/main/java/com/pss/auth/service/BitmapService.java#30-131) Uses N Individual `EXPIRE` Commands

```java
// BitmapService.java:103-105
IntStream.rangeClosed(0, maxChunk)
    .mapToObj(...)
    .forEach(key -> redisTemplate.expire(key, ttlSeconds, TimeUnit.SECONDS));
```

For 100 chunks, this is 100 separate round-trips. This should be pipelined.

---

### ARCH-4: [extendUserBitmapTtl](file:///home/technonext/PSS/redis-base-auth/src/main/java/com/pss/auth/service/SessionPermissionService.java#227-240) Calls `findMaxBitIndex()` on Every Login

Every login for an existing user triggers a DB query just to determine the number of chunks. This data is stable and should be cached (e.g., in an in-process `AtomicInteger`) and refreshed only on permission schema changes.

---

### ARCH-5: [invalidateRoleBitmap](file:///home/technonext/PSS/redis-base-auth/src/main/java/com/pss/auth/service/SessionPermissionService.java#119-138) Calls `findMaxBitIndex()` Which May Return Stale Data

The [deleteAll](file:///home/technonext/PSS/redis-base-auth/src/main/java/com/pss/auth/service/BitmapService.java#108-116) loop iterates from 0 to `totalChunks`. If new permissions were added since the role bitmap was last built, `totalChunks` could undercount and leave stale chunks that never get deleted.

**Fix**: Use Redis `SCAN` with pattern `role:{roleId}:perm:*` for deletion, or store the chunk count in a Redis key at build time.

---

### ARCH-6: No Pub/Sub Subscriber — Invalidation is One-Sided

[CacheInvalidationService](file:///home/technonext/PSS/redis-base-auth/src/main/java/com/pss/auth/service/CacheInvalidationService.java#19-112) publishes to `auth:invalidation` channel but there is no `@RedisListener` / `MessageListenerAdapter` registered to consume these events. In a multi-instance deployment this means no instance actually acts on the broadcast.

**Fix**: Implement a `RedisMessageListenerContainer` + listener that handles `ROLE_UPDATED`, `USER_UPDATED`, and `FORCE_REAUTH` messages to trigger local bitmap rebuild/delete.

---

### ARCH-7: [deleteAll](file:///home/technonext/PSS/redis-base-auth/src/main/java/com/pss/auth/service/BitmapService.java#108-116) Uses `N` Individual `DELETE` Commands

```java
// BitmapService.java:113-114
.forEach(redisTemplate::delete);
```

`redisTemplate.delete(Collection<K>)` sends a single `DEL key1 key2 ...` command. Using `forEach` sends `N` individual `DEL` commands. Pipeline or use the collection-based overload.

---

### ARCH-8: [computeUserBitmap](file:///home/technonext/PSS/redis-base-auth/src/main/java/com/pss/auth/service/SessionPermissionService.java#161-226) Leaks Temp Keys on Exception

If the Lua script invocation at line 220 fails, `tempAllow` and `tempDeny` keys remain in Redis with no TTL. Over time this causes unbounded memory growth.

**Fix**: Set a short TTL on temp keys before the Lua call as a safety net:

```java
stringRedisTemplate.expire(tempAllow, 60, TimeUnit.SECONDS);
stringRedisTemplate.expire(tempDeny, 60, TimeUnit.SECONDS);
```

---

### ARCH-9: Redis Cluster Compatibility - BITOP Constraint

The Lua script uses `BITOP` across multiple keys (role, allow, deny, result). In Redis Cluster mode, keys must be in the same slot. The documented hash tag strategy `{uid}:{sid}` only partially addresses this — `role:{roleId}:perm:*` keys will hash differently from `user:{uid}:perm:*` keys unless hash tags are carefully designed.

**Fix**: Add hash tags to all related keys so cluster routing is predictable:

```
{role:{roleId}}:perm:{chunk}
{user:{uid}}:perm:{chunk}
```

For cross-key Lua scripts in Cluster, all KEYS[] must hash to the same slot — this requires that role and user keys share a hash tag, which may require a design revisit (e.g., all temp keys use `{uid}` as the tag anchor).

---

## 🔵 Code Quality & Best Practices

### CQ-1: [build.gradle](file:///home/technonext/PSS/redis-base-auth/build.gradle) vs README Version Mismatch

- [README.md](file:///home/technonext/PSS/redis-base-auth/README.md) says Java 21/23, Spring Boot 3+
- [build.gradle](file:///home/technonext/PSS/redis-base-auth/build.gradle) uses Java 25, Spring Boot 4.0.4

Update the README to reflect the actual versions.

---

### CQ-2: [RedisProperties](file:///home/technonext/PSS/redis-base-auth/src/main/java/com/pss/auth/config/RedisConfig.java#16-23) Compact Constructor Mutation Pattern

```java
// RedisConfig.java:19-20
public RedisProperties {
    if (host == null) host = "localhost";
```

Records are supposed to be immutable value types. Mutating fields in the compact constructor is allowed but unconventional. Prefer `@Value` defaults or `@ConfigurationProperties` default field values.

---

### CQ-3: No Connection Pool Configuration on Jedis

`JedisConnectionFactory` is constructed with no pool config. By default this uses a single connection. For production, this should use `JedisPoolConfig` with explicitly tuned `maxTotal`, `maxIdle`, `minIdle`, and `testOnBorrow`.

---

### CQ-4: `RuntimeException` Used Throughout for Business Errors

[AuthService](file:///home/technonext/PSS/redis-base-auth/src/main/java/com/pss/auth/service/AuthService.java#24-87) throws bare `RuntimeException` for invalid credentials and disabled accounts. These should be dedicated exception classes (`InvalidCredentialsException`, `AccountDisabledException`) handled by `GlobalExceptionHandler` to return proper HTTP status codes (401 vs 403).

---

### CQ-5: No Tests

There are no unit or integration tests in the codebase (`/src/test` not found). For a security-critical service, this is a significant gap.

**Key test coverage needed:**
- [BitmapService](file:///home/technonext/PSS/redis-base-auth/src/main/java/com/pss/auth/service/BitmapService.java#30-131): chunk/offset math boundary cases
- [SessionPermissionService](file:///home/technonext/PSS/redis-base-auth/src/main/java/com/pss/auth/service/SessionPermissionService.java#40-318): bitmap lifecycle (build, extend TTL, invalidate)
- [AuthService](file:///home/technonext/PSS/redis-base-auth/src/main/java/com/pss/auth/service/AuthService.java#24-87): login happy path + failure modes
- Lua script: [compute_user_bitmap.lua](file:///home/technonext/PSS/redis-base-auth/src/main/resources/scripts/compute_user_bitmap.lua) correctness with Embedded Redis

---

### CQ-6: `perm_cache` in Lua Plugin is Not Shared Across Workers

`lrucache.new(4096)` creates a worker-local cache. In APISIX's multi-worker model each Nginx worker maintains its own `perm_cache`. Cache warm-up must happen per-worker, and invalidation after `PERM_INDEX_REFRESHED` does not clear these worker-local caches.

**Fix**: Use `ngx.shared.DICT` (a shared memory dictionary) for the perm cache, declared in `nginx.conf`:

```nginx
lua_shared_dict perm_cache 10m;
```

---

### CQ-7: Missing OpenAPI Annotations on Controllers

Endpoints are exposed via Swagger but lack `@Operation`, `@ApiResponse`, and `@Parameter` annotations. In a microservice consumed by multiple teams, this makes the contract undiscoverable.

---

## 📋 Prioritized Improvement Plan

### Phase 1 — Immediate (Blockers for Correctness & Security)

| ID | Action | Effort |
|---|---|---|
| CRIT-1 | Replace hardcoded password with `BCryptPasswordEncoder` comparison | S |
| CRIT-2 | **Fix key schema mismatch between Java and Lua plugin** | S |
| CRIT-3 | Re-enable session validity check in `/api/auth/check` | S |
| CRIT-4 | Make `perm:index` sync atomic via Lua RENAME | S |
| CRIT-5 | Fix [getPermissionCount()](file:///home/technonext/PSS/redis-base-auth/src/main/java/com/pss/auth/service/PermissionIndexService.java#94-101) ternary inversion bug | XS |
| SEC-1 | Move JWT secret to environment variable / secrets manager | S |
| SEC-3 | Fix `bypass_paths` prefix-match to be exact-path or `/`-anchored | S |
| SEC-4 | Register `PasswordEncoder` bean in [SecurityConfig](file:///home/technonext/PSS/redis-base-auth/src/main/java/com/pss/auth/config/SecurityConfig.java#11-28) | XS |

### Phase 2 — Short-Term (Stability & Resilience)

| ID | Action | Effort |
|---|---|---|
| ARCH-2 | Make [ensureRoleBitmap](file:///home/technonext/PSS/redis-base-auth/src/main/java/com/pss/auth/service/SessionPermissionService.java#94-106) atomic with Redis `SET NX` lock | M |
| ARCH-3 | Pipeline `EXPIRE` calls in [setTtl](file:///home/technonext/PSS/redis-base-auth/src/main/java/com/pss/auth/service/BitmapService.java#98-107) | S |
| ARCH-6 | Implement `RedisMessageListenerContainer` Pub/Sub subscriber | M |
| ARCH-7 | Use `redisTemplate.delete(Collection)` instead of `forEach` | XS |
| ARCH-8 | Add safety TTL on temp bitmap keys before Lua invocation | S |
| CQ-4 | Replace bare `RuntimeException` with typed exceptions + handler | S |
| CQ-3 | Configure `JedisPoolConfig` with production pool settings | S |
| SEC-2 | Add rate limiting on `/api/auth/login` via APISIX plugin | S |

### Phase 3 — Medium-Term (Scalability & Production Hardening)

| ID | Action | Effort |
|---|---|---|
| ARCH-4 | Cache `maxBitIndex` in-process; invalidate on permission schema change | M |
| ARCH-5 | Replace [deleteAll](file:///home/technonext/PSS/redis-base-auth/src/main/java/com/pss/auth/service/BitmapService.java#108-116) with SCAN-based key deletion | M |
| ARCH-9 | Design Redis Cluster hash tag strategy for cross-key Lua BITOP | L |
| CQ-6 | Migrate Lua `lrucache` to `ngx.shared.DICT` for cross-worker sharing | M |
| CQ-5 | Add unit + integration test suite | L |
| CQ-1 | Update README to reflect actual Java 25 / Spring Boot 4 versions | XS |
| CQ-7 | Add OpenAPI annotations to all controllers | M |

---

## Architecture Decision Records Needed

The following decisions lack formal documentation and should be captured as ADRs:

1. **Why Jedis over Lettuce** — Jedis is synchronous (suitable for Virtual Threads); Lettuce is reactive. The choice is correct for this threading model but should be documented.
2. **User-level vs Session-level bitmap** — Current Java uses `user:{uid}:perm:{chunk}` (shared per user) while the Lua plugin expects `user:{uid}:session:{sid}:perm:{chunk}` (per session). The tradeoff is memory efficiency (user-level) vs independent revocation (session-level). Decide and document.
3. **Deny semantics** — [(role OR allow) AND (NOT deny)](file:///home/technonext/PSS/redis-base-auth/src/main/java/com/pss/auth/controller/AuthController.java#101-123) means deny always wins. This is intentional but should be documented as a security axiom.
4. **LRU vs shared dict for perm cache** — Decision for cross-worker cache invalidation.
