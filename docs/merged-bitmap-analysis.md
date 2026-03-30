# Optimized Solution: Merged Allow/Deny Bitmap

## Problem with Separate Bitmaps

```
user:{uid}:allow:{chunk}  → 128 bytes per chunk
user:{uid}:deny:{chunk}   → 128 bytes per chunk
Total: 256 bytes per chunk (2× memory)
```

For 10K users with overrides: **50 MB** (25 MB allow + 25 MB deny)

---

## Solution Options

### Option 1: Single Bitmap + Metadata Hash (RECOMMENDED)

Store user overrides in a single bitmap, track allow/deny in a separate hash.

```
Redis Keys:
role:{roleId}:perm:{chunk}           → PERMANENT (role permissions)
user:{uid}:override:{chunk}          → TTL (merged allow + deny bits)
user:{uid}:override:meta             → TTL (HASH: bit_index → "allow" or "deny")
```

**Permission Check Logic**:
```
1. Check if bit is in user override bitmap
2. If yes, check metadata: "allow" → ALLOW, "deny" → DENY
3. If no, check role bitmap
```

**Pros**:
- ✅ Single bitmap (50% memory vs separate)
- ✅ Clear semantics (metadata explicitly states intent)
- ✅ Easy to debug (can inspect metadata hash)

**Cons**:
- ❌ Extra Redis call to fetch metadata
- ❌ Metadata hash adds ~50 bytes per override

---

### Option 2: Two Bitmaps with Precedence (SIMPLEST)

Use two bitmaps but with clear precedence: deny always checked first.

```
Redis Keys:
role:{roleId}:perm:{chunk}           → PERMANENT
user:{uid}:deny:{chunk}              → TTL (deny list only)
user:{uid}:allow:{chunk}             → TTL (allow list only)
```

**Permission Check Logic**:
```lua
-- Check deny first (fast rejection)
if GETBIT(user:deny, offset) == 1 then
    return DENY
end

-- Check role or allow
if GETBIT(role, offset) == 1 OR GETBIT(user:allow, offset) == 1 then
    return ALLOW
end

return DENY
```

**Optimization**: Only create bitmaps if user has overrides!

```java
// Only build allow bitmap if user has allow permissions
if (!allowBitIndexes.isEmpty()) {
    bitmapService.buildBitmap(allowPrefix, allowBitIndexes);
}

// Only build deny bitmap if user has deny permissions
if (!denyBitIndexes.isEmpty()) {
    bitmapService.buildBitmap(denyPrefix, denyBitIndexes);
}
```

**Memory Calculation**:
- 20% of users have allow overrides: 25 MB
- 5% of users have deny overrides: 6.25 MB
- **Total: 31.25 MB** (vs 50 MB if all users have both)

**Pros**:
- ✅ Simple logic (already implemented!)
- ✅ Only creates bitmaps when needed
- ✅ Deny-first optimization (fast rejection)
- ✅ No metadata overhead

**Cons**:
- ❌ Still 2 bitmaps per user (but only if they have overrides)

---

### Option 3: Single Bitmap with Bit Pairs (COMPLEX)

Use 2 bits per permission: `[allow_bit, deny_bit]`

```
Bit Layout:
Permission 0: bits 0-1   → [allow, deny]
Permission 1: bits 2-3   → [allow, deny]
Permission 2: bits 4-5   → [allow, deny]
```

**Encoding**:
- `00` = no override (use role)
- `01` = deny override
- `10` = allow override
- `11` = invalid (treat as deny)

**Pros**:
- ✅ Single bitmap (50% memory)
- ✅ No metadata needed

**Cons**:
- ❌ Complex bit manipulation
- ❌ Doubles bit index calculations
- ❌ Harder to debug
- ❌ Wastes space if user has only allow or only deny

---

### Option 4: Single Bitmap with XOR (CLEVER BUT RISKY)

Store `user_override = allow XOR deny` and track which type in metadata.

**Pros**:
- ✅ Single bitmap

**Cons**:
- ❌ Confusing semantics
- ❌ Still needs metadata
- ❌ Hard to debug

---

## RECOMMENDED: Option 2 with Optimization

Keep the current implementation (separate allow/deny) but **only create bitmaps when needed**.

### Optimized Implementation

