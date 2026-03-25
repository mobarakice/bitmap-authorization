-- =============================================================================
-- compute_user_bitmap.lua
-- =============================================================================
-- Computes a user's effective permission bitmap from a PERMANENT role bitmap,
-- temporary allow/deny bitmaps, and stores the result as the user bitmap.
--
-- Formula: user_perm = (role_perm OR allow_perm) AND (NOT deny_perm)
--
-- KEYS layout (per chunk, repeated for each chunk):
--   KEYS[i*4 + 1] = role chunk key   (PERMANENT — do NOT delete)
--   KEYS[i*4 + 2] = allow chunk key  (temporary)
--   KEYS[i*4 + 3] = deny chunk key   (temporary)
--   KEYS[i*4 + 4] = result chunk key (user perm — output)
--
-- ARGV[1] = TTL in seconds for result keys
-- ARGV[2] = number of chunks
-- ARGV[3..] = temp key names for intermediate operations (2 per chunk)
-- =============================================================================

local ttl = tonumber(ARGV[1])
local numChunks = tonumber(ARGV[2])

for i = 0, numChunks - 1 do
    local roleKey   = KEYS[i * 4 + 1]
    local allowKey  = KEYS[i * 4 + 2]
    local denyKey   = KEYS[i * 4 + 3]
    local resultKey = KEYS[i * 4 + 4]

    local tmpOrKey  = ARGV[3 + i * 2]
    local tmpNotKey = ARGV[4 + i * 2]

    -- Check if role or allow keys have data for this chunk
    local roleExists  = redis.call('EXISTS', roleKey)
    local allowExists = redis.call('EXISTS', allowKey)

    if roleExists == 1 or allowExists == 1 then
        -- At least one source has bits → BITOP OR produces output
        redis.call('BITOP', 'OR', tmpOrKey, roleKey, allowKey)

        local denyExists = redis.call('EXISTS', denyKey)

        if denyExists == 1 then
            -- Deny data exists: result = (role OR allow) AND (NOT deny)
            redis.call('BITOP', 'NOT', tmpNotKey, denyKey)
            redis.call('BITOP', 'AND', resultKey, tmpOrKey, tmpNotKey)
            redis.call('DEL', tmpNotKey)
        else
            -- No deny data: result = role OR allow (just rename)
            redis.call('RENAME', tmpOrKey, resultKey)
            tmpOrKey = nil
        end

        -- Set TTL on result
        redis.call('EXPIRE', resultKey, ttl)
    end

    -- Cleanup TEMPORARY keys only (role key is PERMANENT — never delete)
    if tmpOrKey then
        redis.call('DEL', tmpOrKey)
    end
    redis.call('DEL', allowKey, denyKey)
end

return numChunks
