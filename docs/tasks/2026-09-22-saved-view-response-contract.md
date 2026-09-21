# 저장 보기 응답 타임스탬프 계약 복구

상담사가 저장 보기를 생성하거나 목록을 열 때 서버 응답을 Staff Console이 유효한 `SavedAgentView`로 해석하고 사이드바에 표시한다.

REQ-VIEW-001과 D-054를 유지하며 Core OpenAPI `SavedView` 계약, `POST /api/v1/agent/views`, `GET /api/v1/agent/views` 응답을 맞춘다. 기존 PERSONAL/SHARED 소유권, 권한, 감사, 트랜잭션, 동시성 규칙은 변경하지 않는다.

서버 응답에 이미 도메인 모델이 보유한 `createdAt`과 `updatedAt`을 포함하고 두 필드를 OpenAPI 필수 응답 필드로 고정한다. 스키마 migration, 외부 I/O, 보관 정책, UI 컴포넌트 변경, 중복 이름 오류 매핑은 범위 밖이다.

검증은 PostgreSQL `AgentTicketReadIntegrationTest`에서 create/list 응답의 두 필드를 확인하고, `DOC-001` 계약 검증을 실행한다. additive response 복구이므로 rollback은 두 응답 필드와 OpenAPI required 항목 제거이며 데이터 migration은 없다.
