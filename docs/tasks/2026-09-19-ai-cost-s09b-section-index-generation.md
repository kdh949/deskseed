# AI 비용 절감 S09b — 절 중심 청크와 복구 가능한 인덱스 세대

## Goal

공개 KB의 문서 제목·공개 category/section 제목·본문 경계를 보존한 청크를 새 immutable index artifact에 전부 적재한 뒤에만 검색 포인터를 원자적으로 전환하고, 문제가 있으면 같은 canonical PUBLIC corpus revision의 직전 완성 artifact로 새 publication epoch를 만들어 복구한다.

## Decision and source references

- Decision IDs: D-009, D-025, D-054, D-066.
- Accepted ADRs: 0025, 0049.
- Requirements: REQ-AI-004.
- Plan: `docs/ai-v1/COST_REDUCTION_PLAN.md` S09b and sections 9, 13, 14.
- API operation: `readAiPublicKnowledgeArticle`; external Core/Staff API is unchanged.
- Verification gates: AI-SRC-001, AI-KB-001, AI-COST-001, AI-REPLY-001, AI-OPS-001.
- 선행 구현: S09a current-problem RRF retrieval PR #202.

## Actor and source boundary

- 공개 article source read는 기존 `INTEGRATION_CLIENT`와 `KNOWLEDGE_INDEX` credential만 사용한다. caller가 workspace, article binding, 공개성 또는 section metadata를 선택하지 않는다.
- Backend는 current published `PUBLIC` article이면서 active category/section 아래에 있는 경우에만 source를 반환한다. 응답에 공개 `categoryTitle`과 `sectionTitle`을 추가하되 category/section ID, draft, author, internal metadata는 추가하지 않는다.
- source access와 canonical publication authorization은 계속 Backend가 소유한다. AI DB의 artifact 상태나 SQL filter는 current 공개성 증거가 아니다.
- 현재 서버 PUBLIC 대화·공개 KB 원문 사용 승인은 유지하지만, 이 slice는 live corpus export나 paid embedding 호출을 수행하지 않는다. 원문·embedding input은 Git, ordinary log, trace/Langfuse metadata, 감사 증빙에 넣지 않는다.

## Section-aware chunk contract

- final embedding input은 `document title + public category title + public section title + body chunk`를 이름이 고정된 구획으로 구성한다. NFKC, CRLF 통일, control 제거, 줄 끝 공백 제거와 bounded blank-line 정규화만 적용한다. 제목이나 본문을 요약·번역·추론하지 않는다.
- 검색·인용에 반환하는 `content`는 원래 공개 본문에서 정규화한 body chunk이며 embedding용 제목 prefix를 본문으로 가장하지 않는다. keyword 후보는 document/category/section 제목과 body chunk를 모두 검색하되 generation evidence는 body와 canonical title/url 계약을 유지한다.
- 현재 source는 article 내부 heading AST를 제공하지 않으므로 임의 heading을 추정하지 않는다. 확정 가능한 plain-text blank-line block과 줄 경계만 사용하고, public knowledge category/section title을 구조 metadata로 사용한다.
- 빈 줄로 이어진 paragraph block은 기본 분할 단위다. 같은 block의 조건·예외 문장과 연속된 표 모양 줄은 한 단위로 유지한다. token 상한을 넘는 block만 line, sentence, 마지막으로 tokenizer-safe text boundary 순서로 분할한다.
- chunk body 상한은 reviewed embedding tokenizer 기준 512 tokens다. 제목 prefix를 포함한 최종 embedding input도 provider 입력 상한과 DB content 상한 안에 있어야 하며, 안전하게 분할할 수 없으면 provider 호출 전에 해당 build를 실패시킨다.
- adjacent chunk overlap은 두지 않는다. 조건을 중복시키는 overlap 대신 block을 원자 단위로 pack한다. 같은 문서의 인접 ordinal suppression은 S09a와 함께 유지한다.
- `chunkerVersion=section-block-v2`, `normalizationVersion=public-text-nfkc-v2`, embedding model alias와 dimension을 artifact metadata와 각 chunk의 embedding-input digest에 고정한다. 어느 값이 달라져도 기존 artifact에 섞어 쓰지 않는다.

## Immutable artifact and publication contract

