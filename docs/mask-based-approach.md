# Mask-Based User Bitmap Approach

## Concept: User Bitmap as Permission Mask

### Formula
```
result = role_bit AND user_bit
```

### User Bitmap Encoding
```
user_bit = 1  →  Permission allowed (or no override)
user_bit = 0  →  Permission denied (explicit deny)
```

### How to Build User Bitmap
```
1. Start with all bits = 1 (allow everything by default)
2. For each permission in user's ALLOW list: set bit = 1 (redundant, already 1)
3. For each permission in user's DENY list: set bit = 0 (block this permission)
```

**Simplified**:
```
User bitmap = NOT(deny_bitmap)
```

If user has no deny list, user bitmap is all 1s (or doesn't need to exist).

---

## Example

### Scenario
- Role has permissions: [0, 1, 2, 3, 4]
- User allow list: [5] (extra permission beyond role)
- User deny list: [2] (block permission 2 from role)

### Bitmaps
```
role bitmap:  1 1 1 1 1 0 0 0 ...  (bits 0-4 set)
user bitmap:  1 1 0 1 1 1 0 0 ...  (bit 2 cleared for deny, bit 5 set for allow)

Permission 0: role=1 AND user=1 → ALLOW ✅
Permission 1: role=1 AND user=1 → ALLOW ✅
Permission 2: role=1 AND user=0 → DENY ❌ (denied by user)
Permission 3: role=1 AND user=1 → ALLOW ✅
Permission 4: role=1 AND user=1 → ALLOW ✅
Permission 5: role=0 AND user=1 → DENY ❌ (role doesn't have it)
```

**Problem**: User allow list [5] doesn't work! Role must have the permission.

---

## Issue with Simple AND

The simple `role AND user` formula **doesn't support allow overrides** (granting permissions beyond the role).

### What You Actually Need

```
result = (role OR allow) AND (NOT deny)
```

This requires **separate allow and deny tracking**, which brings us back to the previous solution.

---

## Alternative: Inverted Logic

If you **don't need allow overrides** (users can only have permissions from their role, with optional denies):

```
result = role AND user

Where:
- role bitmap: permissions granted by role
- user bitmap: all 1s except for denied permissions
```

### Building User Bitmap
```java
// Start with all bits = 1 (no restrictions)
// Then clear bits for denied permissions
for (int denyBit : denyBitIndexes) {
    bitmapService.clearBit(userPrefix, denyBit);  // Set to 0
}
```

**Optimization**: If user has no deny list, don't create user bitmap at all!

```lua
-- Permission check
local roleBit = redis.call('GETBIT', roleKey, offset)
if roleBit == 0 then
    return 0  -- Role doesn't have permission
end

-- Check if user has deny override
local userKey = 'user:' .. uid .. ':perm:' .. chunk
if redis.call('EXISTS', userKey) == 1 then
    local userBit = redis.call('GETBIT', userKey, offset)
    return userBit  -- 0 = denied, 1 = allowed
end

return 1  -- No user bitmap = no restrictions
```

---

## Comparison

### Option 1: User Bitmap = NOT(deny) [Simple, No Allow Overrides]

```
Redis Keys:
role:{roleId}:perm:{chunk}     → Role permissions
user:{uid}:perm:{chunk}         → Deny mask (only if user has denies)

Permission Check:
result = role AND user (or just role if user bitmap missing)

Pros:
✅ Simple (2 GETBIT)
✅ Minimal memory (only users with denies)
✅ No allow overrides needed

Cons:
❌ Cannot grant permissions beyond role
```

### Option 2: Separate Allow/Deny [Full Flexibility]

```
Redis Keys:
role:{roleId}:perm:{chunk}     → Role permissions
user:{uid}:allow:{chunk}        → Allow overrides
user:{uid}:deny:{chunk}         → Deny overrides

Permission Check:
result = (role OR allow) AND (NOT deny)

Pros:
✅ Full flexibility (allow + deny overrides)
✅ Granular invalidation

Cons:
❌ More complex (3 GETBIT)
❌ More memory (2 bitmaps per user)
```

---

## Recommendation

### If You DON'T Need Allow Overrides: Use Option 1

**User bitmap = deny mask only**

```java
// Build user bitmap (only denied permissions)
private void buildUserBitmap(Long userId, long ttlSeconds) {
    List<Integer> denyBitIndexes = permissionRepository.findDenyBitIndexesByUserId(userId);
    
    if (denyBitIndexes.isEmpty()) {
        log.info("User has no denies, no bitmap needed: userId={}", userId);
        return;  // Don't create bitmap
    }
    
    var userPrefix = String.format(USER_PERM_PREFIX, userId);
    var maxBitIndex = getMaxBitIndex();
    
    // Set all bits to 1 (allow by default)
    bitmapService.setAllBits(userPrefix, maxBitIndex);
    
    // Clear bits for denied permissions
    for (int denyBit : denyBitIndexes) {
        bitmapService.clearBit(userPrefix, denyBit);
    }
    
    bitmapService.setTtl(userPrefix, maxBitIndex, ttlSeconds);
}
```

**Memory**: Only 5% of users (those with denies) have bitmaps = **6.25 MB** for 10K users

### If You DO Need Allow Overrides: Use Option 2

Use the optimized separate allow/deny approach (already implemented).

---

## Which Do You Need?

### Question: Can users have permissions that their role doesn't have?

**Example**: User with "VIEWER" role gets special "DELETE" permission.

- **YES** → Use Option 2 (separate allow/deny)
- **NO** → Use Option 1 (deny mask only)

---

## Implementation for Option 1 (Deny Mask Only)

Let me know if you want this simpler approach implemented!
