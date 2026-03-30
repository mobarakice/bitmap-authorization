# Implementation Plan: Separate Allow/Deny Bitmap Architecture

## Overview
Refactor from pre-computed user bitmaps to separate role/allow/deny bitmaps for granular invalidation.

## Architecture Changes

### Before (Current - Pre-computed)
```
Redis Keys:
- role:{roleId}:perm:{chunk}     → PERMANENT
- user:{uid}:perm:{chunk}         → TTL (pre-computed: role OR allow AND NOT deny)

On Login:
1. Build role bitmap (if missing)
2. Fetch allow/deny from DB
3. Compute user bitmap via Lua: (role OR allow) AND (NOT deny)
4. Store result with TTL

On Permission Check (APISIX):
- GETBIT user:{uid}:perm:{chunk} {offset}

On Role Change:
- Invalidate role bitmap
- Invalidate ALL user bitmaps for that role (expensive)

On User Allow/Deny Change:
- Invalidate user bitmap
- Recompute entire bitmap on next login
```

### After (New - Separate Bitmaps)
```
Redis Keys:
- role:{roleId}:perm:{chunk}     → PERMANENT
- user:{uid}:allow:{chunk}        → TTL (user-specific allow list)
- user:{uid}:deny:{chunk}         → TTL (user-specific deny list)

On Login:
1. Build role bitmap (if missing)
2. Build user allow/deny bitmaps from DB (if missing)
3. Extend TTL on allow/deny bitmaps

On Permission Check (APISIX):
- denyBit = GETBIT user:{uid}:deny:{chunk} {offset}
- if denyBit == 1: DENY
- roleBit = GETBIT role:{roleId}:perm:{chunk} {offset}
- allowBit = GETBIT user:{uid}:allow:{chunk} {offset}
- if roleBit == 1 OR allowBit == 1: ALLOW
- else: DENY

On Role Change:
- Invalidate role bitmap only
- User allow/deny bitmaps remain intact
- Next request uses new role bitmap automatically

On User Allow Change:
- Invalidate user:{uid}:allow:{chunk} only
- Role and deny bitmaps remain intact

On User Deny Change:
- Invalidate user:{uid}:deny:{chunk} only
- Role and allow bitmaps remain intact
```

## Implementation Steps

### ✅ Phase 1: Update SessionPermissionService
- [x] Change key prefixes: `USER_PERM_PREFIX` → `USER_ALLOW_PREFIX` + `USER_DENY_PREFIX`
- [x] Refactor `ensureUserBitmap()` to build allow/deny separately
- [x] Remove Lua script invocation (no longer needed for login)
- [x] Update `invalidateUserBitmap()` to delete allow/deny keys
- [x] Update `checkPermission()` to compute on-the-fly: `(role OR allow) AND (NOT deny)`

### ✅ Phase 2: Update JwtService
- [x] Add `roleId` to JWT claims
- [x] Update `generateToken()` signature
- [x] Add `extractRoleId()` method

### ✅ Phase 3: Update AuthService
- [x] Pass `roleId` to `jwtService.generateToken()`

### ✅ Phase 4: Update APISIX Plugin
- [x] Extract `roleId` from JWT
- [x] Build three keys: `role:{roleId}:perm:{chunk}`, `user:{uid}:allow:{chunk}`, `user:{uid}:deny:{chunk}`
- [x] Implement three GETBIT calls: deny → role → allow
- [x] Apply formula: `(role OR allow) AND (NOT deny)`

### ⏳ Phase 5: Testing (See Testing Plan below)

### ⏳ Phase 6: Update Documentation
- [ ] Update `redis-key-design.md` with new key schema
- [ ] Update README.md with new architecture
- [ ] Add migration guide for existing deployments

## Pros and Cons

### ✅ Pros

1. **Granular Invalidation**
   - Role change: Only invalidate role bitmap (1 key pattern)
   - User allow change: Only invalidate user allow bitmap (1 key pattern)
   - User deny change: Only invalidate user deny bitmap (1 key pattern)
   - No need to recompute entire user bitmap

2. **Reduced Computation Cost**
   - No Lua script execution on login
   - No temporary key creation/cleanup
   - Simpler login flow

