# Redis-Based Authorization Service

A high-performance, stateless authorization service designed for O(1) runtime permission checks using Redis chunked bitmaps. 

This project aims to completely remove database calls from the authorization hot path by pre-computing a user's permissions during login using Redis bitwise operations (`OR`, `AND NOT`) and caching them as chunked binaries. These binaries are then accessed instantly by the API Gateway (via an APISIX Lua plugin) to validate requests.

## Architecture Highlights
- **Java 21 / Spring Boot 3+**: Utilizes virtual threads (`Executors.newVirtualThreadPerTaskExecutor()`), strict pattern matching, Java records, and the Stream API.
- **Jedis**: Engineered specifically with Jedis over Lettuce for Redis connectivity.
- **O(1) Authorization**: Permissions mapped to unique integer bit indexes. 
- **Chunked Bitmap Strategy**: Bitmaps are generated in chunks of 1024 bits (128 bytes) each.
- **Role-Based + APIC (Attribute/Context)**: Complete with Admin/Role models, specific allow/deny overrides per user, and Pub/Sub invalidation.
- **Stateless Flow**: Spring Security is configured uniformly as stateless (no CSRF, no session creation).

## Prerequisites
- **Java 21 or Java 23** (Gradle 8.12 runtime)
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
With `JAVA_HOME` pointing to Java 21 or 23, launch the Spring Boot application:
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
This service includes a bespoke APISIX generic Lua plugin inside `/apisix/plugins/bitmap-auth.lua`.
The plugin acts on the gateway level by:
1. Intercepting incoming requests.
2. Decoding the attached JWT to obtain the `userId` and `sessionId`.
3. Reading `GETBIT user:{uid}:session:{token}:perm:{chunk} {offset}` natively without waking the JVM.
4. Immediately allowing (`200 OK`) or denying (`403 Forbidden`) the request.
