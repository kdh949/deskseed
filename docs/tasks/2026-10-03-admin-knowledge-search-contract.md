# P17 — 관리자 지식 문서 제목 검색 계약

## Goal and authority

ADMIN이 초안의 저장된 제목으로 전체 지식 문서를 찾고 기존 상태·섹션·공개 대상 조건과 keyset 페이지를 함께 사용한다. ADM-F01/02/04의 KB 동선 및 draft 보호는 후속 수직 구현에서 연결한다. 이 PR은 계약만 정의하며 runtime/DDL/UI는 구현하지 않는다.

REQ-KB-001, REQ-AUD-004, REQ-UI-005; D-018/019/020/048/061, Accepted ADR-0018/0033/0037/0044; docs19/21/26/31/32/33/34/39/44/50/52/55 및 CODEX_TASK_TEMPLATE의 경계를 따른다. 구현 gate는 KB-001, ADMIN-KB-SEARCH-001, UI-002/004/005다.

## Wire and actor

- `searchAdminKnowledgeArticles`: `POST /api/v1/admin/knowledge/articles/search`, Active ADMIN/STAFF session, ADMIN_UI, CSRF/expected staff actor. 일반 상담사·감사자·고객·Platform에 노출하지 않는다.
- query 1~254자, trim 후 비어 있거나 ISO control을 포함하면 400. 제목의 대소문자 구분 없는 literal substring이며 %, _, 역슬래시는 wildcard가 아니다. 과거 revision·본문·slug는 검색하지 않는다.
- lifecycle/sectionId/audience의 좁은 allowlist AND 조건을 기존 목록과 동일하게 적용한다. ADMIN이 보는 전체 문서 범위이며 sectionId 미일치는 빈 결과다. 새 permission/filter 엔진은 없다.
- 기존 createdAt DESC/id DESC keyset, limit 50, filter/query-bound cursor를 사용한다. query binding은 기존 cursor signing key에 목적을 분리한 HMAC이며 raw query나 비키드 query hash를 cursor에 넣지 않는다. signing key 교체 시에도 해당 key ID로 검증하며 손상·조건 불일치는 400이다.
- 검색 응답 resultCount는 cursor 앞뒤를 포함한 전체 EXACT 수다. count/items를 REPEATABLE_READ 한 snapshot에서 읽고 그 값으로 감사한다. 페이지 사이 mutation으로 count는 달라질 수 있다. 전체 count의 성능을 측정하기 전 인덱스나 엔진을 추가하지 않는다.
- 새 검색 operation은 구현되지 않았으므로 FROZEN을 붙이지 않는다. 기존 `listKnowledgeArticles`는 FROZEN을 유지하고 `items.latestRevision`의 예정 확장을 BLUEPRINT_READY metadata로 표시한다. 계약 schema에서 optional인 latestRevision을 구현 PR에서 required로 승격한다. 최신 저장 revision의 title/summary만 포함하며 currentPublishedRevision 소비자의 의미와 상세 document 계약은 바꾸지 않는다.

## Audit and privacy

검색마다 typed `ADMIN_KNOWLEDGE_SEARCH_EXECUTED`를 access/search ledger에 기록한다. P15의 AccessAuditWriter, SearchQueryProtector, 보호 ciphertext/keyed fingerprint, expiry/append-only/retention을 재사용하되 별도 typed KB scope를 둔다. 정규화된 필터는 lifecycle/sectionId/audience/cursor-present/pageSize만이며 cursor·제목·본문·원문 query를 metadata에 넣지 않는다. source ADMIN_UI, STAFF_SESSION, session fingerprint/request/correlation/interaction context와 전체 resultCount EXACT를 사용한다.

기존 목록의 KNOWLEDGE_ARTICLE_LISTED와 검색 event를 이중 기록하지 않는다. 결과를 반환하기 전에 검색 보호·필수 감사와 read transaction을 원자적으로 완료하며 실패하면 503이다. 이 명령은 문서·발행·검색색인을 변경하지 않고 outbox/network I/O를 만들지 않는다. Idempotency-Key/If-Match는 읽기 검색에 필요하지 않으며 재시도는 별도 검색 감사다.

routine Audit Explorer에는 [PROTECTED], keyed fingerprint, 안전한 필터/정렬/EXACT count만 보여 주고 protectedContentAvailable=false, openedActivities=[], originSearchActivityId=null을 유지한다. 기존 exact query reveal을 KB로 확대하지 않는다. query는 JSON body/일시 메모리에만 존재하고 URL·ordinary log·APM/trace·React Query key·브라우저 저장소에 기록하지 않는다.

## Migration, regressions and delivery

후속 구현의 `V100__admin_knowledge_search_audit.sql`은 P15 V99 CHECK 허용 목록/shape에 새 명시적 action만 확장한다. 새 generic table/search infrastructure·seed·데이터 backfill은 없다. append-only 기록을 삭제하는 rollback은 하지 않으며 앱 rollback 시 DDL 확장은 유지 가능하다.

ADMIN/AGENT/AUDITOR/익명 권한, CSRF/actor mismatch, 최신 revision 대 과거 발행 제목, literal wildcard, 다중 필터, 0건·51개 이상 keyset/cursor 조건 결합, raw query/canonical body leak, 같은 snapshot의 count, 감사/보호 실패 503/rollback, safe projection/reveal 거부, GET publishedRevision 호환, UI dirty/focus/상태 및 캐시 privacy를 검증한다. 기존 GET 목록은 계속 KNOWLEDGE_ARTICLE_LISTED를 남긴다.

## Validation

계약 PR은 OpenAPI bundle 재생성, docs-check, diff whitespace를 확인한다. Backend/UI/Storybook/운영 검증은 이 PR에서 미실행이며 FROZEN 승격 근거가 아니다.
