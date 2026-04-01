# Capability-Based Authorization for Next.js Frontend

> **Status: PLANNED -- Not yet implemented.**
> The backend does not yet resolve capabilities at login or return them in `LoginResponse`.
> Frontend patterns below are reference designs for when the capability system is built.
> See [Entity Relationships — Presentation Layer](entity_relationships.md#2-presentation-layer-planned)
> for the planned data model and resolution algorithm.

**PSS / CRS System — RBAC + ABAC Integration**

- **Author**: Architecture Team
- **Audience**: Frontend & Backend Development Teams
- **Scope**: UI authorization model for Next.js / React applications

---

## 1. Overview

This document defines the Capability-Based UI Authorization Model for the frontend of the PSS/CRS platform.

The backend system implements:

- RBAC (Role-Based Access Control) via bitmap permissions
- ABAC (Attribute-Based Access Control) extensions (planned)

The frontend must:

- Show/hide UI features
- Protect routes
- Control component visibility
- Avoid exposing unauthorized operations

### ⚠️ Important

> **Frontend authorization is UX control only.**  
> **Actual security enforcement must remain in the backend.**

## 2. Why Capability-Based UI Authorization

Traditional frontend authorization approaches often rely on:

| Approach | Problems |
|----------|----------|
| Role-based UI | Roles become tightly coupled with UI |
| HTTP-method based permissions | Not aligned with business actions |
| API permission checks per request | High latency |

Capability-based authorization solves these problems by exposing business capabilities to the frontend.

## 3. Key Concepts

### 3.1 Resource

A domain entity within the system.

Examples in CRS/PSS:

- `BOOKING`
- `TICKET`
- `PASSENGER`
- `AGENCY`
- `PAYMENT`
- `FARE_RULE`

### 3.2 Action

An operation performed on a resource.

Examples:

- `CREATE`
- `VIEW`
- `UPDATE`
- `DELETE`
- `CANCEL`
- `ISSUE`
- `REFUND`
- `APPROVE`
- `OVERRIDE`

### 3.3 Capability

A resource-action pair representing a business permission.

```
Capability = Resource + Action
```

Examples:

- `BOOKING_CREATE`
- `BOOKING_VIEW`
- `BOOKING_CANCEL`
- `TICKET_ISSUE`
- `PAYMENT_REFUND`
- `FARE_OVERRIDE`

Capabilities are stable identifiers used by the frontend.

## 4. Authorization Architecture

```
Frontend (Next.js)
│
│ receives
▼
User Capabilities
│
▼
UI Guards / Route Guards
│
▼
Backend APIs
│
▼
Policy Engine (RBAC + ABAC)
```

The backend remains the source of truth.

## 5. Capability Lifecycle

### Step 1 — API Discovery (Backend)

During application startup:

- Spring Boot scans controllers

Using:

- `RequestMappingHandlerMapping`

Example discovered APIs:

```
GET    /api/bookings/{id}
POST   /api/bookings
PATCH  /api/bookings/{id}
POST   /api/bookings/{id}/cancel
POST   /api/tickets/{id}/issue
```

### Step 2 — Convert API → Capability

Mapping logic:

| HTTP Method | Path | Resource | Action | Capability |
|-------------|------|----------|--------|------------|
| GET | /bookings/{id} | BOOKING | VIEW | BOOKING_VIEW |
| POST | /bookings | BOOKING | CREATE | BOOKING_CREATE |
| PATCH | /bookings/{id} | BOOKING | UPDATE | BOOKING_UPDATE |
| POST | /bookings/{id}/cancel | BOOKING | CANCEL | BOOKING_CANCEL |
| POST | /tickets/{id}/issue | TICKET | ISSUE | TICKET_ISSUE |

### Step 3 — Store Capability in Database

Capabilities are persisted and assigned to roles.

## 6. Database Model

For the planned database schema (modules, features, capabilities, capability_permissions),
see [Entity Relationships -- Presentation Layer](entity_relationships.md#2-presentation-layer-planned).

The enforcement layer schema (roles, users, permissions, allow/deny overrides) is documented in
[Entity Relationships -- Enforcement Layer](entity_relationships.md#1-enforcement-layer-implemented).

## 7. Login Response

> **Current state:** `LoginResponse` returns 6 fields: `accessToken`, `tokenType`, `expiresIn`,
> `userId`, `username`, `role`. It does **not** include capabilities or modules yet.

When the capability system is implemented, the login response will be enhanced to include
a flat capabilities list and a module tree. See
[Entity Relationships -- Planned Login Response](entity_relationships.md#planned-login-response-enhanced)
for the target response shape.

Frontend will store capabilities in auth state or session storage.

## 8. Frontend Authorization Strategy

Frontend authorization operates at three levels:

1. Menu authorization
2. Route protection
3. Component-level authorization

### 8.1 Menu Authorization

Menu items include a required capability.

Example (TypeScript):

```typescript
import { Capability } from '@/types/auth';

interface MenuItem {
  label: string;
  path: string;
  capability: Capability;
}

const menu: MenuItem[] = [
  {
    label: "Bookings",
    path: "/bookings",
    capability: "BOOKING_VIEW"
  },
  {
    label: "Create Booking",
    path: "/bookings/create",
    capability: "BOOKING_CREATE"
  }
];
```

Menus are filtered out of the UI payload ideally before rendering.

### 8.2 Route Protection (Next.js)

Route access must validate capability. In Next.js, this should be done at the middleware or Server Component level to prevent sending unauthorized code to the client, but can also be handled via client-side wrappers.

**Example Next.js Middleware (`middleware.ts`):**

```typescript
import { NextResponse } from 'next/server';
import type { NextRequest } from 'next/server';

export function middleware(request: NextRequest) {
  const token = request.cookies.get('auth_token');
  // decodeAndGetCapabilities is a custom function to parse the JWT or session
  const userCapabilities = decodeAndGetCapabilities(token);

  if (request.nextUrl.pathname.startsWith('/bookings/create')) {
    if (!userCapabilities.includes('BOOKING_CREATE')) {
      return NextResponse.redirect(new URL('/unauthorized', request.url));
    }
  }
  return NextResponse.next();
}
```

**Example Client HOC:**

```tsx
import { Capability } from '@/types/auth';

function withCapability<P extends object>(capability: Capability, Component: React.ComponentType<P>) {
  return function Protected(props: P) {
    const { capabilities } = useAuth();

    if (!capabilities.includes(capability)) {
      return <ForbiddenPage />;
    }

    return <Component {...props} />;
  }
}
```

### 8.3 Component-Level Authorization

Reusable component for fine-grained UI control:

```tsx
import { ReactNode } from 'react';
import { Capability } from '@/types/auth';

interface CanProps {
  capability: Capability;
  children: ReactNode;
  fallback?: ReactNode; // Optional UI to show if denied
}

export function Can({ capability, children, fallback = null }: CanProps) {
  const { capabilities } = useAuth();

  if (!capabilities.includes(capability)) {
    return <>{fallback}</>;
  }

  return <>{children}</>;
}
```

Usage:

```tsx
<Can capability="BOOKING_CANCEL" fallback={<span className="text-gray-400">Cannot Cancel</span>}>
  <CancelBookingButton />
</Can>
```

## 9. Handling ABAC Conditions in the UI

While the backend is the definitive enforcer of ABAC policies, the frontend often needs to reflect these conditions to provide a good UX (e.g., disabling the "Cancel" button if the booking is already ticketed).

Example policy:

```text
user can cancel booking if:
  booking.owner_id == user.id
  AND booking.status != TICKETED
```

To handle this gracefully in the frontend, combine the capability check (can the user ever do this?) with local state evaluation (is it valid for this specific resource state?):

```tsx
const { user, hasCapability } = useAuth();
const isOwner = booking.ownerId === user.id;
const isNotTicketed = booking.status !== 'TICKETED';

// 1. Check baseline permission (Capability)
// 2. Check resource-specific conditions (ABAC reflection in UI)
const canCancel = hasCapability('BOOKING_CANCEL') && isOwner && isNotTicketed;

return (
  <button disabled={!canCancel} onClick={handleCancel}>
    Cancel Booking
  </button>
);
```

**Important:** The frontend simply uses local state to improve DX/UX. The backend must independently enforce the full ABAC policy.

## 10. Backend Authorization Flow

```
Client Request
│
▼
API Gateway
│
▼
Backend Service
│
▼
Authorization Engine (RBAC + ABAC)
│
▼
Allow / Deny
```

## 11. Performance Considerations

Recommended practices:

| Practice | Reason |
|----------|--------|
| Cache capabilities in session/JWT | Avoid DB queries |
| Resolve permissions during login | Faster UI rendering |
| Avoid API permission checks per component | Prevent latency |
| Use capability constants | Avoid typos |
| Lazy-load protected modules | Improve performance |

## 12. Naming Convention

Capabilities must follow:

```
RESOURCE_ACTION
```

Examples:

```
BOOKING_CREATE
BOOKING_VIEW
BOOKING_UPDATE
BOOKING_CANCEL

TICKET_ISSUE
PAYMENT_REFUND
FARE_OVERRIDE
```

Avoid:

```
POST_BOOKING
GET_BOOKING
PATCH_BOOKING
```

These couple UI to HTTP transport.

## 13. Security Guidelines

> **Frontend authorization is not a security boundary.**

Backend must always:

- Validate permissions
- Enforce RBAC rules
- Apply ABAC policies
- Reject unauthorized API requests

**Never trust frontend checks.**

## 14. Example Capability Usage in UI

```
Dashboard
├── Booking Module
│   ├── BOOKING_VIEW
│   ├── BOOKING_CREATE
│   └── BOOKING_CANCEL
│
├── Ticket Module
│   └── TICKET_ISSUE
│
└── Finance Module
    └── PAYMENT_REFUND
```

Each UI feature maps to a capability.

## 15. Benefits of Capability-Based Model

| Benefit | Description |
|---------|-------------|
| Decouples UI from roles | UI checks capability instead of roles |
| Flexible backend policy | Roles and ABAC rules can evolve |
| Stable permission identifiers | Capabilities rarely change |
| Simplified frontend logic | One permission check |
| Scales to large systems | Works with thousands of APIs |

## 16. Developer Experience & Type Safety

To ensure consistency between the backend and frontend, dynamically generate TypeScript definitions for capabilities.

1. **API Endpoint**: Backend exposes `GET /api/system/capabilities` returning all distinct system capabilities.
2. **Generation**: A build script (`e.g., OpenAPI generator or custom script`) converts these to a TypeScript union type and constants file.
3. **Usage**: Frontend code exclusively uses the generated `Capability` type, eliminating typos and ensuring compile-time safety.

Example generated types:

```typescript
// auto-generated file - do not edit
export type Capability = 
  | 'BOOKING_CREATE'
  | 'BOOKING_VIEW'
  | 'BOOKING_UPDATE'
  | 'BOOKING_CANCEL'
  | 'TICKET_ISSUE';

export const CAPABILITIES: Record<Capability, Capability> = {
  BOOKING_CREATE: 'BOOKING_CREATE',
  // ...
};
```

## 17. Recommended Best Practices

1. **Always check capabilities in UI, not roles:** Hardcoding references to "ADMIN" or "SUPERVISOR" tightly couples the UI to business roles that may evolve.
2. **Use Strict TypeScript Definitions:** Generate `Capability` union types from backend enums to prevent string typos.
3. **Never derive permission from HTTP method alone:** Use explicit business actions (`BOOKING_CANCEL`) over REST operations (`BOOKING_POST`).
4. **Backend must always enforce authorization:** The frontend `<Can>` wrapper is exclusively for UX flow, not security.
5. **Combine Capabilities with Application State for ABAC:** Reflect attribute-based constraints in the UI using local state checks alongside capability checks to avoid disabled/failing API calls.
6. **Graceful Fallbacks:** Provide clear UI feedback (e.g. grayed-out buttons or "Access Denied" alerts) instead of letting an API failure be the first indication of missing privileges.

## 18. Summary

Capability-based UI authorization provides:

- Clean separation between UI and backend policies
- Scalable permission management
- Stable frontend permission checks
- Compatibility with RBAC + ABAC systems
