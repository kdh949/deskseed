# P20 — 관리자 최초 답변 SLA 집계 의미와 필터

## Goal

관리자가 현재 최초 답변 SLA 상태의 집계 범위와 달성률 분모를 이해하고, 기존 정책·우선순위 필터로 같은 범위의 수치를 조회한다. UX 감사 ADM-F08을 해결하는 하나의 수직 슬라이스다.

## Decision and source references

- REQ-SLA-001, REQ-UI-005; 기존 요구 상태는 변경하지 않는다.
- D-034/044/050/061, Accepted ADR 0023/0032/0044. 결정 변경 없음.
- `docs/16`, `docs/21`, `docs/28~31`, `docs/33`, `docs/39~40`, `docs/44`, `docs/51~53`.
- API: `getFirstReplySlaAnalytics`, `listFirstReplySlaPolicies`.
- Gates: SLA-008, ANA-004, UI-002/004/005/006의 이 변경에 해당하는 표시·필터·상태·접근성 범위.

## Actor and source

- STAFF / ADMIN_UI, `/admin/business-rules/sla`.
- 화면 진입은 기존 ADMIN 경계이며 API의 기존 ADMIN/AGENT 읽기 권한은 유지한다.
- 기존 staff session 및 expected-actor guard를 유지한다. 새 command·scope·감사 이벤트는 없다.

## Product and UX contract

- 기존 `policyId`/`priority` 필터를 사용하며 자유 검색어·날짜 범위·신규 분석 endpoint는 추가하지 않는다.
- 서버 수치는 전체 기간의 티켓별 현재 최초 답변 상태다. 시간 만료 ACTIVE의 BREACHED 반영은 서버가 소유한다.
- 달성률은 달성/(달성+위반)이고 진행·정지·취소·정책 없음은 분모에서 제외한다.
- 취소 수·서버 분모·계산 버전·자릿수 구분을 표시한다. 분모 0은 데이터 없음으로 표시한다.
- query의 `dataUpdatedAt`은 마지막 성공 조회 시각으로 표시하며 서버 집계 갱신 시각으로 설명하지 않는다.
- 필터 선택과 정책 편집 대상 선택은 독립적이다. 필터와 읽기 실패·재시도는 키보드로 조작 가능해야 한다.
- 새 범위를 조회할 때 이전 범위의 숫자를 새 범위처럼 표시하지 않는다. loading/error/denied/empty 상태를 명확히 제공한다.
- 직원 앱의 기존 공개 디자인 시스템 계약을 문서 MCP로 확인한 뒤 사용한다. 고객 앱의 코드·토큰은 가져오지 않는다.
- 시각 fixture는 deterministic data/clock과 1280/1440/1920 기준을 사용한다.

## In scope / out of scope

- In: 기존 query parameter를 사용하는 클라이언트, 관리자 집계 필터/설명/메타데이터, 관련 unit·Storybook·페이지 검증과 근거 문서.
- Out: 날짜 cohort, 신규 API/schema/DB migration, SLA 계산·scanner·목표·정책 mutation 변경, 티켓 drill-down·정책 버전 필터 추가, 실서비스 데이터 변경, 자동 병합/배포.

## Invariants and failure semantics

- canonical target/fact와 서버 달성률을 재계산·변경하지 않는다. UI는 정의를 설명하고 서버 응답을 표시한다.
- 읽기만 추가하므로 ticket/admin mutation transaction·idempotency·outbox에 변화가 없다.
- query key에 정책/우선순위를 포함하여 서로 다른 범위의 캐시를 분리한다.
- 권한 거부는 데이터 없음으로 숨기지 않는다. 조회 실패의 재시도는 현재 필터만 다시 요청한다.
- 외부 I/O·새 감사 이벤트·sensitive content reveal은 없다.

## Data and privacy

- 정책 ID와 정해진 우선순위 enum, 집계 수·계산 버전만 다룬다.
- 자유 검색어·고객/직원 개인정보·secret·티켓 본문은 요청이나 새 로그에 추가하지 않는다.
- retention/export/webhook 노출 변화 없음.

## Acceptance scenarios

1. Given 기존 전체 집계, When 화면 조회, Then 기간 미적용 현재 상태·6개 상태·분모·제외 상태·계산 버전·마지막 조회를 정확히 보여 준다.
2. Given 정책/우선순위 선택, When 필터 변경, Then Core의 기존 parameter를 보내고 해당 query 범위 숫자만 표시한다.
3. Given 분모 0, Then 달성률을 0%로 오해시키지 않고 데이터 없음으로 표시한다.
4. Given 조회 실패/403, Then 정책 편집은 유지하며 집계 영역에 정확한 상태·현재 범위 재시도를 제공한다.
5. Given 정책 편집 대상 선택/새 버전 저장, Then 집계 필터는 임의로 바뀌지 않고 기존 invalidate prefix로 관련 집계를 갱신한다.
6. Given 키보드/좁은 데스크톱/긴 정책명, Then 필터·설명·지표가 겹치지 않고 초점이 보인다.

