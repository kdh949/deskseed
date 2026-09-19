# ADR 0052: AI embedding artifact 재사용과 offline Batch 경계

- Status: Accepted
- Date: 2026-09-19
- Decision: D-069
- Requirements: REQ-AI-004, REQ-AI-005, REQ-AI-008

## Context

PUBLIC KB generation build는 같은 최종 embedding 입력을 revision과 generation마다 다시 과금할 수 있다. 현재 indexer는 각 청크를 개별 동기 요청으로 보내므로 공급자가 지원하는 배열 입력을 활용하지 못하고, 초기 전체 색인처럼 상담사 응답과 무관한 작업에도 일반 동기 가격을 사용한다.

벡터 재사용은 content digest만으로 결정할 수 없다. 문서·category·section 제목을 포함한 실제 전송 입력, normalization, dimension과 model 의미가 모두 같아야 한다. 또한 현재 OpenAI의 `text-embedding-3-small` 문서는 날짜가 고정된 불변 snapshot을 제공하지 않으므로, mutable alias를 snapshot이라고 기록하면 alias가 바뀐 뒤 오래된 벡터를 잘못 재사용할 수 있다.

공급자 Batch는 24시간 completion window의 offline API이며 동기 배열 요청과 다른 lifecycle이다. 입력 JSONL과 결과 파일, provider batch ID, 부분 완료, 취소와 늦은 결과를 process memory에만 두면 재시작 후 source freshness와 비용 정산을 보장할 수 없다.

## Decision

Deskseed는 동기 embedding 최적화와 offline Batch를 서로 다른 mode와 durable state로 구현한다. 둘 다 `off | test | intent`이며 기본값은 `off`이고 production은 `test`를 거부한다. `intent`는 실행 허가일 뿐 비용 절감이나 품질 동등성을 의미하지 않는다.

### Reusable embedding artifact

- 재사용 key는 canonical length-prefixed encoding으로 `modelSnapshot`, dimension, normalization version과 final embedding input을 결합한 SHA-256이다. final input에는 공개 article title, category title, section title과 body chunk가 들어간다.
- `modelSnapshot`은 server-owned allowlist의 검토된 불변 공급자 식별자다. mutable alias만 있는 경우 production `intent`를 허용하지 않는다. test는 `test:` namespace의 합성 snapshot만 사용하며 production artifact와 섞이지 않는다.
- artifact row는 key, snapshot, dimension, normalization version, final-input digest, vector, bounded actual-model metadata와 시간만 저장한다. raw title/body/final input은 저장하지 않는다.
- vector blob과 article/revision/generation chunk binding을 분리한다. pgvector 검색용 vector는 current immutable chunk artifact에도 복사하되 reusable artifact key로 provenance를 고정한다.
- lookup은 exact key만 허용한다. dimension·vector shape·snapshot·normalization·input digest가 하나라도 다르거나 row가 malformed하면 miss/fail closed다.
- artifact publish transaction은 current index build lease와 exact PUBLIC source binding을 다시 확인한다. 철회·수정된 source 또는 stale generation은 새 artifact나 chunk binding을 current index에 노출하지 않는다.
- concurrent builders는 unique key와 insert-on-conflict 후 canonical read로 한 vector blob에 수렴한다. 이는 concurrent provider dispatch 자체를 coalesce하지 않으므로 중복 비용이 가능하며 절감 지표가 이를 포함한다.

### Bounded synchronous array request

- cache miss만 original chunk order를 유지한 bounded 배열로 보낸다. 내부 limit은 provider limit보다 작고 typed setting으로 제한한다.
- 한 배열은 하나의 provider call, reservation, receipt와 total usage를 가진다. call 총비용을 청크별 실제 과금으로 분해하지 않는다. 청크별 allocation이 필요하면 명시적으로 추정치로 표시한다.
- response는 index가 unique하고 `0..n-1`을 정확히 한 번씩 덮으며 각 vector dimension이 일치해야 한다. 누락·중복·범위 밖 index·잘못된 dimension은 해당 call을 실패시킨다.
- usage receipt와 비용 정산은 output mapping 검증과 독립적으로 먼저 보존한다. 이후 batch가 실패해도 앞선 성공 call 비용은 유지되고, delivery ambiguity는 기존 `UNKNOWN`을 따른다.
- query embedding과 고객 PUBLIC 대화는 reusable artifact 대상이 아니다. 이 slice의 cache는 PUBLIC KB index input만 다룬다.

### Offline provider Batch