```java
private void buildUserAllowDenyBitmaps(Long userId, long ttlSeconds) {
    log.info("Building user allow/deny bitmaps for userId={}", userId);

    List<Integer> allowBitIndexes;
    List<Integer> denyBitIndexes;

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
        var f1 = executor.submit(() -> permissionRepository.findAllowBitIndexesByUserId(userId));
        var f2 = executor.submit(() -> permissionRepository.findDenyBitIndexesByUserId(userId));
        allowBitIndexes = f1.get();
        denyBitIndexes = f2.get();
    } catch (Exception e) {
        log.error("Failed to fetch user permission overrides", e);
        throw new RuntimeException("Failed to fetch user permission overrides", e);
    }

    var allowPrefix = String.format(USER_ALLOW_PREFIX, userId);
    var denyPrefix = String.format(USER_DENY_PREFIX, userId);
    var maxBitIndex = getMaxBitIndex();

    // OPTIMIZATION: Only build bitmaps if user has overrides
    if (!allowBitIndexes.isEmpty()) {
        bitmapService.buildBitmap(allowPrefix, allowBitIndexes);
        if (maxBitIndex > 0) {
            bitmapService.setTtl(allowPrefix, maxBitIndex, ttlSeconds);
        }
        log.info("Built allow bitmap: userId={}, count={}", userId, allowBitIndexes.size());
    }

    if (!denyBitIndexes.isEmpty()) {
        bitmapService.buildBitmap(denyPrefix, denyBitIndexes);
        if (maxBitIndex > 0) {
            bitmapService.setTtl(denyPrefix, maxBitIndex, ttlSeconds);
        }
        log.info("Built deny bitmap: userId={}, count={}", userId, denyBitIndexes.size());
    }

    // If user has no overrides, log it
    if (allowBitIndexes.isEmpty() && denyBitIndexes.isEmpty()) {
        log.info("User has no permission overrides: userId={}", userId);
    }
}
```

### Memory Savings

**Scenario**: 10,000 users, 100,000 permissions

| User Type | Count | Allow Bitmap | Deny Bitmap | Total |
|-----------|-------|--------------|-------------|-------|
| No overrides | 7,500 (75%) | 0 | 0 | 0 MB |
| Allow only | 1,500 (15%) | 18.75 MB | 0 | 18.75 MB |
| Deny only | 500 (5%) | 0 | 6.25 MB | 6.25 MB |
| Both | 500 (5%) | 6.25 MB | 6.25 MB | 12.5 MB |
| **Total** | **10,000** | **25 MB** | **12.5 MB** | **37.5 MB** |

**vs. Creating bitmaps for all users**: 125 MB (allow) + 125 MB (deny) = 250 MB

**Savings**: 85% reduction!

---

## Alternative: If You MUST Merge into Single Bitmap

If you absolutely need a single bitmap, use **Option 1 (Bitmap + Metadata Hash)**:

### Implementation

```java
// Store all overrides in single bitmap
user:{uid}:override:{chunk}

// Store type in hash
user:{uid}:override:meta → HASH
  "10" → "allow"
  "15" → "deny"
  "20" → "allow"
```

### Permission Check (APISIX)

```lua
-- 1. Check if user has override for this permission
local overrideKey = "user:" .. uid .. ":override:" .. chunk
local hasBit = red:getbit(overrideKey, offset)

if hasBit == 1 then
    -- 2. Check metadata to determine allow/deny
    local metaKey = "user:" .. uid .. ":override:meta"
    local permType = red:hget(metaKey, tostring(perm_id))
    
    if permType == "deny" then
        return 403  -- DENY
    elseif permType == "allow" then
        return 200  -- ALLOW
    end
end

-- 3. Fall back to role bitmap
local roleKey = "role:" .. roleId .. ":perm:" .. chunk
local roleBit = red:getbit(roleKey, offset)
return roleBit == 1 and 200 or 403
```

### Memory Calculation

**Bitmap**: 12.5 MB (only users with overrides)  
**Metadata Hash**: ~50 bytes × 2,000 users × 5 overrides = 0.5 MB  
**Total**: ~13 MB

**vs. Separate bitmaps (optimized)**: 37.5 MB

**Savings**: 65% reduction

---

## Final Recommendation

### Best Approach: **Option 2 (Separate Bitmaps) + Optimization**

**Why**:
1. ✅ **Simplest implementation** (already done!)
2. ✅ **85% memory savings** vs naive approach (only create when needed)
3. ✅ **Fastest permission check** (no metadata lookup)
4. ✅ **Easy to debug** (clear semantics)
5. ✅ **Deny-first optimization** (fast rejection)

### When to Use Option 1 (Merged Bitmap + Metadata):
- If >50% of users have BOTH allow AND deny overrides
- If you need to minimize Redis key count
- If metadata lookup latency is acceptable (+0.1ms)

---

## Implementation: Optimized Separate Bitmaps

I'll update the code to only create bitmaps when needed.
