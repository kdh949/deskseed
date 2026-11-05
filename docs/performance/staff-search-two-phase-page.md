# 상담사 검색 — 페이지를 먼저 선택한 뒤 상세 JOIN

## Scope

`StaffTicketSearchSqlPlanFactory`의 PAGE 쿼리(REQ-SRCH-001)를 개선한 실험입니다. 검색 후보 전체에 상세 정보를 JOIN하던 처리를, 페이지에 반환할 티켓을 먼저 고른 뒤 JOIN하는 방식으로 바꿨습니다. 정확한 COUNT, 점수·정렬, 권한, 감사, cursor, API, schema·인덱스와 커넥션 풀 설정은 유지했습니다.

측정에는 개인 스테이징의 합성 검색 데이터를 사용했습니다.

- 배포: `10d3050f0744a17b691e2cb8735800f5f7ca35cc`
- seed: `20260919`
- corpus SHA-256: `5d4df63a1912af7a991ed71bc61b7d579d92c7b6e862db96e523d0bef597173d`
- 공유 문서에는 검색 원문을 포함하지 않습니다.

## Before/after component evidence

기존 PAGE 쿼리는 검색 후보 22,510건에 티켓·고객·그룹·담당자·SLA 정보를 JOIN한 뒤 26건을 반환했습니다. 변경한 쿼리는 먼저 `ticket_id`, `ticket_number`, `updated_at`, `search_score`를 구해 정렬·개수 제한을 적용하고, 선택된 티켓에만 상세 정보를 JOIN합니다.

| Four read-only executions | Baseline PAGE | Two-phase PAGE |
|---|---:|---:|
| Samples (ms) | 1797.398, 1754.404, 1760.562, 2068.953 | 1812.036, 1475.104, 1617.365, 1568.077 |
| Median | 1778.980 ms | 1592.721 ms |
| Mean | 1845.329 ms | 1618.146 ms |
| Root shared hit blocks, median | 318,156 | 134,707 |
| Root shared read blocks, median | 83,311 | 64,988 |
| Detail join rows | 22,510 | 26 |

4회 측정에서 shared hit blocks 중앙값은 57.66%, shared read blocks 중앙값은 21.99% 줄었습니다. 실행시간 중앙값은 10.47% 감소했지만, 전후 측정 범위가 겹쳤습니다. 이 입력에서 처리량과 버퍼 접근은 줄었으며, 지연시간의 반복 측정이 더 필요합니다.

COUNT는 약 1.155초로 유지됐습니다. 구성요소별 중앙값으로 계산한 COUNT+PAGE 감소 예상치는 약 6.35%입니다. SQL 구성요소를 각각 측정한 값이며 HTTP 전후 비교는 이 실험에 포함하지 않았습니다.

## Correctness evidence and limits

- 같은 repeatable-read snapshot에서 두 쿼리가 동일한 26개 고유 티켓 ID·번호·점수를 반환했습니다.
- PostgreSQL 통합 테스트는 점수·최신순 정렬, 두 cursor 형식, snapshot 제외, 정확한 COUNT, 상태·우선순위·그룹·담당자·SLA 필터, INTERNAL 검색 권한, 정확한 티켓 번호 우선순위, 와일드카드 문자 검색과 필수 감사 실패를 검사합니다.
- SLA 필터가 있을 때만 후보 선택 단계에서 SLA 데이터를 JOIN합니다. 상세 조회 단계에서는 선택된 페이지에 SLA 정보를 JOIN합니다.
- 대규모 전후 측정은 `common:0`, 점수순, 필터 없는 첫 페이지를 대상으로 했습니다. 필터·cursor의 동작은 자동 테스트로 확인했고, 해당 조건의 대규모 지연시간은 후속 측정 대상입니다.
- 넓은 부분문자열 후보 검색과 정확한 COUNT 비용은 남아 있습니다.

## Re-measurement gate

배포 승인 후에는 같은 corpus·seed와 새 `TEST_RUN_ID`로 HTTP 경로를 다시 측정합니다. Grafana에서 HTTP, COUNT, PAGE, 감사 저장, 오류·누락 요청, 자원 사용량과 부하 제거 후 회복을 비교하고, 검색 결과와 감사 동작의 회귀도 함께 확인합니다.
