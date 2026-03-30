# Why Single User Bitmap Doesn't Work for Allow+Deny

## The Fundamental Problem

With a single bitmap, each bit can only be in two states: `0` or `1`.

But we need to represent **THREE states** for each permission:
1. **No override** - use role permission
2. **Allow override** - grant permission (even if role doesn't have it)
3. **Deny override** - block permission (even if role has it)

**2 states < 3 states** → Single bitmap is insufficient!

---

## Example Showing the Problem

### User Overrides
- Allow list: `[5]` (grant permission 5)
- Deny list: `[2]` (block permission 2)
- No override for permissions: `[0, 1, 3, 4, 6, 7, ...]`

### Attempt 1: bit=1 for allow, bit=0 for deny
```
user bitmap: 0 0 0 0 0 1 0 0 ...
             ↑ ↑       ↑
             ? ?       allow

Problem: Can't distinguish between:
- bit=0 at position 2 (explicit deny)
- bit=0 at position 0 (no override, use role)
```

### Attempt 2: bit=1 for allow, bit=0 for no-override, ??? for deny
```
We only have 2 states (0 and 1), but need 3 states!
```

---

## Solutions

### Solution 1: Two Bitmaps (RECOMMENDED)

```
user:{uid}:allow:{chunk}  → bit=1 means explicit allow
user:{uid}:deny:{chunk}   → bit=1 means explicit deny

Permission check:
if deny_bit == 1:
    return DENY
if role_bit == 1 OR allow_bit == 1:
    return ALLOW
return DENY
```

**Pros**:
- ✅ Clear semantics
- ✅ Fast (2-3 GETBIT calls)
- ✅ Only creates bitmaps if user has overrides

**Cons**:
- ❌ Two bitmaps per user (but only if they have overrides)

**Memory**: 37.5 MB for 10K users (20% have allows, 5% have denies)

---

### Solution 2: Single Bitmap + Metadata Hash

```
user:{uid}:override:{chunk}  → bit=1 means there's an override
user:{uid}:override:meta     → HASH: bit_index → "allow" or "deny"

Permission check:
if GETBIT(override, offset) == 1:
    type = HGET(meta, perm_id)
    if type == "deny":
        return DENY
    if type == "allow":
        return ALLOW
if role_bit == 1:
    return ALLOW
return DENY
```

**Pros**:
- ✅ Single bitmap
- ✅ Clear semantics (metadata)

**Cons**:
- ❌ Extra HGET call (+0.1ms latency)
- ❌ Metadata hash overhead

**Memory**: 13 MB for 10K users

---

### Solution 3: Bit Pairs (COMPLEX)

Use 2 bits per permission: `[allow_bit, deny_bit]`

```
Encoding:
00 = no override
01 = deny
10 = allow
11 = invalid (treat as deny)

Permission 0: bits 0-1
Permission 1: bits 2-3
Permission 2: bits 4-5
```

**Pros**:
- ✅ Single bitmap
- ✅ No metadata

**Cons**:
- ❌ Complex bit manipulation
- ❌ Doubles bit index calculations
- ❌ Wastes space (need 2 bits even if only allow OR deny)

**Memory**: Same as two bitmaps (2 bits per permission)

---

## Recommended Approach

### Use Two Bitmaps with Optimization

```java
// Only create bitmaps if user has overrides
if (!allowBitIndexes.isEmpty()) {
    createAllowBitmap(userId, allowBitIndexes);
}
if (!denyBitIndexes.isEmpty()) {
    createDenyBitmap(userId, denyBitIndexes);
}
```

**Why**:
1. ✅ Simplest implementation
2. ✅ Fastest permission check (2-3 GETBIT)
3. ✅ Only 25% of users have bitmaps (75% have no overrides)
4. ✅ Memory: 37.5 MB for 10K users (70% savings vs pre-computed)

---

## APISIX Implementation (Two Bitmaps)

```lua
local function check_permission(red, uid, roleId, perm_id, chunk_size)
    local chunk = math.floor(perm_id / chunk_size)
    local offset = perm_id % chunk_size
    
    -- Check deny first (fast rejection)
    local denyKey = "{user:" .. uid .. "}:deny:" .. chunk
    local denyBit = red:getbit(denyKey, offset)
    if denyBit == 1 then
        return false  -- DENIED
    end
    
    -- Check role or allow
    local roleKey = "{user:" .. uid .. "}:role:" .. roleId .. ":perm:" .. chunk
    local allowKey = "{user:" .. uid .. "}:allow:" .. chunk
    
    local roleBit = red:getbit(roleKey, offset)
    local allowBit = red:getbit(allowKey, offset)
    
    return (roleBit == 1 or allowBit == 1)
end
```

**Optimization with Lua EVAL** (single Redis call):

```lua
local script = [[
    local denyKey = KEYS[1]
    local roleKey = KEYS[2]
    local allowKey = KEYS[3]
    local offset = tonumber(ARGV[1])
    
    -- Check deny first
    local deny = redis.call('GETBIT', denyKey, offset)
    if deny == 1 then
        return 0
    end
    
    -- Check role or allow
    local role = redis.call('GETBIT', roleKey, offset)
    local allow = redis.call('GETBIT', allowKey, offset)
    
    return (role == 1 or allow == 1) and 1 or 0
]]

-- Execute
local result = red:eval(script, 3, denyKey, roleKey, allowKey, offset)
return result == 1
```

**Latency**: 0.12ms (vs 0.1ms for pre-computed)

---

## Final Verdict

**You MUST use two bitmaps (or bitmap + metadata) to support both allow and deny overrides.**

Single bitmap is mathematically insufficient (2 states < 3 states).

**Recommended**: Two bitmaps with conditional creation (already implemented in previous version).

---

## Alternative: If You Can Change Requirements

### Option A: Only Deny Overrides (No Allow)

```
user:{uid}:perm:{chunk}  → bit=0 means deny, missing key means no override

Permission check:
if user_key_exists AND user_bit == 0:
    return DENY
return role_bit
```

**Memory**: 6.25 MB for 10K users (only 5% have denies)

### Option B: Only Allow Overrides (No Deny)

```
user:{uid}:perm:{chunk}  → bit=1 means allow

Permission check:
return role_bit OR user_bit
```

**Memory**: 25 MB for 10K users (20% have allows)

---

## What Do You Want?

1. **Two bitmaps** (allow + deny) - 37.5 MB, 0.12ms ← RECOMMENDED
2. **Bitmap + metadata** - 13 MB, 0.2ms
3. **Bit pairs** - 37.5 MB, 0.12ms, complex
4. **Only deny** - 6.25 MB, 0.11ms, no allow overrides
5. **Only allow** - 25 MB, 0.11ms, no deny overrides

Which approach do you prefer?
