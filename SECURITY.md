# Security policy

Deskseed is a portfolio release candidate supported only for local or private-network use. Public production deployment is not supported.

Customer ticket grants have server-enforced 30-day expiry and revocation fields. Staff authentication uses BCrypt password hashes, server-side sessions, CSRF protection, idle and absolute session expiry, and database-backed login throttling.

Do not publicly deploy the anonymous request flow until email ownership verification, customer grant reissue and revocation workflows, layered anonymous and ingress abuse controls, and managed secret, TLS, and monitoring controls are provided. Password reset, MFA, and SSO are not implemented.

Never submit a public issue containing real customer data, access tokens, credentials, or vulnerability exploit details. Until a private disclosure channel is established, contact the repository owner privately.
