-- =============================================================================
-- check_permission.lua
-- =============================================================================
-- Performs O(1) permission check: result = (role OR allow) AND (NOT deny)
--
-- KEYS[1] = role chunk key
-- KEYS[2] = allow chunk key
-- KEYS[3] = deny chunk key
--
-- ARGV[1] = bit offset within chunk
--
-- Returns: 1 (ALLOWED) or 0 (DENIED)
-- =============================================================================

local roleKey  = KEYS[1]
local allowKey = KEYS[2]
local denyKey  = KEYS[3]
local offset   = tonumber(ARGV[1])

-- Check deny first (fast rejection)
local denyBit = redis.call('GETBIT', denyKey, offset)
if denyBit == 1 then
    return 0  -- DENIED
end

-- Check role or allow
local roleBit  = redis.call('GETBIT', roleKey, offset)
local allowBit = redis.call('GETBIT', allowKey, offset)

if roleBit == 1 or allowBit == 1 then
    return 1  -- ALLOWED
end

return 0  -- DENIED
