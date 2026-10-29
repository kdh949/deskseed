# 고객 문의 조회와 공개 답변 분리

```mermaid
flowchart LR
    n_customer["고객 계정 / 문의별 접근 토큰"]:::client
    n_authorize["문의 접근 권한 확인"]:::app
    n_public["고객용 조회<br/>PUBLIC 답변만 선택"]:::app
    n_customerui["문의 상태·공개 답변"]:::client
    n_staff["직원 세션·권한 확인"]:::client
    n_staffread["직원용 조회<br/>PUBLIC + INTERNAL"]:::app
    n_audit[("필수 열람 이력 저장")]:::data
    n_workspace["상담 워크스페이스"]:::client

    n_customer --> n_authorize
    n_authorize --> n_public
    n_public --> n_customerui
    n_staff --> n_staffread
    n_staffread --> n_audit
    n_audit -->|"저장 성공"| n_workspace

    classDef client fill:#eff6ff,stroke:#60a5fa,color:#17233b
    classDef edge fill:#f8fafc,stroke:#94a3b8,color:#17233b
    classDef app fill:#f0fdfa,stroke:#2bbbad,color:#17233b
    classDef data fill:#fff7ed,stroke:#fdba74,color:#17233b
    classDef external fill:#f5f3ff,stroke:#bda4ee,color:#17233b
    classDef decision fill:#f0fdfa,stroke:#2bbbad,color:#17233b
```

고객 계정 또는 문의별 접근 토큰으로 조회 권한을 확인합니다. 고객에게는 문의 상태와 공개 답변을 반환하고, 내부 메모와 직원용 정보는 직원 조회에서 처리합니다.

## 관련 문서

- [고객·직원 앱 분리 · ADR-0044](../adr/0044-customer-portal-frontend-surface-isolation.md)
- [상담사 조회와 접근 이력 저장](../../backend/src/main/kotlin/dev/deskseed/staffaccess/internal/AgentTicketReadApplicationService.kt)

[시스템 구조](overview.md) · [README](../../README.md#아키텍처)
