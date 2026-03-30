--
-- bitmap-auth.lua
-- APISIX plugin for O(1) bitmap-based authorization.
--
-- Flow:
--   1. Extract JWT from Authorization header
--   2. Decode JWT to extract user_id (uid) and session_id (sid)
--   3. Build permission key: "{METHOD}:{normalized_path}"
--   4. HGET perm:index key → permission_id
--   5. Compute: chunk = floor(permission_id / chunk_size), offset = permission_id % chunk_size
--   6. GETBIT user:{uid}:session:{sid}:perm:{chunk} offset
--   7. Return 403 if 0, pass-through if 1
--

local core       = require("apisix.core")
local redis_new  = require("resty.redis")
local jwt        = require("resty.jwt")

local plugin_name = "bitmap-auth"

local schema = {
    type = "object",
    properties = {
        redis_host = {
            type = "string",
            default = "127.0.0.1"
        },
        redis_port = {
            type = "integer",
            default = 6379
        },
        redis_password = {
            type = "string",
            default = ""
        },
        redis_timeout = {
            type = "integer",
            default = 100,  -- ms
            description = "Redis connection and read timeout in milliseconds"
        },
        redis_pool_size = {
            type = "integer",
            default = 100
        },
        jwt_secret = {
            type = "string",
            description = "JWT HMAC secret (base64-encoded)"
        },
        chunk_size = {
            type = "integer",
            default = 1024,
            description = "Bitmap chunk size in bits"
        },
        perm_cache_ttl = {
            type = "integer",
            default = 60,
            description = "Permission index LRU cache TTL in seconds"
        },
        bypass_paths = {
            type = "array",
            items = { type = "string" },
            default = {},
            description = "Paths to bypass authorization (e.g., /api/auth/login)"
        }
    },
    required = { "jwt_secret" }
}

local _M = {
    version  = 0.1,
    priority = 2510,  -- Run after authentication plugins
    name     = plugin_name,
    schema   = schema,
}

function _M.check_schema(conf)
    return core.schema.check(schema, conf)
end

-- Connect to Redis with connection pooling
local function get_redis(conf)
    local red = redis_new:new()
    red:set_timeout(conf.redis_timeout)

    local ok, conn_err = red:connect(conf.redis_host, conf.redis_port)
    if not ok then
        return nil, "Redis connect failed: " .. (conn_err or "unknown")
    end

    if conf.redis_password and conf.redis_password ~= "" then
        local auth_ok, auth_err = red:auth(conf.redis_password)
        if not auth_ok then
            return nil, "Redis auth failed: " .. (auth_err or "unknown")
        end
    end

    return red
end

-- Return Redis connection to pool
local function put_redis(red, conf)
    local pool_max = conf.redis_pool_size or 100
    red:set_keepalive(10000, pool_max)
end

