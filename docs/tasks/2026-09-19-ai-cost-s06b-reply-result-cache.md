# AI 비용 절감 S06b — 답변 완료 결과 정확 일치 캐시

## Goal

같은 상담사·티켓의 schema v2 답변 요청이 동일한 PUBLIC 입력과 생성 구성뿐 아니라 Backend 공개 KB 코퍼스와 AI 검색 인덱스의 완전 게시 세대까지 정확히 같을 때만, 현재 권한과 citation을 다시 검증한 뒤 유효한 `SUCCEEDED` 결과를 새 logical job에 재암호화해 query embedding·retrieval·generation 없이 재사용한다.

## Decision and source references

- Decision IDs: D-003, D-008, D-009, D-018, D-021, D-054, D-066.
- Accepted ADRs: 0003, 0008, 0009, 0018, 0025, 0049.
- Requirements: REQ-AI-002, REQ-AI-003, REQ-AI-004, REQ-AI-005.
- Plan: `docs/ai-v1/COST_REDUCTION_PLAN.md` S06 and sections 8.1~8.2, 11, 12.
- API operations: Backend `readAiPublicKnowledgeManifest`, `getInternalAiPolicy`, `getInternalAiRequestContext`, `getInternalAiRequestContextRevision`, `authorizeAiResultKnowledgeCitations`; AI internal `accept_job_internal_v1_jobs_post`, `get_job_internal_v1_jobs__job_id__get`.
- Verification gates: AI-API-001, AI-SRC-001, AI-LIFE-001, AI-KB-001, AI-REPLY-001, AI-RET-001, AI-OBS-001.
- 선행 구현: S05 input revision PR #192, S06a summary/triage cache PR #194. 이 slice는 Accepted decision이나 외부 Staff API/UI 계약을 바꾸지 않는다.

## Actor, source and resource boundary

- 요청 actor는 기존 active `STAFF`/`AGENT_UI`, source read actor는 registered job에 고정된 `INTEGRATION_CLIENT`/`AI_SERVICE`다.
- cache scope는 server-owned workspace, requester staff, ticket, `ticket.reply_draft`로 고정한다. caller는 cache key, corpus revision, index generation, TTL 또는 원 결과를 선택하지 않는다.
- cache lookup 전 Backend source context와 policy를 정상 호출해 current owner binding, active staff, ticket read capability, cancellation, deadline, feature enablement, `contextRevision`, `aiInputRevision`을 다시 검증한다.
- cache hit 후보의 citation은 결과 commit 전에 Backend의 exact current PUBLIC revision·상위 공개성으로 다시 인가한다. 과거 authorization, cache key 또는 origin audit을 현재 권한 근거로 재사용하지 않는다.
- Backend policy의 공개 코퍼스 리비전과 AI DB의 게시 인덱스 세대가 서로 결합된 경우에만 답변 cache key를 만들 수 있다. 어느 한 값이 없거나 불일치하면 fail-closed miss다.

## Canonical PUBLIC corpus revision

- Backend migration V96은 singleton `ai_public_knowledge_corpus_state`를 만들고 양의 `revision`을 소유한다. 초기값은 migration 시점의 현재 코퍼스를 대표하는 1이다.
- `KnowledgeAdministration`이 PUBLIC 검색 가능 집합이나 canonical source metadata를 바꿀 수 있는 transaction에서 revision을 한 번 증가시킨다. 범위는 category/section 생성·수정, article publish/unpublish/archive, published article audience 교체다.
- draft 생성·draft revision 수정·review 이동처럼 현재 PUBLIC 집합과 current published revision을 바꾸지 않는 command는 revision을 올리지 않는다.
- category/section 수정은 상태, 계층, slug/title 같은 향후 retrieval/canonical citation 영향을 안전하게 포괄하기 위해 변경 종류를 세분하지 않고 보수적으로 revision을 증가시킨다.
- revision 증가는 knowledge mutation·change audit·domain event·AI index outbox와 같은 Backend transaction에 commit/rollback된다. 외부 network I/O는 수행하지 않는다.
- manifest snapshot은 생성 transaction에서 현재 revision을 함께 고정한다. 모든 page는 snapshot에 저장된 동일 `canonicalPublicCorpusRevision`을 반환하며 caller가 값을 공급하지 않는다.
- AI policy는 현재 `canonicalPublicCorpusRevision`을 body-free metadata로 반환한다. additive reader 호환을 위해 AI decoder는 필드를 optional로 먼저 수용하지만, null/누락은 reply cache와 generation publish에 사용할 수 없다.

## Published AI index generation

