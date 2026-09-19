# AI 비용 절감 S14a — embedding artifact 재사용과 bounded 배열 호출

## Goal

PUBLIC KB의 final embedding input이 model snapshot·dimension·normalization까지 동일할 때만 vector artifact를 재사용하고, 없는 입력만 bounded 동기 배열로 보내면서 source freshness와 call 단위 비용 원장을 보존한다.

## Decision and source references

- Decision IDs: D-009, D-025, D-054, D-066, D-069.
- Accepted ADRs: 0025, 0049, 0052.
- Requirements: REQ-AI-004, REQ-AI-005, REQ-AI-008.
- Plan: `docs/ai-v1/COST_REDUCTION_PLAN.md` S14 and sections 3, 5, 9, 11, 12, 13, 14.
- Provider contract: OpenAI Embeddings array input; pinned LiteLLM embedding transport.
- Verification gates: AI-COST-001, AI-KB-001, AI-EMBED-001, AI-OBS-001, AI-RET-001.
- 선행 구현: S09b immutable index generation, S13 code PR #212.

## Actor, source and scope

- actor는 기존 `INTEGRATION_CLIENT` source read와 SYSTEM/index budget owner다. caller가 workspace, article, revision, snapshot 또는 input을 선택하지 않는다.
- Backend exact PUBLIC source와 현재 build job/lease/generation을 provider I/O 전과 artifact bind 직전에 확인한다.
- 현재 서버 PUBLIC KB 원문 사용 승인 범위 안에서만 동작한다. query embedding, PUBLIC conversation, interactive reply/rewrite/context memory는 artifact cache 대상이 아니다.
- Backend/Core/Staff/OpenAPI/frontend 변경은 없다. AI migration과 internal indexing code만 변경한다.

## Runtime contract

- optimization mode는 `off | test | intent`, 기본값은 `off`다. production은 `test`를 거부한다.
- `intent`는 server allowlist의 immutable `modelSnapshot`이 필요하다. mutable alias만 있으면 startup이 실패한다. test snapshot은 `test:` namespace라 production artifact와 섞이지 않는다.
- reusable key는 `modelSnapshot + dimension + normalizationVersion + finalEmbeddingInput`의 canonical length-prefixed SHA-256이다. title/category/section/body가 final input에 모두 포함된다.
- artifact에는 vector와 bounded contract metadata만 저장하고 raw input은 저장하지 않는다. chunk는 artifact key와 검색용 vector를 함께 가진다.
- mode `off`는 기존 one-input call shape를 유지한다. `test | intent`는 exact artifact lookup 후 miss만 `1..N` bounded array로 보낸다.
- 배열 response는 index의 complete unique mapping과 vector dimension을 검증한다. 하나라도 틀리면 artifact/index publish를 막는다.
- 한 배열은 하나의 reservation/call/receipt/settlement다. total usage를 chunk actual cost로 분해하지 않는다.
- successful earlier call의 비용은 later call/source recheck/publish 실패에도 남는다. delivery ambiguity는 `UNKNOWN`이다.
- concurrent insert는 unique key로 canonical vector artifact에 수렴하지만 provider dispatch coalescing은 보장하지 않는다.

## Data, privacy and retention

- raw PUBLIC KB title/body/final input과 vector는 ordinary log, metric, trace, Langfuse, audit, Git evidence에 넣지 않는다.
- reusable key/input digest와 workspace/article/revision/job ID도 metric label이나 Langfuse metadata에 넣지 않는다.
- source withdrawal은 current chunk/publication binding을 즉시 차단한다. reusable artifact의 물리 purge는 기존 index artifact retention을 따르며 이 slice가 새 보존 기간을 만들지 않는다.
- migration은 additive다. 기존 chunk는 nullable artifact key를 유지하고 새 optimized writer만 key를 채운다.

## Acceptance scenarios

1. 같은 final input과 exact model snapshot/dimension/normalization은 provider call 없이 같은 artifact를 재사용한다.
2. body가 같아도 title/category/section, normalization, dimension 또는 snapshot이 다르면 miss다.
3. mutable alias나 빈 snapshot으로 production `intent`는 시작하지 않으며 `test` artifact가 intent lookup에 hit하지 않는다.
4. mixed hit/miss는 miss만 bounded arrays로 보내고 original chunk order로 완성한다.
5. out-of-order valid response는 provider index로 복원되고 missing/duplicate/out-of-range index나 wrong dimension은 fail closed다.
6. 한 array receipt는 total usage 한 번만 정산되고 같은 call ID를 여러 chunk에 매핑해 비용을 중복 합산하지 않는다.
7. second array 실패 또는 source withdrawal은 index publish를 막지만 first array의 settled/UNKNOWN 비용은 유지한다.
8. duplicate event/retry와 concurrent builder가 artifact row를 덮어쓰지 않고 same key/vector에 수렴한다.
9. migration 016→017은 기존 chunk/index를 보존하고 invalid key/spec/vector row를 constraint로 거부한다.
10. log/metric/trace/Langfuse/DB content columns에 raw final input이 없고 fake/intercepted test를 savings evidence로 보고하지 않는다.

## Validation

- `cd ai && .venv/bin/ruff check src tests scripts`
- `cd ai && .venv/bin/mypy`
- `cd ai && .venv/bin/pytest -q`
- PostgreSQL/pgvector migration, hit/miss/concurrency/source-withdrawal/cost-preservation tests.
- pinned LiteLLM final OpenAI SDK body array interception; no paid network call.
- `cd ai && .venv/bin/python scripts/export_openapi.py` to prove unchanged artifact reproducibility.
- `cd ai && .venv/bin/python scripts/evaluate_fake.py` — regression only, not quality/savings evidence.
- `make docs-check`, `git diff --check`, latest PR HEAD CI.

## Compatibility, rollout and rollback

- reader → migration 017 → writer with mode `off` → synthetic `test` → reviewed snapshot and paid budget approval 후 measured `intent` 순서다.
- mode off는 새 lookup/array dispatch를 중지한다. additive artifacts/keys와 settled receipt는 즉시 삭제하지 않는다.
- old application은 nullable artifact key를 무시할 수 있다. migration down/drop이나 production reindex는 자동 실행하지 않는다.

## Evidence boundary and non-goals

- fake/intercepted provider는 key/mapping/transport/accounting 회귀만 증명한다.
- live cache hit, duplicate-dispatch rate, latency, provider bill, retrieval quality와 total savings는 NOT_ESTABLISHED다.
- offline provider Batch, product UI, deployment, merge, paid reindex는 S14a 범위가 아니다.

## Human explanation

본문이 같아도 제목이나 모델 의미가 바뀌면 검색 vector는 같은 결과라고 볼 수 없다. 그래서 실제 전송 입력과 불변 모델 식별자까지 exact match일 때만 재사용한다. 여러 missing input은 한 call로 줄이되 공급자는 call 전체 usage만 주므로 청크별 비용을 실제 청구액처럼 꾸미지 않는다.