-- Normalize request path: remove trailing slash, collapse doubles
local function normalize_path(path)
    if not path then return "/" end

    -- Collapse double slashes
    path = path:gsub("//+", "/")

    -- Remove trailing slash (except root)
    if #path > 1 and path:sub(-1) == "/" then
        path = path:sub(1, -2)
    end

    -- Replace path parameters with {id} pattern
    -- /users/123 → /users/{id}
    -- /orders/abc-def/items/456 → /orders/{id}/items/{id}
    path = path:gsub("/(%w[%w%-]*%w)", function(segment)
        -- If segment looks like an ID (numeric, UUID-like, or alphanumeric)
        if segment:match("^%d+$") or
           segment:match("^%x+%-%x+%-%x+%-%x+%-%x+$") or
           (#segment > 20 and segment:match("^%w+$")) then
            return "/{id}"
        end
        return "/" .. segment
    end)

    return path
end

-- Check if path should bypass authorization
local function is_bypass_path(path, bypass_paths)
    for _, bp in ipairs(bypass_paths) do
        if path == bp or path:sub(1, #bp + 1) == bp .. "/" then
            return true
        end
    end
    return false
end

-- Get permission index from shared cache or Redis
local function get_permission_index(red, conf, perm_key)
    local shared_cache = ngx.shared.perm_cache
    if shared_cache then
        local cached = shared_cache:get(perm_key)
        if cached then
            return cached
        end
    end

    -- Fetch from Redis
    local index, redis_err = red:hget("perm:index", perm_key)
    if not index or index == ngx.null then
        return nil, "Permission not found: " .. perm_key
    end

    local perm_id = tonumber(index)

    -- Store in shared cache
    if shared_cache and perm_id then
        shared_cache:set(perm_key, perm_id, conf.perm_cache_ttl)
    end

    return perm_id
end


function _M.rewrite(conf, ctx)
    local uri  = ctx.var.uri
    local method = ctx.var.request_method

    -- Check bypass paths
    if is_bypass_path(uri, conf.bypass_paths or {}) then
        return
    end

    -- 1. Extract JWT from Authorization header
    local auth_header = core.request.header(ctx, "Authorization")
    if not auth_header then
        return 401, { message = "Missing Authorization header" }
    end

    local token = auth_header:match("^Bearer%s+(.+)$")
    if not token then
        return 401, { message = "Invalid Authorization header format" }
    end

    -- 2. Decode JWT
    local jwt_secret = ngx.decode_base64(conf.jwt_secret)
    local jwt_obj = jwt:verify(jwt_secret, token)

    if not jwt_obj or not jwt_obj.verified then
        return 401, { message = "Invalid or expired token" }
    end

    local uid = jwt_obj.payload.uid
    local sid = jwt_obj.payload.sid
    local roleId = jwt_obj.payload.roleId

    if not uid or not sid or not roleId then
        return 401, { message = "Token missing required claims (uid, sid, roleId)" }
    end

    -- 3. Normalize path and build permission key
    local normalized = normalize_path(uri)
    local perm_key = method .. ":" .. normalized

    -- 4. Connect to Redis
    local red, redis_err = get_redis(conf)
    if not red then
        core.log.error("Redis connection failed: ", redis_err)
        return 500, { message = "Authorization service unavailable" }
    end

    -- 5. Get permission index
    local perm_id, perm_err = get_permission_index(red, conf, perm_key)
    if not perm_id then
        core.log.warn("Permission not found for: ", perm_key)
        put_redis(red, conf)
        return 403, { message = "Permission not defined: " .. perm_key }
    end

    -- 6. Compute chunk and offset
    local chunk_size = conf.chunk_size or 1024
    local chunk  = math.floor(perm_id / chunk_size)
    local offset = perm_id % chunk_size

    -- 7. Check permission: (role OR allow) AND (NOT deny)
    local roleKey  = "{user:" .. uid .. "}:role:" .. roleId .. ":perm:" .. chunk
    local allowKey = "{user:" .. uid .. "}:allow:" .. chunk
    local denyKey  = "{user:" .. uid .. "}:deny:" .. chunk

    -- Check deny first (fast rejection)
    local denyBit, deny_err = red:getbit(denyKey, offset)
    if denyBit == 1 then
        put_redis(red, conf)
        core.log.info("DENIED (deny list): user=", uid, " perm=", perm_key)
        return 403, { message = "Forbidden" }
    end

    -- Check role or allow
    local roleBit, role_err = red:getbit(roleKey, offset)
    local allowBit, allow_err = red:getbit(allowKey, offset)

    put_redis(red, conf)

    if not roleBit or not allowBit then
        core.log.error("GETBIT failed")
        return 500, { message = "Authorization check failed" }
    end

    -- 8. Authorize: (role OR allow) AND (NOT deny)
    if roleBit == 1 or allowBit == 1 then
        -- ALLOWED
        core.request.set_header(ctx, "X-User-Id", uid)
        core.request.set_header(ctx, "X-Session-Id", sid)
        core.request.set_header(ctx, "X-Perm-Key", perm_key)
        return
    end

    -- DENIED
    core.log.info("DENIED: user=", uid, " perm=", perm_key,
                   " index=", perm_id, " chunk=", chunk, " offset=", offset)
    return 403, { message = "Forbidden" }
end


return _M