- AI migration 009는 reconciliation run에 `canonical_corpus_revision`과 publication 상태를 추가하고, workspace별 게시 generation을 별도 table에 저장한다.
- manifest page 수집 완료는 scan 완료일 뿐 index 게시 완료가 아니다. 기존 `SUCCEEDED`는 `SCANNED`로 의미를 분리하거나 migration 후 새 상태 전이로 대체하며, 마지막 page를 읽은 직후에는 generation을 발행하지 않는다.
- 한 run의 generation은 다음 조건을 한 transaction에서 모두 검증한 뒤에만 발행한다.
  - snapshot의 모든 page가 완료되고 snapshot corpus revision이 존재한다.
  - `seen`의 article/revision/sourceVersion/publicRevision이 workspace의 `ai_kb_article_state` 및 `PUBLIC` `ai_kb_revisions`와 exact match한다.
  - 각 seen revision에 하나 이상의 현재 chunk가 존재한다.
  - snapshot에 없는 workspace `PUBLIC` revision은 이미 `DELETED`이고 chunk가 없다.
  - 해당 workspace에 현재 state와 관련된 `PENDING`, `LEASED`, `DEAD` index job이 없으며, 성공 상태와 local index가 불일치하지 않는다.
- 조건이 아직 충족되지 않으면 run은 `INDEXING`으로 남고 다음 cycle에서 재검사한다. `DEAD`/불일치/만료 snapshot은 `FAILED`가 되며 generation은 발행하지 않는다.
- 게시 시 workspace advisory lock과 monotonic generation을 사용한다. row는 generation, canonical corpus revision, source run/snapshot, published timestamp만 저장하며 본문·query·chunk content를 저장하지 않는다.
- 이후 Backend corpus revision이 증가해도 old generation은 history로 보존할 수 있으나, worker가 읽은 current policy revision과 generation row의 revision이 다르면 cache read/write를 모두 금지한다.
- index event 처리 완료 직후와 reconciliation cycle에서 publication을 재시도하되 provider/network call을 publication transaction 안에서 실행하지 않는다.

## Exact reply cache identity and eligibility

- key version은 `result-cache-reply-v1`이며 S06a와 같은 secret HMAC-SHA-256 및 length-delimited canonical encoding을 사용한다.
- canonical key 입력은 workspace, requester, ticket, feature, `aiInputRevision`, `inputPolicyVersion`, normalized language/tone, prompt digest/version, output schema version, model route version, resolved model alias, Backend policy version, AI config version, retrieval version, chunking version, canonical PUBLIC corpus revision, published index generation, context-builder version이다.
- key는 query embedding과 retrieval 전에 구성한다. PUBLIC body, raw query, citation title/url, chunk content, email/phone, raw idempotency key를 key·로그·metric label에 넣지 않는다.
- schema v2이며 paired input revision이 있는 `ticket.reply_draft` job만 eligible하다. schema v1/pre-S05 legacy job, corpus/index identity가 없는 job은 캐시를 읽거나 쓰지 않는다.
- 원 결과는 uncancelled `SUCCEEDED`, non-empty citations, ciphertext/source-map provenance 존재, result/cache expiry 전, 동일 requester/ticket이어야 한다. `NEEDS_REVIEW`, citation 없는 reply, failed/unknown/cancelled/superseded/expired result는 entry가 될 수 없다.
- TTL은 최초 생성 시각 기준 24시간과 원 result expiry 중 이른 값이다. hit는 TTL을 연장하지 않으며 hit job은 다시 cache origin이 되지 않는다.

## Cache-hit citation reauthorization

- reply hit는 두 단계로 처리한다. 첫 DB transaction에서 origin과 cache entry를 lock·검증하고 origin ciphertext를 typed `ReplyDraftResult`로 복호화하되 소비 job을 완료하지 않는다.
- DB transaction을 종료한 뒤 cached citation candidate를 Backend `authorizeAiResultKnowledgeCitations`로 보낸다. network I/O는 DB transaction 안에서 수행하지 않는다.
- Backend 반환이 원 citation과 순서·article/revision/chunk/title/url까지 exact match하지 않으면 후보를 commit하지 않고 entry를 invalid 처리한 뒤 현재 request의 normal retrieval/generation으로 miss한다.
- 두 번째 DB transaction은 origin/cache를 다시 lock하고 expiry, origin identity, result digest/provenance, generation/lease fence를 재검증한 뒤에만 소비 job UUID AAD로 새 ciphertext/nonce를 저장한다. 첫 단계와 다른 origin/result가 되면 miss한다.
- source freshness, policy, corpus/index identity는 candidate read 전과 cache commit 직전에 다시 비교한다. 중간에 어느 값이 변하면 stale candidate를 저장하지 않는다.
- cache miss 후 normal reply 경로도 generation 완료 직전에 current context/policy/corpus/index identity와 citation을 재검증하며, 최초 identity와 같을 때만 cache entry를 쓴다.

## Activation contract

