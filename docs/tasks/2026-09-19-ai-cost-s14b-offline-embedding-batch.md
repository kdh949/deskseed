# AI 비용 절감 S14b — offline embedding Batch durable lifecycle

## Goal

초기/full PUBLIC KB 색인과 offline evaluation의 embedding을 공급자 Batch로 제출하고, 파일·custom ID·부분 결과·취소·늦은 결과·source withdrawal·usage와 cleanup을 재시작 가능한 상태로 수렴시킨다.

## Decision and source references

- Decision IDs: D-009, D-025, D-054, D-066, D-069.
- Accepted ADRs: 0025, 0049, 0052.
- Requirements: REQ-AI-004, REQ-AI-005, REQ-AI-008.
- Plan: `docs/ai-v1/COST_REDUCTION_PLAN.md` S14 and sections 3, 5, 9, 11, 12, 13, 14.
- Provider contract: OpenAI Files and Batch `/v1/embeddings`, `24h` completion window.
- Verification gates: AI-COST-001, AI-KB-001, AI-BATCH-001, AI-OBS-001, AI-OPS-001, AI-RET-001.
- 선행 구현: S14a reusable embedding artifacts and bounded arrays.

## Actor, authorization and eligible work

- offline Batch는 SYSTEM/index 또는 SYSTEM/eval budget owner만 사용한다. workspace total limit에는 포함되며 interactive actor 예산으로 가장하지 않는다.
- initial/full PUBLIC KB build, explicit historical reindex와 offline evaluation만 eligible하다. query, reply, rewrite, context memory와 상담사 request deadline 경로는 submit을 거부한다.
- source input은 기존 `INTEGRATION_CLIENT` exact PUBLIC projection으로만 만든다. caller는 raw input, custom ID, provider batch/file ID 또는 source binding을 제출하지 않는다.
- 현재 서버 PUBLIC KB 원문 provider 전송 승인은 적용되지만, live/paid submit과 production activation에는 별도 예산 승인과 provider data-control 확인이 필요하다.

## Durable lifecycle

- mode는 `off | test | intent`, 기본 `off`, production `test` 금지다.
- immutable manifest는 batch contract version, model snapshot, dimension, normalization, expected custom IDs, source/build binding, input digest와 reservation identity를 가진다. raw input/file bytes는 DB에 저장하지 않는다.
- custom ID는 manifest-local ordinal과 content-free digest로 만들고 bounded/unique하다. workspace/article/revision/job 값을 그대로 노출하지 않는다.
- lifecycle은 `PREPARING -> UPLOADING -> SUBMITTING -> IN_PROGRESS -> FINALIZING -> COMPLETED`이며 `CANCELLING`, `CANCELLED`, `FAILED`, `EXPIRED`, `CLEANUP_PENDING`을 포함한다.
- provider batch/file IDs, timestamps, request counts, output/error file IDs와 bounded error/status code는 durable하되 log/metric/Langfuse label에는 넣지 않는다.
- upload/submit/poll/download/finalize/delete의 각 external I/O 전후 intent/receipt를 commit한다. crash와 duplicate delivery는 same manifest/provider identity에 수렴한다.
- provider cancellation은 즉시 완료를 뜻하지 않는다. late partial output을 계속 reconcile하고 valid success usage는 정산한다.
- 모든 expected custom ID가 success/failed/unknown으로 정확히 분류되고 budget이 settled/UNKNOWN이 되기 전에는 completed가 아니다.
- vector artifact bind 직전에 build lease와 exact current PUBLIC source binding을 재검증한다. stale/withdrawn result는 비용만 보존하고 publish하지 않는다.
- input/output/error provider file은 terminal reconciliation 직후 delete intent를 만들고 acknowledgement까지 bounded retry한다. cleanup backlog는 content-free 운영 status에 포함한다.

## Limits and pricing

- completion window는 provider가 지원하는 `24h`만 허용한다.
- provider의 50,000 request/input, 200MB 외부 limit보다 작은 typed Deskseed request/input/byte limit를 적용한다.
- Batch 가격은 기존 standard 가격과 다른 immutable catalog/version으로 관리한다. 지원되지 않은 endpoint/model/tier/window 조합은 upload 전에 거부한다.
- reservation은 full manifest upper bound와 cleanup 전송 비용 정책을 반영한다. partial/failed/late/unknown output을 0원으로 처리하지 않는다.
- 동기 배열 최적화와 Batch 할인 지표를 분리하고, total workspace API cost에는 둘 다 포함한다.

