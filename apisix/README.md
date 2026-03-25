# APISIX Bitmap Authorization Plugin

## Overview

The `bitmap-auth` plugin performs O(1) authorization checks using Redis bitmaps.

## Prerequisites

- APISIX 3.x
- Redis 7.x
- `resty.jwt` module installed in APISIX's OpenResty
- `resty.redis` module (bundled with OpenResty)

## Installation

### 1. Copy plugin file

```bash
cp plugins/bitmap-auth.lua /usr/local/apisix/apisix/plugins/
```

### 2. Register the plugin

Add `bitmap-auth` to the plugin list in `config.yaml`:

```yaml
plugins:
  - bitmap-auth
```

### 3. Configure routes

```yaml
routes:
  - uri: /api/*
    upstream:
      type: roundrobin
      nodes:
        "backend:8080": 1
    plugins:
      bitmap-auth:
        redis_host: "redis"
        redis_port: 6379
        jwt_secret: "YOUR_BASE64_JWT_SECRET"
        chunk_size: 1024
        perm_cache_ttl: 60
        bypass_paths:
          - "/api/auth/login"
          - "/api/auth/logout"
```

## Configuration Reference

| Parameter        | Type    | Default     | Description                             |
|-----------------|---------|-------------|-----------------------------------------|
| `redis_host`    | string  | 127.0.0.1   | Redis server hostname                   |
| `redis_port`    | integer | 6379        | Redis server port                       |
| `redis_password`| string  | ""          | Redis password                          |
| `redis_timeout` | integer | 100         | Redis timeout in ms                     |
| `redis_pool_size`| integer| 100         | Redis connection pool size              |
| `jwt_secret`    | string  | (required)  | Base64-encoded JWT HMAC secret          |
| `chunk_size`    | integer | 1024        | Bitmap chunk size (must match backend)  |
| `perm_cache_ttl`| integer | 60          | Permission index LRU cache TTL (sec)    |
| `bypass_paths`  | array   | []          | Paths exempt from authorization         |

## How It Works

```
Request → Extract JWT → Decode (uid, sid) → Normalize path
  → HGET perm:index "{METHOD}:{path}" → permission_id
  → chunk = id / 1024, offset = id % 1024
  → GETBIT user:{uid}:session:{sid}:perm:{chunk} offset
  → 1? ALLOW : 403 DENY
```

## Docker Deployment

```bash
docker-compose up -d
```

The `docker-compose.yml` in the project root mounts:
- `apisix/plugins/bitmap-auth.lua` → `/usr/local/apisix/apisix/plugins/bitmap-auth.lua`
- `apisix/config.yaml` → `/usr/local/apisix/conf/config.yaml`