- 기존 UI는 generation mode를 보내지 않으므로 S06b cache 경로도 기본 `off`다.
- S06a와 동일한 test-only mode에서만 회귀 테스트에 사용하며 production startup은 이를 거부한다. S08a가 `REUSE_OR_CREATE | NEW_CANDIDATE`를 계약화하기 전에는 운영 flag를 켜지 않는다.
- mode 생략 요청은 계속 신규 생성 의미를 유지한다. S06b 구현만 배포해 기존 답변 생성 버튼이 몰래 cache hit로 바뀌지 않는다.
- S08a 이후 `NEW_CANDIDATE`는 cache를 우회하고 `REUSE_OR_CREATE`만 유효 cache를 사용한다.

## In scope

- 이 S06b task brief.
- Backend additive migration V96, corpus revision owner, mutation transaction bump, manifest snapshot/API/policy metadata.
- AI additive migration 009, reconciliation scan/index publication 분리, fail-closed 게시 generation 조회.
- `result-cache-reply-v1` key, current corpus/index identity gate, two-phase cached reply read/commit, citation 재인가와 invalidation.
- 원 reply result 복호화 후 typed/provenance 검증, 소비 job별 재암호화, no-provider-hit 회귀 테스트.
- OpenAPI, migration, Backend/AI implementation and reusable regression tests in the code slice.

## Out of scope

- 진행 중 동일 입력 coalescing/shared execution(S07).
- 외부 generation mode, 새 후보 제한, reuse reason/cost API와 UI(S08a/S08b).
- 검색 질의/RRF/청크 변경(S09), 누적 memory(S10), model routing(S11), rewrite(S12), provider prompt cache(S13), batch embedding(S14).
- cross-requester/cross-ticket/cross-workspace reuse, sliding TTL, Redis cache, live provider, human quality evaluation, merge·배포.
- raw PUBLIC body, 감사 증빙, screenshot, one-off script, 부하 로그의 Git 커밋.

## Invariants and failure semantics

- cache key 일치는 authorization, required source access audit, cancellation, deadline, feature policy, source/context freshness, citation reauthorization을 대체하지 않는다.
- Backend knowledge mutation과 corpus revision increment는 같은 transaction이다. revision 증가 실패 시 mutation도 rollback하고, mutation 실패 시 revision만 진행하지 않는다.
- incomplete/expired manifest, index job lag/dead letter, local state/chunk 불일치, corpus revision unknown/mismatch에는 published generation을 만들지 않는다.
- cache candidate read와 final commit은 짧은 PostgreSQL transaction으로 분리한다. Backend/provider network I/O를 transaction 또는 row lock 안에서 실행하지 않는다.
- decrypt/schema/provenance/citation/expiry 불일치는 원문이나 unbounded 오류를 반환하지 않고 entry를 invalid 처리한 뒤 miss한다.
- cache hit commit은 current generation/lease epoch/status로 fence한다. fence를 잃으면 새 result를 저장하지 않는다.
- 원 job cancel/result retention purge는 해당 cache entry를 invalid 처리한다. 이미 materialize된 소비 job은 자신의 current Backend authorization/cancellation 검증을 따른다.
- cache hit의 증분 provider 비용은 0이고 origin 생성비를 소비 job에 복제하지 않는다. fake 통과나 counterfactual saved cost를 실제 모델 품질·청구 절감으로 보고하지 않는다.

## Data and privacy

- 읽기: Backend corpus revision, manifest binding, job/config/index generation metadata, origin encrypted typed result와 source-map provenance.
- 저장: corpus revision integer, snapshot/run/generation references, HMAC key, origin job reference, bounded status/timestamps, 소비 job의 새 ciphertext/nonce와 origin reference.
- 저장하지 않음: PUBLIC/INTERNAL body, prompt, raw query, chunk content, citation title/url in key or telemetry, raw key material, email/phone, Authorization/provider secret.
- result는 기존 7일 authenticated-encryption retention, cache entry는 최대 24시간, generation/reconciliation metadata는 기존 운영 metadata retention을 따른다.
- cache/generation metadata와 result는 customer API/webhook/ordinary export/Langfuse metadata에 노출하지 않는다.

## Threats changed

- stale/newly published KB omission: selected citation revision만 검사하지 않고 Backend 전체 canonical corpus revision과 완전 게시 AI generation을 key에 결합한다.
- parent visibility bypass: category/section mutation도 revision을 증가시키고 cached citation은 current Backend projection에서 다시 인가한다.
- partial index publication: manifest scan과 index publication을 분리하고 pending/dead/missing chunk 상태에서 generation을 발행하지 않는다.
- TOCTOU citation reuse: network reauthorization 후 second transaction에서 origin/result/index/fence를 재검증한다.
- cross-actor/result disclosure: requester/ticket/workspace가 key와 row validation에 모두 포함되고 current source authorization을 다시 수행한다.
- mixed deployment: migration과 optional reader를 먼저 배포하고 null/unknown corpus or generation은 miss하며 activation은 off를 유지한다.

