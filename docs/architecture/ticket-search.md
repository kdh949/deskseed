# 활성·종료 티켓 검색

```mermaid
flowchart LR
    n_query["검색어·필터 입력<br/>상태 미지정"]:::client
    n_active["활성 티켓 검색"]:::app
    n_enough{"페이지 결과 충분"}:::decision
    n_terminal["종료 티켓 검색"]:::app
    n_ids["페이지 티켓 ID 선택"]:::app
    n_join["고객·그룹·담당자·SLA JOIN"]:::app
    n_audit[("필수 검색 이력 저장")]:::data
    n_response["결과 + 다음 페이지 cursor"]:::client
    n_fail["503 반환"]:::client

    n_query --> n_active
    n_active --> n_enough
    n_enough -->|"충분"| n_ids
    n_enough -->|"부족"| n_terminal
    n_terminal --> n_ids
    n_ids --> n_join
    n_join --> n_audit
    n_audit -->|"저장 성공"| n_response
    n_audit -->|"저장 실패"| n_fail

    classDef client fill:#eff6ff,stroke:#60a5fa,color:#17233b
    classDef edge fill:#f8fafc,stroke:#94a3b8,color:#17233b
    classDef app fill:#f0fdfa,stroke:#2bbbad,color:#17233b
    classDef data fill:#fff7ed,stroke:#fdba74,color:#17233b
    classDef external fill:#f5f3ff,stroke:#bda4ee,color:#17233b
    classDef decision fill:#f0fdfa,stroke:#2bbbad,color:#17233b
```

상태 조건이 없으면 활성 티켓을 먼저 검색하고 결과가 부족할 때 종료 티켓을 이어서 검색합니다. 상태를 지정하면 해당 상태의 티켓을 검색합니다. 페이지에 넣을 티켓을 고른 뒤 상세 정보를 JOIN하고 검색 이력을 저장합니다.

## 성능 개선 측정

### 검색 흐름 개선

100만 티켓이 저장된 개인 스테이징에서 1 VU·검색 20회로 A/B 테스트를 진행했습니다. 첫 페이지의 전체 건수 계산을 줄이고, 검색 범위가 넓을 때 검색어 추가 안내를 반환하도록 변경했습니다.

| 항목                      | 변경 전        | 변경 후                             |
| ------------------------- | -------------- | ----------------------------------- |
| 검색 20회의 HTTP 응답 p95 | 22.056초       | 1.601초                             |
| 응답 구성                 | 결과 목록 20건 | 결과 목록 15건·검색어 추가 안내 5건 |

변경 후 결과 목록 15건의 p95는 1.158초였습니다. 구현과 측정은 [PR #237](https://github.com/kdh949/deskseed/pull/237)에 정리했습니다.

### 최신순 인덱스 적용

`tickets(updated_at DESC, ticket_number DESC) INCLUDE (id)` 인덱스를 추가했습니다. 100만 티켓·1 VU·최신순 정렬 조건에서 같은 검색 요청(`topic:3`)의 HTTP 응답시간을 비교했습니다.

| 측정          | HTTP 응답시간 |
| ------------- | ------------: |
| 적용 전 1회   |   4,166.014ms |
| 적용 후 1회차 |      49.899ms |
| 적용 후 2회차 |      49.748ms |
| 적용 후 3회차 |      47.876ms |

- [최신순 인덱스 PR #234](https://github.com/kdh949/deskseed/pull/234)
- [Flyway V97](../../backend/src/main/resources/db/migration/V97__index_staff_search_latest_order.sql)
- [인덱스 설계와 실행계획 · ADR-0054](../adr/0054-latest-first-staff-search-order-index.md)
- [배포 후 HTTP 반복 측정 기록](../tasks/2026-09-21-postgresql-search-optimization-handoff.md#고정-corpus-전후)

## 관련 문서

- [검색 SQL 계획](../../backend/src/main/kotlin/dev/deskseed/ticketing/internal/StaffTicketSearchSqlPlan.kt)
- [검색 Repository](../../backend/src/main/kotlin/dev/deskseed/ticketing/internal/StaffTicketQueryRepository.kt)

[시스템 구조](overview.md) · [README](../../README.md#아키텍처)
