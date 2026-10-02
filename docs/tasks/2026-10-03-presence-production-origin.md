# Production 함께 작업 연결의 origin 설정

## Goal

운영자가 지정한 실제 Deskseed 브라우저 origin에서 상담사의 함께 작업 WebSocket 연결이 시작되고, 다른 origin은 기존대로 거절된다.

## Decision and source references

- REQ-COL-002; D-039/D-061, Accepted ADR 0040의 기존 extension 경계.
- docs/31의 Query cache/stale 알림, docs/33의 staff read boundary, docs/39의 기존 계약.
- Gate: PERM-001의 staff read/authorization 해당 범위, OPS-001 production wiring, DOC-001. CHN-012 부하 검증은 수행하지 않는다.
- `/ws/agent/collaboration`의 기존 protocol·권한·message/heartbeat 계약은 변경하지 않는다. 새 API 계약 PR이 필요한 의미 변경은 없다.

## Actor and source

기존 STAFF session과 ticket read authorization을 유지한다. origin은 actor를 선택하거나 권한을 부여하지 않는다. HTTP command context와 TicketAudit은 변경하지 않는다.

## Product and UX contract

감사 AG-F14: 실제 production 컨테이너에는 별도 collaboration origin 설정이 없고 기존 HTTP CORS allowlist에 공개 origin만 지정되어 있었다. backend의 WebSocket 설정 기본값은 `http://localhost:5173`이므로 실제 공개 origin을 거절한다. 2026-10-02 이후 제한된 frontend 로그 조사에서 해당 WebSocket 경로의 403을 892건 확인했다. 각 403이 모두 같은 원인이라는 주장은 하지 않는다.

## In scope

production profile에서 WebSocket allowed-origins를 이미 명시된 `deskseed.cors.allowed-origins`에 연결한다. 실제 production Spring context의 interceptor를 사용해 허용/다른/로컬 origin을 검증한다. 기존 authenticated socket의 ticket access·logout·rate limit 회귀를 실행한다.

## Out of scope

프록시/WAF/서버 설정 변경·배포, client 재연결 UI, presence lock, 멀티 인스턴스 broker, 새 capability API는 포함하지 않는다. REQ-COL-002 전체 완료로 승격하지 않는다.

## Invariants and failure semantics

- 명시적 non-wildcard origin allowlist, staff session, ticket read scope를 모두 유지한다.
- presence는 advisory이며 command version/transaction을 대체하지 않는다.
- heartbeat·TTL·disconnect·rate/size cap, post-commit stale hint는 기존 그대로다.
- background presence는 semantic TICKET_VIEWED를 생성하지 않는다. 새 audit/event/retention은 없다.

## Data and privacy / threats

새 payload·PII·secret·로그가 없고 WebSocket 권한 완화가 없다. cookie session을 이용하는 교차 origin 연결을 계속 거절한다. 원격 진단은 환경변수 존재/공개 origin 일치와 HTTP 상태 수만 읽었으며 쿠키·본문을 출력하지 않았다.

## Acceptance scenarios

1. Given production의 HTTP allowlist에 실제 공개 origin, When 같은 origin으로 handshake를 검사하면, Then origin 검사가 허용된다. 인증은 별도 필수다.
2. Given 같은 설정, When localhost 또는 다른 origin이면, Then 403이다.
3. Given 허용 origin의 인증 staff, When 권한 있는 티켓을 구독하면, Then presence snapshot이 오고 logout하면 연결이 폐기된다.

## Validation

- `./gradlew integrationTest --tests '*PersonalStagingObservabilityRuntimeIntegrationTest'`
- `./gradlew fastTest --tests '*StaffCollaborationOriginInterceptorTest'`
- `./gradlew slowTest --tests '*StaffCollaborationWebSocketIntegrationTest'`
- `make docs-check`, `git diff --check`.

## Compatibility and migration

DB/API/protocol/migration은 없다. 기존 production HTTP allowlist를 재사용하여 운영 변수 추가를 피한다. 로컬 profile은 기존 localhost 기본값을 유지한다. rollback은 profile 매핑 커밋을 되돌리면 된다. 서버 원격 변경이나 자동 배포는 하지 않는다.

## Human explanation / completion

새 proxy나 재연결 엔진을 만들지 않고 실제 거절을 유발하는 누락된 profile 매핑만 수정한다. 로컬 검증과 배포 서비스 복구는 구분한다. 수정 전 production origin 회귀가 실패했고 수정 후 production profile 2개, origin fast test 1개, 인증 WebSocket slow tests 3개가 통과했다. 문서·diff 검증을 수행했다. 실제 배포·브라우저 2명 동시 연결·부하 검증은 미실행이다. 성능과 운영 복구를 주장하지 않는다.
