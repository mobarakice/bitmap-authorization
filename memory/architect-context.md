# Architect Memory Bank

# Claude reads this to maintain continuity across sessions

# Update after each major session. Keep ≤200 lines

## Decisions Log

### Auth Architecture (2026-02)

- Chose RS256 JWT asymmetric signing
- Token validated at APISIX gateway layer (not service layer)
- RBAC + ABAC hybrid policy model
- `external_id` for cross-system entity mapping
- `hierarchy_path` using ltree for org tree traversal

### Database Conventions (2026-02)

- PostgreSQL 17+ everywhere
- UUID PKs: `gen_random_uuid()`
- Soft deletes: `deleted_at TIMESTAMP` (not boolean flag)
- Auditing: `created_at`, `updated_at` auto-triggers
- Multi-tenancy: `tenant_id` on all domain tables

### API Architecture (2026-02)

- Spring Boot 4 layered with DDD: controller → service → repository
- Correlation ID on all requests (middleware)
- HTTP 422 for validation errors, 409 for conflicts

### Infrastructure (2026-02)

- Dev: Docker Compose
- Redis for session/token cache with TTL
- APISIX as API gateway
- Structured JSON logging with correlation IDs

## Active Work Streams

- Auth/Authorization service (JWT + RBAC/ABAC)

## Known Issues / Watch Points

- Redis Commander auth was fixed — REDIS_HOSTS must include password
- APISIX JWT plugin: use asymmetric config, not symmetric

## Glossary in Use

See CLAUDE.md abbreviation table.