## Acceptance scenarios

1. 같은 requester/ticket/reply의 동일 v2 input/config와 동일 published index identity 요청 두 개를 test-only mode에서 순차 실행하면 첫 job만 query embedding/generation call을 만들고 둘째는 새 job ciphertext로 같은 typed reply를 받는다.
2. 둘째 job의 ciphertext/nonce는 원 job과 다르고 각 job UUID AAD에서만 복호화된다. generatedAt과 origin expiry 상한은 보존되고 cache TTL은 늘지 않는다.
3. 새 PUBLIC article publish, current article unpublish/archive, published article audience 비공개 변경, parent category/section archive·이동·수정은 corpus revision을 같은 transaction에서 증가시켜 기존 reply cache를 miss시킨다.
4. manifest scan 직후 index job이 pending/leased/dead이거나 seen revision/chunk/state가 불일치하면 generation을 게시하지 않는다. 모든 current PUBLIC item과 deletion이 반영된 뒤에만 새 generation을 게시한다.
5. Backend current corpus revision과 AI published generation의 bound revision이 다르거나 어느 값이 null/unknown이면 reply cache read/write가 모두 발생하지 않는다.
6. cache 후보 citation 중 하나가 철회되거나 title/url/revision/chunk가 바뀌면 commit 전 재인가가 exact match하지 않아 cache miss하고 stale result를 노출하지 않는다.
7. 다른 requester, ticket, language/tone, input revision/policy, prompt/schema/model route, Backend policy, retrieval/chunking, corpus revision, index generation 또는 context builder는 miss한다.
8. schema v1/pre-S05 legacy job, summary/triage namespace 혼용, citation 없는 reply, `NEEDS_REVIEW`, failed/unknown/cancelled/superseded/expired origin은 read/write 모두 miss한다.
9. cache candidate read와 citation 재인가 사이에 origin 취소, expiry, corpus/index change 또는 consumer lease loss가 발생하면 second transaction이 fail closed하고 provider/network call을 DB lock 안에서 수행하지 않는다.
10. production에서 test-only cache mode를 켜면 startup이 실패하고 기본 off/legacy UI 요청은 기존 신규 생성 경로를 유지한다.

## Validation

- `cd backend && GRADLE_USER_HOME=./.gradle-user-home ./gradlew test` with focused corpus/manifest transaction tests.
- `cd ai && .venv/bin/ruff check src tests scripts`
- `cd ai && .venv/bin/mypy`
- `cd ai && .venv/bin/pytest -q`
- PostgreSQL-backed migration/reconciliation publication/cache/AAD/TTL/cancel/retention/concurrency tests.
- fake provider ledger에서 reply hit job query embedding/generation 0, origin cost 비복제, distinct ciphertext 확인.
- `cd ai && .venv/bin/python scripts/export_openapi.py` and generated internal OpenAPI parity.
- `cd ai && .venv/bin/python scripts/evaluate_fake.py` — contract evidence only; live quality/cost claim 금지.
- `make docs-check`
- `git diff --check`
- latest PR HEAD CI.

## Compatibility and migration

- Backend V96과 AI 009는 additive metadata/table/nullable column·constraint 확장이다. 기존 manifest consumer가 새 required response field를 받을 수 있도록 AI optional reader를 code path와 함께 배포하되 reply cache는 identity가 없으면 miss한다.
- AI result cache constraint는 기존 summary/triage namespace를 유지하면서 `result-cache-reply-v1`과 reply feature를 추가한다. 기존 entry를 재해석하거나 backfill하지 않는다.
- reader/migration/writer를 배포해도 activation은 off다. production은 S08a 전 test-only mode를 거부한다.
- rollback은 cache activation off, 신규 reply lookup/write 중지, 진행 job drain, application rollback 순서다. corpus revision·generation/cache additive metadata는 즉시 삭제하지 않고 기존 retention으로 소진한다.
- Core/Staff API와 current UI decoder에는 변경이 없다. internal AI source OpenAPI만 corpus/manifest metadata를 추가한다.

## Human explanation

답변 캐시는 같은 인용 몇 개가 아직 공개인지 확인하는 것만으로 안전하지 않다. 새로 공개된 더 적합한 문서를 놓칠 수 있기 때문이다. S06b는 Backend가 공개 KB 전체 변경을 transaction-bound revision으로 표시하고, AI가 그 revision의 snapshot을 끝까지 읽어 실제 검색 index와 일치시킨 뒤에만 별도 generation을 게시한다. 두 값이 현재도 맞고 cached citation까지 다시 승인될 때만 결과를 재사용하므로 hit에서는 embedding과 모델 호출을 생략하면서도 철회·상위 공개성·색인 지연에는 보수적으로 miss한다.
