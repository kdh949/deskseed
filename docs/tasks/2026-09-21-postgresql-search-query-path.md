# PostgreSQL 상담사 검색 query-path 최적화

상태: 구현·검증 중

## 사용자 시나리오와 범위

- Actor: 인증된 active `STAFF`, source `AGENT_UI`.
- 사용자는 `/agent/search`에서 검색 비용을 알지 않아도 업무에 사용할 첫 결과를 빠르게 확인한다.
- 대상 operation은 `POST /api/v1/agent/search` (`searchAgentWorkspace`)다.
- 같은 개인 스테이징 PostgreSQL 한 개만 사용한다. DB 복제·신규 검색엔진·Redis/Kafka·pool/전역 DB 설정 변경은 제외한다.
- 검색 결과의 정확성·순위는 보호된 합성 corpus와 명시된 정렬 계약 안에서만 평가한다.

## 추적 항목

- Requirements: `REQ-SRCH-001`, `REQ-PERM-001`, `REQ-AUD-003`, `REQ-AUD-004`, `REQ-AUD-005`, `REQ-AUD-008`, `REQ-PERF-001`, `REQ-PERF-002`, `REQ-OPS-002`.
- Decisions: `D-008`, `D-018`, `D-033`, `D-036`, `D-041`, `D-045`, `D-048`, `D-064`, `D-065`, `D-067`, `D-068`, `D-069`, `D-070`.
- ADR: 0018, 0025, 0030, 0033, 0036, 0037, 0047, 0048, 0050, 0051, 0052, 0053.
- Gates: `ARCH-001~004`, `ACC-002/003/004/007`, `SEARCH-AUD-001/002`, `PERM-001`, `PERF-001/003`, `OPS-004`, `DOC-001`.

## 데이터·권한·감사 경계

- 후보 생성부터 STAFF 전용 PUBLIC/INTERNAL projection을 사용하고 customer surface를 변경하지 않는다.
- 현재 `ALL_TICKETS` 읽기 정책, active staff 확인, 현재 티켓 필터와 삭제 상태를 유지한다.
- 성공 응답 전 `SEARCH_EXECUTED`와 반환 membership 감사를 commit한다. 감사 실패는 fail closed다.
- 검색 원문은 request body와 보호된 ciphertext/HMAC 정책에만 존재한다. ordinary log, metric label, URL, 증거 문서에는 남기지 않는다.

## 수직 슬라이스

1. S0: 동일 corpus/seed/계정/개인 스테이징에서 score 정렬과 updatedAt 정렬을 고유 run ID로 비교한다.
2. S1: 십진 정수 입력을 본문 substring 후보·점수 정렬과 분리하되 active/CLOSED, 권한, 필터, 감사 의미를 보존한다.
3. S2: S0가 입증한 경우 updatedAt 경로에서 불필요한 score 계산을 제거하고 실제 화면 기본 정렬을 계약과 함께 변경한다. score 정렬은 명시적 선택으로 유지한다.
4. S3: S2가 2초 목표를 충족하지 못하면 별도 ADR 아래 후보 projection/점진적 응답을 검토한다. 미검증 후보를 전역 관련도 결과로 표시하지 않는다.

## 실패 의미와 rollback

- 5초 statement budget 초과는 기존 `/problems/agent-search-too-broad`를 유지하되 성공으로 계산하지 않는다.
- INTERNAL/권한/삭제/감사 회귀, 재시작/OOM, pool timeout, 지속 p95 2초 초과 시 상승을 중단한다.
- application rollback은 이전 정확한 SHA `0459830f81cdeb4b4c6e7b04d7767ea984499015`다. additive schema가 생기면 삭제하지 않고 별도 cleanup 승인을 기다린다.

## 검증

- focused SQL plan, cursor, PostgreSQL integration, audit failure tests.
- `make docs-check`, `git diff --check`.
- 실제 서버 SHA/Flyway/health/restart와 1 VU fixed corpus smoke.
- Grafana에서 도착 부하 → 사용자 영향 → 최초 포화 자원 → 회복 순으로 고정 UTC 증거를 보존한다.
- personal-staging에서는 `EXPLAIN ANALYZE`를 실행하지 않는다. 동일 규모 load DB가 없으면 executor-node 원인은 확정하지 않는다.
