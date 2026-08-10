# Next Decision Gates

Status: **Non-blocking list after the 2026-08-10 decisions**

다음 값은 현재 구현을 막지 않지만, 해당 기능의 다음 세로 기능을 열기 전에 결정한다.

| Priority | Capability | Decide | Safe current default |
|---:|---|---|---|
| 1 | Cross-group write | 모든 상담사가 모든 티켓을 수정할지, group/assignee에 한정할지 | global read; write only assignee or active ticket-group member |
| 2 | First Reply targets | LOW/NORMAL/HIGH/URGENT의 실제 목표 시간 | no active policy until admin enters values |
| 3 | Audit reveal hardening | 원문 공개에 최근 로그인만 필요한지 MFA까지 필요한지 | permission + reason + self-audit; MFA when available |
| 4 | Raw-query retention | 30일을 유지할지 조직별 기간을 정할지 | 30 days, configurable |
| 5 | Production email | SMTP/API provider, bounce webhook, sending domain | provider-neutral port; Mailpit only in development |
| 6 | Inbound email | provider boundary, mailbox address, threading and spam policy | not implemented |
| 7 | Webhook v1 | event allowlist, PII fields, replay role | minimal metadata, at-least-once |
| 8 | SDK distribution | package names and registries | generated artifacts in repository first |
| 9 | Customer solved workflow | solved ticket reply/reopen window | 14 days proposed |
| 10 | Multiple SLA schedules | one default schedule only or ticket/group selection | one default schedule; model supports versioned multiple schedules |

## One-line answers that unlock the next pack

```text
Cross-group write: ALL / GROUP_OR_ASSIGNEE / ASSIGNEE_ONLY
SLA targets: LOW=?, NORMAL=?, HIGH=?, URGENT=? business minutes
Raw query retention: ? days
Audit reveal MFA: REQUIRED / OPTIONAL / LATER
Production email provider: SMTP_GENERIC / SES / SENDGRID / OTHER
Inbound email v1: YES / NO
```
