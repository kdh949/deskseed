# ADR 0035 — Staff password sessions and first-admin bootstrap

## Status

Accepted

## Context

M2 requires ADMIN/AGENT email-password authentication, but the existing documents do not fix the initial credential delivery, SPA CSRF exchange, or login lockout semantics. A committed default password, browser token storage, or UI-only authorization would make the first staff slice unsafe.

## Decision

- Staff passwords are BCrypt hashes with cost 12. Interactive and bootstrap passwords are 12–128 characters and are never logged, audited, returned, or stored in configuration committed to Git.
- The first ADMIN is created only when the staff table is empty and the operator supplies an email plus a password file outside the repository. Partial bootstrap configuration fails startup. Once any staff account exists, bootstrap input cannot replace or recover credentials.
- Staff authentication uses a server-side Spring Security session. The session cookie is `HttpOnly`, `SameSite=Lax`, and `Secure` in the production profile. Idle expiry is 60 minutes and absolute expiry is 12 hours.
- The SPA obtains a server-generated CSRF token before login. Login, logout, and every admin mutation require the token. CORS credentials are allowed only for configured origins; the supported production topology remains same-origin through the frontend reverse proxy.
- Login failures are counted for a bounded fingerprint of normalized email plus the direct remote address. Ten failures in a rolling 15-minute window lock that key until the window ends. The response is generic for unknown, wrong-password, and disabled accounts; throttled attempts return `429` with `Retry-After` without revealing account existence.
- Every protected request revalidates that the staff account is active. API route authorization and method authorization both require ADMIN for organization administration.
- An ADMIN cannot disable their own account or the last active ADMIN. Staff/group/membership deactivation is rejected while it would leave a current ticket assignment invalid.

## Alternatives

- A password committed for first boot: rejected because cloning the repository would disclose an administrative credential.
- JWTs in browser storage: rejected because server-side invalidation and disabled-account enforcement are required and the deployment is a single instance.
- CSRF disabled for JSON APIs: rejected because cookies are sent automatically and the frontend and API are same-origin in production.
- Redis-backed rate limiting: rejected because PostgreSQL is the accepted initial store and the expected authentication volume does not justify another dependency.

## Consequences

- Operators must provision the bootstrap password file securely and remove access to it after the first successful boot.
- Disabled-account and role changes take effect on the next protected request, at the cost of one indexed staff lookup per request.
- Rate-limit state is local to the installation database. A reverse proxy or upstream control may add broader network throttling but cannot weaken application authentication or audit.
- Password reset email, MFA, OIDC, SSO, multi-role grants, and customer authentication remain out of scope.
