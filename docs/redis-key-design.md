# Redis Key Design

## Key Schema

| Key Pattern | Type | TTL | Description |
|---|---|---|---|
| `perm:index` | HASH | None (persistent) | Global permission → bit_index mapping |
| `user:{uid}:session:{sid}:perm:{chunk}` | STRING (bitmap) | Same as JWT expiry | User session permission bitmap chunk |

## Detailed Key Descriptions

### `perm:index` — Permission Index (Persistent)

```
Type: HASH
TTL: None (refreshed on app startup, updated on permission CRUD)
Fields: "{ACTION}:{resource}" → "{bit_index}"

Example:
  HSET perm:index "GET:/api/users" "0"
  HSET perm:index "POST:/api/users" "1"
  HSET perm:index "GET:/api/orders/{id}" "16"
```

**Memory**: ~100 bytes per permission entry. For 100K permissions: ~10 MB.

### `user:{uid}:session:{sid}:perm:{chunk}` — Session Bitmap (Ephemeral)

```
Type: STRING (used as bitmap)
TTL: JWT expiry (default 3600s)
Size: chunk_size / 8 bytes per key = 128 bytes per chunk

Example:
  Key: user:1:session:a1b2c3d4:perm:0
  Value: (bitmap, 128 bytes)
  GETBIT user:1:session:a1b2c3d4:perm:0 5 → 1 (ALLOWED)
```

## Memory Estimation

### Per User Session

| Parameter | Value |
|---|---|
| Chunk size | 1024 bits = 128 bytes |
| Total permissions | 100,000 |
| Chunks per session | ceil(100000 / 1024) = 98 |
| Bytes per session | 98 × 128 = 12,544 bytes ≈ **12.5 KB** |
| Key overhead (per chunk) | ~80 bytes |
| Total per session | 98 × (128 + 80) ≈ **20 KB** |

### Aggregate

| Users/Sessions | Memory |
|---|---|
| 100 active sessions | ~2 MB |
| 1,000 active sessions | ~20 MB |
| 10,000 active sessions | ~200 MB |
| 100,000 active sessions | ~2 GB |

### Permission Index

| Permissions | Memory |
|---|---|
| 1,000 | ~100 KB |
| 10,000 | ~1 MB |
| 100,000 | ~10 MB |

## TTL Strategy

| Key | TTL | Rationale |
|---|---|---|
| `perm:index` | None | Persistent, refreshed on startup |
| `user:*:session:*:perm:*` | JWT expiry (3600s default) | Auto-cleanup when session expires |

## Cleanup

- Session keys auto-expire with TTL
- On logout: explicit `DEL` of all chunk keys
- On permission update: Pub/Sub notification for session recomputation
