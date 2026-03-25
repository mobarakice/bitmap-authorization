# Performance Analysis

## Runtime Authorization Check (APISIX → Redis)

### Latency Breakdown

| Operation | Latency | Notes |
|---|---|---|
| JWT decode (local) | ~0.05 ms | In-memory, no network |
| Path normalization | ~0.01 ms | String manipulation |
| Perm index lookup (LRU hit) | ~0.001 ms | Local LRU cache |
| Perm index lookup (LRU miss → Redis HGET) | ~0.1-0.3 ms | Network round-trip |
| GETBIT (Redis) | ~0.1-0.2 ms | O(1), single command |
| **Total (cache hit)** | **~0.2 ms** | |
| **Total (cache miss)** | **~0.4 ms** | |

### QPS Estimation

**Single APISIX node + Redis:**

| Scenario | Expected QPS | Bottleneck |
|---|---|---|
| LRU cache warm | 50,000-100,000 | APISIX worker concurrency |
| LRU cache cold | 20,000-50,000 | Redis HGET + GETBIT |
| With connection pooling | 30,000-80,000 | Redis pipeline |

**Redis (single instance):**
- `GETBIT`: ~100,000 ops/sec per core
- `HGET`: ~80,000 ops/sec per core
- Combined: ~50,000-80,000 auth checks/sec

## Login-Time Computation

### Latency Breakdown

| Operation | Latency | Notes |
|---|---|---|
| DB: fetch role bit indexes | ~2-5 ms | Single indexed query |
| DB: fetch allow bit indexes | ~1-2 ms | Single indexed query |
| DB: fetch deny bit indexes | ~1-2 ms | Single indexed query |
| Redis: build temp bitmaps | ~5-15 ms | Pipeline N SETBIT operations |
| Redis: Lua script (BITOP) | ~1-3 ms | Server-side, atomic |
| Redis: TTL assignment | ~0.5 ms | Part of Lua script |
| **Total login overhead** | **~10-30 ms** | One-time per session |

## Scaling Strategy

### Horizontal Scaling

```
[APISIX Node 1] ──┐
[APISIX Node 2] ──┼──→ [Redis Cluster / Sentinel]
[APISIX Node N] ──┘
```

1. **APISIX**: Stateless, scale horizontally behind load balancer
2. **Redis**: Redis Cluster for >100K concurrent sessions
3. **Spring Boot**: Multiple instances behind LB; login is the only DB-heavy path

### Redis Cluster Sharding

With Redis Cluster, use hash tags to keep user session chunks on the same shard:

```
user:{uid}:session:{sid}:perm:{chunk}
→ hash tag: {uid}:{sid}
```

### Capacity Planning

| Scale | Active Sessions | Redis Memory | Redis Instances |
|---|---|---|---|
| Small | 1,000 | 20 MB | 1 |
| Medium | 10,000 | 200 MB | 1 |
| Large | 100,000 | 2 GB | 2-3 (cluster) |
| Enterprise | 1,000,000 | 20 GB | 6+ (cluster) |

## Edge Cases

### Permission Update During Active Session

**Problem**: Admin changes role permissions; active sessions have stale bitmaps.

**Solution**: 
1. Pub/Sub notification via `auth:invalidation` channel
2. Options:
   - **Lazy**: Mark session stale, recompute on next login
   - **Eager**: Recompute all affected sessions immediately
   - **Hybrid**: Set a short TTL override, forcing natural expiry

### Token Revocation

**Problem**: Need to invalidate a session before JWT expiry.

**Solution**: 
- Delete all `user:{uid}:session:{sid}:perm:*` keys
- GETBIT returns 0 for all permissions → effectively revoked
- No token blacklist needed

### Multi-Device Login

**Problem**: User logged in on multiple devices.

**Solution**: Each device gets a unique `sessionId` in JWT:
- `user:1:session:abc123:perm:0` (Device A)
- `user:1:session:def456:perm:0` (Device B)
- Independent sessions, independent bitmaps
- Revoking one device doesn't affect others

### Missing Permission Index

**Problem**: APISIX receives a request for an unregistered API path.

**Solution**: 
- `HGET perm:index "GET:/unknown"` returns nil
- Plugin returns 403 by default (deny-by-default policy)
- Logged as warning for admin review

### Bitmap Sparse Storage

**Problem**: With 100K permissions but a role with only 10, most chunks are empty.

**Solution**: 
- Redis only allocates memory for chunks that have bits set
- Empty chunks return 0 for GETBIT (implicit deny)
- No wasted memory for sparse permission sets
