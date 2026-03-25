# Project Context — Software Architect Workspace

## Role & Identity

This file provides **project context only**. Role behavior and output format are controlled by
`--system` profiles (`carch` / `ccode` / `creview`). When no profile is active, apply defaults below.

- Domain: distributed systems, microservices, airline/travel tech (PSS, DCS, inventory)
- Stack: Java 25, Spring Boot 4, PostgreSQL 17+, Redis, Docker/Compose, JWT/RBAC, APISIX
- Audience: expert-level — no basic explanations unless `+explain` appended

## Response Contract (Token Budget Rules)

### Always

- Lead with the **direct answer or code** — no preambles, no "Certainly!" openers
- Use **code blocks** for all code, SQL, YAML, shell commands
- Prefer **bullet lists** over prose paragraphs for explanations
- State **assumptions** explicitly in ≤2 lines before code
- Flag **breaking changes** and **security implications** inline with `⚠️`

### Never

- Repeat back the user's question
- Add unsolicited apologies, disclaimers, or filler phrases
- Explain basic concepts unless explicitly asked (`--explain` in the prompt)
- Generate more than one implementation variant unless asked for alternatives

### Code Output Rules

- Default: production-ready, not tutorial-quality
- Always include error handling and strong typing
- SQL: include EXPLAIN ANALYZE hints when writing complex queries
- Java 25: use `record`, `sealed`, `var`, pattern matching (`switch`), virtual threads (`Thread.ofVirtual()`), and GraalVM-compatible idioms
- Spring Boot 4: prefer constructor injection, `@Service`/`@Repository` layering, reactive where relevant
- No raw types, no unchecked casts; use `Optional<>` over null returns

## Abbreviation Glossary (save tokens — use these)

| Short | Meaning                            |
|-------|------------------------------------|
| PSS | Passenger Service System           |
| DCS | Departure Control System           |
| PNL | Passenger Name List                |
| PNR | Passenger Name Record              |
| RBAC | Role-Based Access Control          |
| ABAC | Attribute-Based Access Control     |
| API | Spring Boot 4 REST unless stated   |
| DB | PostgreSQL 17 unless stated        |
| cache | Redis unless stated                |
| GW | API Gateway (APISIX unless stated) |

## Architecture Defaults (assume unless overridden)

- **Auth**: JWT RS256, asymmetric keys, validated at gateway layer
- **DB**: PostgreSQL 17+, schema-per-domain, UUID primary keys
- **API**: Spring Boot 4 with layered arch (controller → service → repository); virtual threads enabled via `spring.threads.virtual.enabled=true`
- **Language**: Java 25 — `record` for DTOs, `sealed` for domain types, pattern matching for dispatch, `StructuredTaskScope` for concurrent tasks, GraalVM native-image ready
- **Cache**: Redis with TTL-based invalidation
- **Deploy**: Docker Compose (dev), Kubernetes (prod intent)
- **Logging**: Structured JSON (Logback), correlation IDs on all requests via MDC

## File Structure Convention

```
project/
├── CLAUDE.md           ← this file (project context)
├── .claude/
│   ├── settings.json   ← CLI preferences
│   └── commands/       ← slash command definitions
├── src/
├── tests/
└── docs/
```

## Output Format Preferences

| Request type | Format |
|---|---|
| Architecture review | Numbered findings + severity label [HIGH/MED/LOW] |
| Code generation | Code block only + inline comments |
| SQL | Formatted SQL + execution plan note |
| Debug/root cause | 3-section: Cause → Fix → Prevention |
| Comparison | Markdown table |

## Token-Saving Prompt Shortcuts

Append these to any prompt to control output:

- `+brief` → 3-5 bullets only, no code
- `+code` → code only, no explanation
- `+explain` → add beginner-level explanation
- `+alt` → provide 2 alternative approaches
- `+prod` → production hardening checklist
- `+sql` → generate SQL representation
- `+review` → code review mode (find issues only)
- `+test` → generate test cases only
