# DeskSeed 시스템 아키텍처

```mermaid
flowchart LR
    n_portal["Customer Portal<br/>문의 등록 · 내 문의 · KB"]:::client
    n_staff["Staff Console<br/>상담사 워크스페이스<br/>관리자"]:::client
    n_waf["Sophos WAF<br/>HTTPS 진입<br/>Reverse Proxy"]:::edge
    n_nginx["Nginx<br/>API · WebSocket<br/>프록시"]:::edge
    n_api["DeskSeed API<br/>Spring Modulith<br/>Kotlin · Spring Boot"]:::app
    n_db[("PostgreSQL<br/>업무 데이터<br/>검색 문서 · Outbox")]:::data
    n_storage[("VersityGW<br/>첨부파일 저장")]:::external
    n_redis[("Redis<br/>고객 인증 요청 제한")]:::data

    n_portal -->|"HTTPS"| n_waf
    n_staff -->|"HTTPS ·<br/>WebSocket"| n_waf
    n_waf -->|"HTTP · WS"| n_nginx
    n_nginx -->|"/api · /ws"| n_api
    n_api -->|"SQL"| n_db
    n_api -->|"S3 API"| n_storage
    n_api --> n_redis

    classDef client fill:#eff6ff,stroke:#60a5fa,color:#17233b
    classDef edge fill:#f8fafc,stroke:#94a3b8,color:#17233b
    classDef app fill:#f0fdfa,stroke:#2bbbad,color:#17233b
    classDef data fill:#fff7ed,stroke:#fdba74,color:#17233b
    classDef external fill:#f5f3ff,stroke:#bda4ee,color:#17233b
    classDef decision fill:#f0fdfa,stroke:#2bbbad,color:#17233b
```

고객 포털과 직원 콘솔은 공통 API를 사용하며 인증과 조회 데이터를 분리합니다. PostgreSQL은 티켓·감사·검색 데이터를, VersityGW는 첨부파일을 보관합니다. Redis는 고객 인증 요청 제한에 사용합니다.

## 기능별 흐름

- [티켓 수정과 동시 수정 충돌 처리](ticket-command.md)
- [활성·종료 티켓 검색](ticket-search.md)
- [고객 문의 조회와 공개 답변 분리](customer-read.md)

## 관련 문서

- [설계 결정](../portfolio/adr-index.md)
- [Core API](../../api/core-api-outline-v1.yaml)

[README](../../README.md#아키텍처)