- 대상은 initial/full PUBLIC KB build, 명시적으로 선별된 historical reindex와 offline evaluation뿐이다. 상담사가 기다리는 query/reply/rewrite/context-memory call은 금지한다.
- job은 immutable manifest와 unique content-free `customId`를 만들고 SYSTEM/index 또는 SYSTEM/eval budget을 workspace total 안에서 예약한다. request body의 workspace/article/revision 원문 식별자는 custom ID에 넣지 않는다.
- 상태는 `PREPARING -> UPLOADING -> SUBMITTING -> IN_PROGRESS -> FINALIZING -> COMPLETED`를 기본으로 하고 `CANCELLING`, `CANCELLED`, `FAILED`, `EXPIRED`, `CLEANUP_PENDING`을 durable하게 보존한다. provider batch/file ID, status timestamps, request counts, output/error file ID와 마지막 bounded error code를 저장한다.
- upload 후 submit 전 crash, duplicate submit response, polling restart, partial output, cancel과 늦은 terminal result를 멱등 수렴시킨다. provider output line은 custom ID가 manifest에 정확히 한 번 대응하고 status/body/usage가 유효해야 한다.
- partial output의 성공 usage와 실패/unknown을 각각 정산한다. 결과가 일부 있어도 모든 expected custom ID가 terminal로 분류되고 비용이 settled 또는 UNKNOWN이 되기 전에는 job을 완료하지 않는다.
- result vector binding 직전에 current build job, model snapshot, PUBLIC source revision/audience와 active corpus contract를 다시 확인한다. 철회되거나 바뀐 source 결과는 비용만 정산하고 artifact/index에 bind하지 않는다.
- input/output/error 파일은 terminal reconciliation 뒤 즉시 delete intent를 만들고 성공할 때까지 bounded retry한다. provider의 물리 삭제 시각은 보장하지 않는다. production `intent`는 reviewed provider data-control/retention 설정과 file cleanup worker가 모두 준비된 경우에만 허용한다.
- provider completion window는 24시간으로 고정하고, Deskseed는 provider limit보다 작은 request/input/byte 한도를 적용한다. limit 변경은 contract test와 cost reservation review를 요구한다.

## Cost and observability

SYSTEM/index와 SYSTEM/eval은 interactive actor budget과 별도 owner로 집계하지만 같은 workspace total limit에 포함한다. 동기 배열은 call total usage를, Batch는 output line과 provider request counts를 canonical manifest와 대조해 정산한다. cache hit는 provider call과 절감 추정치를 구분하며, concurrent duplicate dispatch·failed/withdrawn result·partial output·UNKNOWN도 전체 비용 분자에 포함한다.

metric, log, trace와 Langfuse에는 mode, bounded state/result code, batch size, reused/missing count, call count, usage/cost만 허용한다. raw PUBLIC KB text, final input, vector, reusable key/input digest, provider file content, provider batch/file ID와 article/workspace/job 식별자는 label 또는 metadata로 내보내지 않는다.

## Privacy and retention

PUBLIC KB 원문을 embedding provider에 전송하는 기존 승인 범위 안에서만 동작한다. 원문과 JSONL/result 파일은 Git, ordinary log, audit evidence, screenshot과 Langfuse에 넣지 않는다. Deskseed DB는 provider file bytes를 저장하지 않고 content-free manifest와 derived vector만 저장한다.

Reusable artifact는 기존 immutable PUBLIC KB index artifact의 파생 데이터 보존 경계를 따른다. source withdrawal은 즉시 current publication/query binding을 끊지만 DB page·backup에서 즉시 물리 삭제됐다고 주장하지 않는다. 자동 artifact purge와 법적 삭제 기간은 별도 운영 정책이 확정되기 전까지 추가하지 않는다.

Provider file cleanup은 terminal state와 별개로 추적한다. delete acknowledgement 전 `CLEANUP_PENDING`을 유지하고 operator status에 backlog age/count를 노출한다. provider-side backup/abuse-monitoring retention은 공급자 data-control 계약을 따르며 Deskseed의 delete acknowledgement가 즉시 물리 삭제를 증명하지 않는다.

## Consequences

변경되지 않은 PUBLIC KB 입력은 provider 호출 없이 재사용할 수 있고, missing input은 호출 수를 줄일 수 있다. offline Batch는 초기 색인의 단가를 낮출 가능성이 있지만 완료 지연과 파일 lifecycle, 별도 복구 state를 추가한다. mutable model alias만 제공되는 현재 provider 설정에서는 production artifact reuse가 기본적으로 활성화되지 않으므로 실제 절감은 없다.

## Alternatives considered

- `content_sha256`만으로 재사용: title/section/model/normalization 차이를 놓쳐 거부한다.
- article revision에 vector를 직접 소유: generation 간 동일 입력 재사용이 불가능해 거부한다.
- mutable alias를 snapshot으로 간주: 공급자 변경을 탐지할 수 없어 production `intent`에서는 거부한다.
- 배열 usage를 청크별 실제 비용으로 균등 배분: 공급자 청구 근거가 아니므로 canonical ledger로 사용하지 않는다.
- Batch를 interactive reply에 사용: 24시간 completion window가 상담사 흐름과 맞지 않아 거부한다.
- provider file/batch 상태를 process memory로만 관리: crash 뒤 비용·철회·cleanup 수렴을 증명할 수 없어 거부한다.

## Revisit triggers

- provider가 immutable embedding snapshot이나 새로운 dimension/model migration contract를 제공한다
- provider array/Batch limit, pricing, completion window, file retention 또는 output schema가 바뀐다
- measured duplicate dispatch, index latency, DB storage 또는 cleanup backlog가 운영 한도를 넘는다
- PUBLIC KB provider 전송 승인이나 data-residency/retention 정책이 바뀐다
- representative corpus에서 retrieval quality 또는 total cost가 baseline보다 나빠진다
