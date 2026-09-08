# 조건부 폼과 동의를 포함한 고객 문의 접수

## 사용자 시나리오
관리자가 발행한 조건부 문의 폼을 고객이 입력하면 서버가 표시/필수 조건을 판정한다. 최종 접수는 폼 버전과 typed 값, 현재 동의를 저장하며 응답 유실 후 같은 요청을 다시 제출해도 문의를 중복 생성하지 않는다. 상담사 편집·태그·상태·View는 다음 수직 슬라이스다.

## 계약과 결정
REQ-CFG-014, REQ-CONSENT-002; CFG-001~006, CONSENT-002, TKT-001/002/006, CHG-001, FILE-001/003/004/006, DOC-001; ADR 0041, D-059와 docs/56 계약을 따른다. 기존 HTTP request shape와 계획된 CreateCustomerRequest의 차이를 해소하고 JSON/multipart를 하나의 command로 처리한다.

## 구현 경계
- 공개 candidate projection은 CUSTOMER_REQUEST 고정이고 machineKey 입력을 기존 condition registry의 field.UUID fact로 매핑한다. 내부 티켓 종류/필드 존재는 노출하지 않는다.
- published form version에 customer semantic snapshot을 추가한다. label/description은 현재 copy로 조회한다. 기존 발행 버전은 migration 시점 catalog로 명시적 backfill하며 과거 UI/정책의 원본 복원을 주장하지 않는다.
- 티켓 단위 form binding과 typed EAV 값을 최초 PUBLIC comment와 같은 transaction/한 TicketAudit에 저장한다.
- 최초 요청은 identity별 keyed clientCommand digest + canonical payload/attachment manifest digest를 7일 보존한다. 동일 요청은 같은 논리 결과와 새 access grant, 다른 payload는 409이며 raw command ID/token/본문은 receipt에 저장하지 않는다.
- multipart는 server-generated planned customer UUID로 격리 업로드 후 최종 transaction에서 고객 생성/폼·동의 재검증/티켓 생성한다. 외부 I/O는 ticket transaction 밖이다.
- 기존 세션/CSRF/expected staff actor, 공개/내부 projection, active group membership, 감사 fail-closed를 유지한다.
- 추가 메시징/검색/워크플로 인프라나 범용 폼 엔진을 만들지 않는다.

## 검증 계획
조건부 필드·잘못된 타입·unknown/staff-only key·숨김/readonly 삭제·stale form·snapshot/copy 차이·exact replay/conflict/concurrency·multipart rollback·단일 audit/메일·권한 회귀를 PostgreSQL로 검증한다. 양쪽 앱의 현재 Storybook 계약과 unit/typecheck/build/a11y/320·390px 검증을 수행한다. Migration, OpenAPI 및 추적 문서는 함께 갱신한다.

## 결과와 검증

- 공개 폼 조회와 후보값 판정, typed 값·불변 폼 binding, 동의 acceptance/security audit, JSON/multipart command와 UI를 연결했다. 등록된 고객은 세션 identity를 사용한다.
- 고객/티켓/동의/receipt/감사/메일 intent는 한 최종 transaction이다. attachment byte upload는 그 밖에서 수행하며 실패한 준비 단계에서 고객 row를 생성하지 않는다. snapshot/copy 조회는 필드별 N+1 없이 묶는다.
- Passed: PostgreSQL 폼·접수·첨부·rate limit 회귀 및 최종 configuration 12 / API 5 / architecture 1 tests (철회/동의 감사 rollback 포함), runtime OpenAPI parity와 architecture; customer unit 62 / staff unit 214, 양 앱 typecheck/build, Storybook MCP customer 43 / staff 99와 a11y, docs/lint/boundaries.
- 1280/390/320px 실제 조건부 환불 폼의 가로 넘침 없음 및 모바일 육안 확인. 운영 부하/EXPLAIN 측정, 실제 백엔드 browser E2E, 배포는 Not run이다.
- V87은 기존 published 버전을 migration 시점 catalog로 backfill한다. 원본 과거 정책/문구 복원을 주장하지 않는다. additive schema의 rollback은 구버전 app 복귀보다 forward repair를 우선한다.
- 미출시 API의 기존 평면 requester/privacyConsent를 새 계약으로 교체했다. 구클라이언트 호환을 제공하지 않으며 양 앱을 함께 배포해야 한다. receipt는 7일 보존하고 동작 중인 fingerprint key 교체는 기존 receipt 식별을 끊으므로 이 기간의 키를 유지해야 한다.
- 후속: 상담사 입력·태그/사용자 상태·View 조건 및 자동화. 외부 인프라/범용 폼 엔진을 추가하지 않았다.