- AI migration 012는 legacy mutable revision/chunk table을 즉시 삭제하지 않고 generation-aware artifact, revision, chunk, build-job, publication-history table을 추가한다. 현재 게시 포인터와 일치하는 legacy rows는 그 artifact로 한 번 복사해 reader 전환 중 빈 검색을 막는다.
- Backend index event는 최신 source state를 멱등 기록하고 reconciliation을 즉시 due로 만드는 signal이다. active artifact를 제자리 수정하거나 event transaction에서 provider를 호출하지 않는다.
- manifest snapshot을 시작할 때 workspace advisory lock 아래 새 positive `artifactGeneration`을 할당한다. snapshot의 모든 item마다 build job을 만들며 job identity는 run/artifact/article/revision/publicRevision/index-spec에 결합한다.
- build worker는 정확한 source binding과 PUBLIC data class를 다시 확인하고, SYSTEM budget을 chunk call별 예약·receipt 정산한 뒤 target artifact에만 revision/chunk를 기록한다. stale source, lease loss, dead job, UNKNOWN call 또는 부분 artifact는 active reader에 보이지 않는다.
- publication transaction은 snapshot expiry, canonical corpus revision, seen count/binding, 모든 build job 성공, 각 item의 revision/chunk/index-spec 일치, snapshot 밖 revision 부재를 검증한다. 그 뒤에만 active pointer를 새 artifact로 바꾸고 publication epoch를 1 증가시킨다.
- retrieval transaction은 active pointer의 `artifactGeneration`을 한 번 읽고 vector·keyword SQL 모두 같은 artifact로 제한한다. pointer가 없거나 Backend current corpus revision과 published artifact revision이 다르면 reply generation은 fail closed하며 stale/partial artifact를 검색하지 않는다.
- `publicationEpoch`는 publish와 restore마다 단조 증가하고 S06 cache/shared-execution identity의 `generation` 값으로 사용한다. immutable `artifactGeneration`은 실제 chunk 집합을 가리킨다. 복구가 과거 artifact를 다시 가리켜도 cache namespace는 새 epoch다.

## Restore and retention contract

- restore는 reusable product operation이며 caller가 body나 raw SQL을 제공하지 않는다. workspace, target artifact, expected current publication epoch, expected canonical corpus revision과 bounded reason을 받는다.
- target은 `COMPLETE`이고 current Backend canonical corpus revision과 exact match하며 현재 index-spec과 dimension이 호환돼야 한다. 다른 corpus revision, BUILDING/FAILED artifact, 현재 포인터, stale expected epoch는 거부한다.
- restore는 advisory lock/CAS transaction에서 active pointer를 target artifact로 바꾸고 새 publication epoch와 body-free history row를 만든다. provider call, Backend network call, chunk copy는 transaction 안에서 하지 않는다.
- 최소 active artifact와 직전 complete artifact는 보존한다. 이 slice는 자동 purge를 구현하지 않으며 기존 table/artifact를 즉시 drop하지 않는다. 후속 retention은 active/history reference와 in-flight result/cache TTL을 고려해야 한다.
- initial cutover와 rollback은 indexer/worker drain을 전제로 한다. application rollback은 reader를 legacy table로 되돌릴 수 있지만 migration과 complete artifact는 즉시 삭제하지 않는다.

## In scope

- AI source OpenAPI의 additive `categoryTitle`/`sectionTitle`, Backend canonical projection과 regression tests.
- deterministic section-aware tokenizer-bounded chunker와 embedding-input builder.
- AI migration 012의 immutable artifact/build/publication history, legacy backfill과 active pointer 확장.
- reconciliation full build, atomic publish, artifact-scoped vector/keyword retrieval, same-corpus restore product operation.
- chunker/retrieval/cache version bump, incomplete/mixed generation과 corpus drift fail-closed regression.
- synthetic PUBLIC KB fixtures의 chunk boundary, table/condition grouping, generation switch/restore와 query plan 검증.

## Out of scope

- unchanged embedding digest reuse, multi-input provider request, offline Batch API, cost break-even automation(S14).
- article 내부 structured heading AST 추가, Markdown/HTML parser 추정, OCR, query rewrite, reranker, 새 VectorDB.
- live corpus reindex, paid provider canary, production quality/cost reduction claim, merge·deploy.
- actual corpus/query-plan/load evidence, screenshots, one-off audit script와 logs의 Git commit.
- Staff/Admin HTTP endpoint와 UI. restore는 AI operational code/API boundary까지만 제공하며 배포·실행 권한은 별도다.

## Invariants and failure semantics

- partial build never changes the active artifact. publish/restore pointer and history row commit together.
- source/network/provider I/O는 publication/restore DB transaction 밖이다. provider response usage는 output validation과 독립적으로 기존 call ledger에 보존한다.
- full reindex는 별도 SYSTEM budget과 기존 indexing max-attempt/UNKNOWN semantics를 따른다. fake test 비용은 actual saving으로 보고하지 않는다.
- duplicate manifest page/job/receipt/publication retry는 same identity에 수렴한다. conflicting source or digest는 overwrite가 아니라 bounded conflict다.
- publish 직전 canonical source revision은 snapshot에 고정되며, runtime reply는 Backend current policy revision과 active artifact revision mismatch에서 생성하지 않는다.
- source body, titles와 embedding input/hash 원문 대응값은 application log, operation reason, publication history 또는 Langfuse에 기록하지 않는다.