3. **Better Cache Efficiency**
   - Role bitmaps shared across all users with that role
   - Allow/deny bitmaps only change when user-specific permissions change
   - Higher cache hit rate

4. **Scalability**
   - Role bitmap computed once, used by millions of users
   - User-specific bitmaps only for users with overrides
   - Reduced Redis memory for users without overrides

5. **Atomic Updates**
   - Each bitmap type updated independently
   - No race conditions during invalidation

### ❌ Cons

1. **Increased Latency per Request**
   - Before: 1 GETBIT call
   - After: 3 GETBIT calls (deny, role, allow)
   - ~3x Redis round-trips per authorization check
   - Mitigation: Use Redis pipelining or Lua script in APISIX

2. **More Complex APISIX Plugin**
   - Logic moved from Java to Lua
   - Harder to debug/maintain
   - Need to ensure roleId is in JWT

3. **Slightly Higher Redis Memory**
   - Before: 1 bitmap per user (12.5 KB for 100K permissions)
   - After: 1 role bitmap + 2 user bitmaps (allow/deny)
   - For users with no overrides: allow/deny bitmaps are empty but still exist
   - Mitigation: Don't create empty bitmaps

4. **JWT Size Increase**
   - Adding roleId to JWT increases token size by ~10 bytes
   - Negligible impact

5. **Migration Complexity**
   - Existing deployments need to:
     - Update APISIX plugin
     - Invalidate all existing user bitmaps
     - Force re-login for all users

## Performance Analysis

### Latency Impact (per request)

| Scenario | Before | After | Delta |
|----------|--------|-------|-------|
| Single GETBIT | ~0.1ms | - | - |
| Three GETBIT (sequential) | - | ~0.3ms | +0.2ms |
| Three GETBIT (pipelined) | - | ~0.15ms | +0.05ms |
| Lua EVAL (3 GETBIT) | - | ~0.12ms | +0.02ms |

**Recommendation**: Use Lua EVAL in APISIX for minimal latency impact.

### Memory Impact (100K permissions, 10K users)

| Component | Before | After | Delta |
|-----------|--------|-------|-------|
| Role bitmaps (10 roles) | 125 KB | 125 KB | 0 |
| User bitmaps | 125 MB | - | -125 MB |
| User allow bitmaps (20% have overrides) | - | 25 MB | +25 MB |
| User deny bitmaps (5% have overrides) | - | 6.25 MB | +6.25 MB |
| **Total** | **125.125 MB** | **31.375 MB** | **-93.75 MB (75% reduction)** |

### Invalidation Cost

| Event | Before | After | Improvement |
|-------|--------|-------|-------------|
| Role permission change | Delete 1 role + N user bitmaps | Delete 1 role bitmap | N× faster |
| User allow change | Delete 1 user bitmap | Delete 1 allow bitmap | Same |
| User deny change | Delete 1 user bitmap | Delete 1 deny bitmap | Same |

## Migration Strategy

### For Existing Deployments

1. **Deploy Code Changes**
   - Update Spring Boot service
   - Update APISIX plugin
   - Deploy simultaneously (zero-downtime)

2. **Invalidate Old Keys**
   ```bash
   redis-cli --scan --pattern "user:*:perm:*" | xargs redis-cli DEL
   ```

3. **Force Re-login**
   - Option A: Invalidate all sessions (force immediate re-login)
   - Option B: Let sessions expire naturally (gradual migration)

4. **Monitor**
   - Track APISIX latency (should increase by ~0.1-0.2ms)
   - Monitor Redis memory (should decrease)
   - Check error rates

## Rollback Plan

If issues arise:

1. **Revert Code**
   - Deploy previous version of Spring Boot service
   - Deploy previous version of APISIX plugin

2. **Clean Up New Keys**
   ```bash
   redis-cli --scan --pattern "user:*:allow:*" | xargs redis-cli DEL
   redis-cli --scan --pattern "user:*:deny:*" | xargs redis-cli DEL
   ```

3. **Force Re-login**
   - Invalidate all sessions to rebuild old-style bitmaps