## Validation

- 구현 후 focused client/page unit tests, typecheck, lint, design-system boundaries, Core contract check.
- Storybook MCP: instructions/docs → focused story tests → changed stories → previews. broad 영향은 full story tests.
- 해당 관리자 route 또는 페이지 수준 Playwright 검증 및 시각 확인.
- SLA-008/ANA-004의 backend 재계산·drill-down 전체 gate는 변경 범위 밖이며 통과로 주장하지 않는다.

## Compatibility and migration

- 기존 FROZEN OpenAPI의 query parameter 사용만 추가한다. wire contract·DB 변경 없음.
- 기존 무인자 client 호출은 전체 조회로 유지한다. frontend revert로 되돌릴 수 있다.
- 새로운 성능 개선 수치를 주장하지 않는다. 필터 조작마다 기존 집계 요청 1회가 발생한다.

## Human explanation

날짜 통계를 새로 발명하지 않고, 이미 서버가 반환하는 분모·제외 상태와 필터를 UI에 정확히 연결한다. 조회 시각은 서버 집계의 생성 시각을 보증하지 않는다.

## Completion report

- 변경: 정책/우선순위 집계 필터, 현재 티켓 상태·기간 미적용 설명, 취소 수·달성률 분모·계산 버전·자릿수 구분·마지막 성공 조회를 연결했다. 분모 0과 로딩/403/실패·재시도를 구분하고 정책 편집 선택은 독립적으로 유지한다.
- 디자인 시스템: MCP로 `ScreenState`, `RetryButton`, `Notification`, `DsButton`, `DsSelect` 문서를 확인했다. 기존 관리자 native label/select 스타일과 문서화된 상태/재시도 컴포넌트를 조합했으며 공개 DS API나 토큰은 추가하지 않았다.
- 통과: 직원 단위 테스트 43파일/258개, 타입 검사, 직원 production build, 변경 파일 ESLint, 디자인 시스템 경계 4개 및 import 검사, 기존 P1 OpenAPI fixture 검사. P1 검사는 SLA endpoint의 새 계약 검증으로 주장하지 않는다.
- 통과: Storybook MCP focused SLA 7개 및 전체 직원 61파일/294개 (`a11y: true`), `get-changed-stories` 및 runtime consumer `get-stories-by-component`, 아래 5개 preview.
- 통과: Playwright Chromium 관리자 라우트 3개(1280×800, 1440×900, 1920×1080). 실제 필터 요청·분모·고정 조회 시간·키보드 이동·가로 overflow를 검사하고 1440에서 axe WCAG 2.2 AA 태그 위반 0건을 확인했다. 세 크기의 PNG를 직접 열어 확인했다.
- 시각 증거: `frontend/test-results/admin-sla-analytics-*/sla-analytics-*.png`에 로컬 캡처를 남긴다. 기존 Queue/Workspace 픽셀 기준선은 변경하지 않았다.
- 미실행: 실제 backend/운영 데이터·SLA 계산 scanner 통합 테스트, Firefox/WebKit, Linux 픽셀 기준선, 고객 앱 build. SLA-008/ANA-004/UI-006의 전체 gate 통과를 주장하지 않는다.
- 기존 직원 build의 500 kB 초과 chunk 경고는 유지한다. 성능 개선 수치나 운영 검증 결과를 주장하지 않는다.
- API/DB migration, 권한, actor/source, 감사 event, mutation transaction/concurrency/idempotency/retry, retention 의미는 변경하지 않았다. 읽기 실패의 사용자 재시도만 추가했다. frontend revert로 복구 가능하다.
- 새 날짜 cohort·드릴다운·정책 버전 필터는 구현하지 않았다. 서버의 현재 상태 집계를 정확히 해석하는 표시 개선이라는 trade-off를 유지한다.

### Storybook previews

- [전체 변경 목록](http://localhost:6008/?statuses=affected;modified;new)
- [정책/우선순위 필터](http://localhost:6008/?path=/story/06-admin-admin-first-reply-sla-page--analytics-filters)
- [분모 0](http://localhost:6008/?path=/story/06-admin-admin-first-reply-sla-page--empty)
- [로딩](http://localhost:6008/?path=/story/06-admin-admin-first-reply-sla-page--analytics-loading)
- [조회 실패](http://localhost:6008/?path=/story/06-admin-admin-first-reply-sla-page--analytics-error)
- [권한 거절](http://localhost:6008/?path=/story/06-admin-admin-first-reply-sla-page--analytics-denied)