## Acceptance scenarios

1. article title, active public category/section title과 짧은 body block은 하나의 versioned embedding input에 들어가지만 stored evidence body에는 prefix가 중복되지 않는다.
2. 조건과 예외가 같은 paragraph block에 있거나 표 header와 연속 row가 있으면 token 상한 안에서 같은 chunk에 남는다. 긴 block만 512-token 이하 subchunk가 되고 공백/제어문자 정규화가 반복 실행에서 같다.
3. inactive/non-public parent 아래 article source는 metadata를 포함해도 반환되지 않으며 draft/internal metadata는 응답에 없다.
4. 새 artifact build 중 일부 job이 pending/leased/dead/UNKNOWN이거나 chunk/index-spec이 없으면 active pointer와 publication epoch는 변하지 않고 기존 complete artifact만 검색된다.
5. 모든 manifest item이 target artifact에 정확히 적재된 뒤 publish하면 한 transaction에서 pointer가 바뀌고 vector/keyword query 모두 새 artifact만 읽는다.
6. source corpus가 build 도중 바뀌거나 snapshot이 만료되면 artifact는 publish되지 않는다. runtime corpus/pointer mismatch는 query embedding/generation을 추가 호출하지 않고 fail closed한다.
7. 같은 canonical corpus revision의 직전 complete artifact restore는 expected epoch CAS를 통과할 때 새 publication epoch를 만들며 provider call 0회다. stale epoch, 다른 corpus, incompatible spec과 partial artifact는 거부된다.
8. restore 뒤 completed result cache/shared execution은 이전 publication epoch key를 재사용하지 않는다.
9. legacy published rows가 있는 migration은 active artifact를 backfill하고 빈 결과 없이 전환한다. pointer가 없는 legacy rows는 임의로 공개하지 않는다.
10. synthetic 1k/10k corpus에서 artifact filter를 포함한 HNSW/GIN query plan과 ANN-vs-exact recall을 별도로 확인하며 HTTP 200/fake embedding을 production 품질로 간주하지 않는다.

## Validation

- `cd backend && GRADLE_USER_HOME=/private/tmp/deskseed-gradle-s09b ./gradlew test`
- Backend focused AI source projection/OpenAPI tests: public parent metadata, inactive parent denial, response field bound and internal-data absence.
- `cd ai && .venv/bin/ruff check src tests scripts`
- `cd ai && .venv/bin/mypy`
- `cd ai && .venv/bin/pytest -q`
- PostgreSQL/pgvector-backed migration backfill, deterministic chunking, partial build isolation, atomic switch, corpus drift, CAS restore and cache epoch tests.
- `cd ai && .venv/bin/python scripts/export_openapi.py` and generated internal OpenAPI parity.
- `cd ai && .venv/bin/python scripts/evaluate_fake.py` — contract regression only; live quality/cost claim 금지.
- synthetic artifact-filtered `EXPLAIN (ANALYZE, BUFFERS)`, Recall@10 and ANN-vs-exact recall; evidence is not committed.
- `make docs-check`, `git diff --check`, latest PR HEAD CI.

## Compatibility and migration

- AI source response change is additive but `additionalProperties: false` consumers must deploy the updated decoder with Backend. Core/Staff API와 current UI contract는 바뀌지 않는다.
- migration 012는 additive generation tables/columns and one-time legacy data copy다. old tables remain for application rollback; new writer does not mix artifacts.
- deploy order is migration → Backend source metadata writer → drained AI indexer/worker deployment → full fake/synthetic build verification → operational live reindex only after explicit paid budget approval. Until a matching new artifact is complete, existing active artifact remains.
- rollback is stop/drain new indexer → restore compatible prior artifact or application rollback → preserve additive schema/history for investigation. migration down/drop and production reindex are not automatic.

## Human explanation

청크 알고리즘을 바꾸면서 기존 검색 table을 제자리 갱신하면 상담사 요청이 일부는 구 청크, 일부는 신 청크를 읽을 수 있고 실패한 재색인에서 되돌릴 기준도 사라진다. S09b는 공개 구조 metadata를 Backend가 명시적으로 제공하고, 새 청크를 별도 immutable artifact에 완성한 뒤 포인터 하나만 바꾼다. 복구도 과거 row를 다시 쓰지 않고 같은 corpus의 완성 artifact를 새 publication epoch로 다시 가리키므로, partial build와 stale cache를 동시에 차단한다.
