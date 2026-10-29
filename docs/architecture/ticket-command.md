# 티켓 수정과 동시 수정 충돌 처리

```mermaid
flowchart LR
    n_request["상담사 수정 요청<br/>버전·변경 필드"]:::client
    n_guard["권한·버전·변경 필드 검사"]:::app
    n_conflict{"같은 필드 충돌"}:::decision
    n_reject["409 + 충돌 필드<br/>작성 중 답변 유지"]:::client
    n_save[("티켓·댓글·감사 기록<br/>한 트랜잭션에 저장")]:::data
    n_intent["메일·웹훅 전송 작업"]:::app
    n_delivery["외부 전송<br/>실패 시 재시도"]:::external

    n_request --> n_guard
    n_guard --> n_conflict
    n_conflict -->|"있음"| n_reject
    n_conflict -->|"없음 / 병합 가능"| n_save
    n_save -->|"전송 의도 함께 저장"| n_intent
    n_intent -->|"커밋 후"| n_delivery

    classDef client fill:#eff6ff,stroke:#60a5fa,color:#17233b
    classDef edge fill:#f8fafc,stroke:#94a3b8,color:#17233b
    classDef app fill:#f0fdfa,stroke:#2bbbad,color:#17233b
    classDef data fill:#fff7ed,stroke:#fdba74,color:#17233b
    classDef external fill:#f5f3ff,stroke:#bda4ee,color:#17233b
    classDef decision fill:#f0fdfa,stroke:#2bbbad,color:#17233b
```

티켓 버전과 변경 필드를 비교해 겹치는 수정은 409로 반환합니다. 저장이 가능한 요청은 티켓·댓글·감사 기록을 한 트랜잭션에 반영합니다.

## 관련 문서

- [필드별 동시 수정 충돌 처리 · ADR-0007](../adr/0007-field-aware-optimistic-concurrency.md)
- [티켓 변경 서비스](../../backend/src/main/kotlin/dev/deskseed/ticketing/internal/JpaAgentTicketCommandService.kt)

[시스템 구조](overview.md) · [README](../../README.md#아키텍처)
