# Redis-Based Authorization Service

A high-performance, stateless authorization service that eliminates database calls from the API request hot path. Permissions are pre-computed as Redis bitmaps at login; every subsequent request is authorized by the APISIX gateway via three `GETBIT` calls — no JVM wake-up, no DB query.

## Architecture Highlights
- **Java 25 / Spring Boot 4.0.4**: Virtual threads (`spring.threads.virtual.enabled=true`), records, pattern matching, `StructuredTaskScope` for parallel DB queries.
- **Jedis** over Lettuce for Redis connectivity; pipelined `SETBIT` on login, pooled connections at the gateway.
- **O(1) Authorization**: Each permission maps to a unique integer `bit_index`; lookup is a single `HGET` + `GETBIT`.
- **Three-Tier Bitmap Model**: Separate `role`, `allow`, and `deny` bitmaps. Role bitmaps are permanent and shared across all users of a role; allow/deny bitmaps are user-scoped, TTL-bound, and created only when the user has overrides (75% of users generate zero user-level keys).
- **Permission Formula**: `(role OR allow) AND (NOT deny)` — evaluated at request time by the APISIX Lua plugin; deny checked first for fast rejection.
- **Granular Cache Invalidation**: Role permission changes invalidate one bitmap key regardless of user count. Coordinated across JVM instances via Redis Pub/Sub (`auth:invalidation`).
- **Stateless Flow**: Spring Security configured as stateless (no CSRF, no server-side session).

## Prerequisites
- **Java 25** (Gradle 8.12 runtime)
- **Docker** & **Docker Compose**
- **PostgreSQL**
- **Redis 7+**

## Quick Start

### 1. Start Support Services
Ensure PostgreSQL and Redis are running locally or start them using the provided `docker-compose.yaml`:
```bash
docker compose up -d postgres redis
```
*(By default, `application.yaml` points to `localhost:5432` for Postgres and `localhost:6379` for Redis).*

### 2. Build and Run
With `JAVA_HOME` pointing to Java 25, launch the Spring Boot application:
```bash
./gradlew bootRun
```
Flyway handles all database migrations (V1 through V3) automatically on startup, including schema creation, tables, and seeding standard roles and permissions.

### 3. Verification & Swagger UI
Open the generated OpenAPI Swagger UI to inspect endpoints:
- **Swagger URL**: [http://localhost:8080/swagger-ui.html](http://localhost:8080/swagger-ui.html)
- **OpenAPI Schema**: [http://localhost:8080/api-docs](http://localhost:8080/api-docs)

### API Overview
- `POST /api/auth/login` - Exchange credentials for a stateless JWT.
- `GET /api/admin/roles` - Retrieve all RBAC roles.
- `GET /api/admin/permissions` - Retrieve indexed permissions.

## Gateway Integration
The APISIX Lua plugin at `apisix/plugins/bitmap-auth.lua` handles authorization at the edge without touching the JVM:

1. Extract and validate the JWT (`uid`, `sid`, `roleId` claims).
2. Normalize the request path (`/orders/123` → `/orders/{id}`) and build the permission key.
3. `HGET perm:index "{METHOD}:{path}"` → `bit_index` (cached in an LRU shared dict, default TTL 60 s).
4. Compute `chunk = floor(bit_index / 1024)`, `offset = bit_index % 1024`.
5. `GETBIT user:{uid}:deny:{chunk} {offset}` — return 403 immediately if `1`.
6. `GETBIT role:{roleId}:perm:{chunk} {offset}` and `GETBIT user:{uid}:allow:{chunk} {offset}` — allow if either is `1`, otherwise 403.

All Redis keys used at request time are written during login by the Spring Boot service and require no recomputation per request.
