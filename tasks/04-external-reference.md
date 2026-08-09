# Codex Brief 04 — External System and External Reference

## Goal

상담 티켓을 쇼핑몰 주문·결제·환불·회원·운영 객체와 안전하게 연결하고 Agent Workspace에서 맥락과 deep link를 본다.

## Required decisions

D-015

## In scope

- ExternalSystem admin definition
- ExternalReference model and API
- object type/external ID/display label
- HTTPS and host allowlist validation
- bounded metadata snapshot
- staff/platform projections
- create/remove audit
- ticket sidebar reference panel

## Out of scope

- backend fetch of external URL
- continuous synchronization
- arbitrary JSON payload storage
- iframe app

## Acceptance

- safe link renders and opens correctly;
- forbidden URL/host/scheme rejected;
- backend performs no fetch;
- duplicate-on-ticket prevented;
- same external object can link to multiple tickets when allowed;
- metadata limits enforced;
- all changes audited.

## Gates

EXT-001 through EXT-004