## Privacy and retention

- JSONL/input/output/error raw bytes와 PUBLIC KB 원문은 Git, ordinary log, trace, Langfuse, audit evidence, screenshot 또는 Deskseed DB에 저장하지 않는다.
- provider Files에 일시 저장되는 PUBLIC KB 원문은 approved provider project/data-control scope만 사용한다.
- terminal 후 delete acknowledgement를 받아도 provider의 물리 삭제/backup expiry를 보장하지 않는다. provider retention 정책이 검토되지 않았거나 cleanup worker가 준비되지 않으면 production `intent`를 거부한다.
- content-free manifest/lifecycle/cost rows는 기존 AI execution metadata/UNKNOWN 조사 retention을 따른다. vector artifact는 S14a/index retention을 따른다.

## Acceptance scenarios

1. off와 interactive feature는 provider upload/submit 0회다; production test 설정은 startup failure다.
2. manifest custom IDs는 unique/bounded/content-free이고 duplicate/oversize/raw identifier input을 거부한다.
3. crash after upload/before submit, ambiguous submit와 duplicate poll은 두 provider batch를 만들거나 reservation을 이중 정산하지 않는다.
4. out-of-order output은 custom ID로 original manifest에 매핑되고 unknown/duplicate/missing ID는 fail closed다.
5. partial completion은 successful line usage를 보존하고 failed/unknown을 0원으로 만들지 않는다.
6. cancel 요청 뒤 늦은 successful result도 정산하지만 current source가 아니면 artifact/index에 bind하지 않는다.
7. source revision/audience/parent publication 또는 build lease가 바뀌면 finalization이 publish를 막는다.
8. terminal input/output/error file은 delete intent를 만들고 delete failure는 `CLEANUP_PENDING`으로 retry/status backlog에 남는다.
9. unsupported endpoint/model/tier/window와 price-catalog miss는 upload 전에 거부한다.
10. fake adapter/migration/restart tests는 lifecycle만 증명하며 live discount, 24h completion, quality 또는 savings로 보고하지 않는다.

## Validation

- `cd ai && .venv/bin/ruff check src tests scripts`
- `cd ai && .venv/bin/mypy`
- `cd ai && .venv/bin/pytest -q`
- PostgreSQL migration and restart/crash/duplicate/partial/cancel/late/withdrawal/cleanup tests.
- pinned OpenAI SDK Files/Batch request interception; no paid network call.
- pricing-catalog reservation/settlement and unsupported-combination regressions.
- `cd ai && .venv/bin/python scripts/export_openapi.py` and `scripts/evaluate_fake.py` regression only.
- `make docs-check`, `git diff --check`, latest PR HEAD CI.

## Compatibility, rollout and rollback

- reader → additive migration → workers mode `off` → fake/intercepted `test` → provider data-control/paid budget approval → small offline canary → measured `intent` 순서다.
- rollback은 new submit off → active batches cancel 요청 또는 drain → result/cost/file cleanup reconciliation 지속 → application rollback이다.
- application rollback이나 source withdrawal은 provider file을 즉시 사라지게 하지 않으므로 cleanup worker를 먼저 중지하지 않는다.
- Backend/Core/Staff/OpenAPI/frontend 변경과 deployment/merge는 이 slice에 없다.

## Evidence boundary

- fake provider와 intercepted SDK는 request shape, lifecycle, mapping, pricing 계산만 증명한다.
- live provider acceptance, discount, completion latency, bill reconciliation, retrieval quality와 total savings는 NOT_ESTABLISHED다.

## Human explanation

Batch는 배열 요청의 큰 버전이 아니라 하루 안에 끝나는 별도 비동기 서비스다. 제출 뒤 취소해도 일부 결과와 비용이 남을 수 있고 파일도 별도로 지워야 한다. 따라서 process memory가 아니라 manifest·provider 상태·비용·cleanup을 DB에서 각각 추적하고, 결과를 받는 시점에 공개 source가 여전히 같은지 다시 확인한다.
