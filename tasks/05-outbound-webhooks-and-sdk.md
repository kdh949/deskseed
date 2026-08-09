# Codex Brief 05 — Signed Webhooks, Incremental Export, and Generated SDKs

## Goal

외부 자동화와 시스템이 Deskseed 변경을 중복에 안전하게 수신하고, 세 언어 SDK로 같은 API 계약을 사용할 수 있다.

## Required decisions

D-011, D-024, D-025

## In scope

- versioned integration event envelope
- WebhookSubscription/Delivery/Attempt
- HMAC signature, timestamp, secret rotation
- retry/dead-letter/manual replay
- SSRF endpoint policy
- cursor incremental export
- OpenAPI completion
- TypeScript/Python/JVM SDK generation config
- smoke tests and examples
- n8n/Workato generic webhook examples

## Out of scope

- Kafka
- provider marketplace connector
- Agent App SDK
- arbitrary payload templates before canonical events stabilize

## Acceptance

- signature tamper/expiry tests;
- timeout/429/5xx retry and duplicate event test;
- dead-letter/replay with actor/reason;
- endpoint SSRF boundary;
- cursor resume/duplicate/tombstone behavior;
- reproducible SDK generation;
- all three SDK smoke tests.

## Gates

WH-001 through WH-005, EXP-001, EXP-002, SDK-001 through SDK-003
