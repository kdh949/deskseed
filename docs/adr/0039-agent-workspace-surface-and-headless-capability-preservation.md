# ADR 0039 — Agent Workspace surface and headless capability preservation

## Status

Accepted

## Context

`main` added customer, administration, audit, search, integration, SLA, and ticket-mutation screens while the frontend design-system branch replaced the former shell, tokens, and shared UI roots with one canonical system under `frontend/src/design-system/`. Merging both visual systems would leave competing tokens, duplicate shells, and screens whose design contract no longer matches the current product surface.

OpenAPI alone is not sufficient to reconstruct every frontend behavior. Actor and projection boundaries, semantic read-audit intent, idempotent command identity, optimistic concurrency, and separate PUBLIC/INTERNAL drafts also require executable client or headless contracts.

## Decision

- The shipped frontend surface is temporarily limited to Agent Queue, read-only Ticket Workspace, minimum staff login, and canonical denied/not-found states.
- Production routes are `/agent/login`, `/agent/views/:viewKey`, and `/agent/tickets/:ticketNumber`; `/` redirects to `/agent/views/my-open`. Removed or unknown routes render the canonical not-found state.
- AGENT and ADMIN with `AGENT_WORKSPACE` capability may enter Queue/Workspace. SECURITY_AUDITOR remains denied unless a later ADR changes the server authorization matrix and read-audit policy.
- `frontend/src/design-system/` and `frontend/src/design-system/index.css` are the only visual-system and global-style roots. Legacy tokens, CSS, shells, compatibility exports, archived screenshots, and aliases are deleted rather than preserved in the working tree.
- Backend code, migrations, Core/Platform OpenAPI, permission policy, and canonical audit behavior remain unchanged.
- Unshipped capabilities keep non-visual reconstruction contracts in OpenAPI, `frontend/src/api/`, feature `api/` or `model/` modules, and contract tests. React pages, page-specific fixtures, and screenshots are not retained merely as documentation.
- A completed backend requirement is not downgraded because its screen is deferred. The requirement matrix and capability recomposition matrix record backend readiness separately from current UI delivery.
- Ticket mutation stays fixture/Storybook-only in this slice. Production Ticket Workspace is explicitly read-only; a later mutation-wiring PR must re-verify command idempotency, 409 draft preservation, permissions, and audit semantics.

## Alternatives

- Keep both visual systems behind compatibility wrappers: rejected because it makes old tokens and shells reachable and prolongs ambiguous ownership.
- Keep every main screen and restyle incrementally: rejected because the merged PR would ship inconsistent, insufficiently reviewed product surfaces.
- Delete all frontend logic for deferred screens and rely only on OpenAPI: rejected because OpenAPI does not encode local draft lifetime, audit interaction intent, or every retry/concurrency transition.

## Consequences

- Queue and Workspace can evolve against one current design contract without reviving previous visual artifacts.
- Customer, Admin, Audit, Search, Integration, SLA, and auxiliary Workspace screens return canonical 404 until recomposed.
- API and backend capabilities remain available for future composition and continue to be verified without React page tests.
- The repository carries less demo breadth in exchange for a coherent, reversible frontend surface.
- Reintroducing a capability requires a vertical slice referencing `docs/55-frontend-capability-recomposition-matrix.md`, at least one `REQ-*`, and the applicable verification gates.
