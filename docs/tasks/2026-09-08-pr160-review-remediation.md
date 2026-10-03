# PR 160 협업 요청 결과 확인

## Goal and sources

STAFF가 응답을 잃은 협업 요청을 재확인해도 같은 자식 티켓을 새 요청 ID로 다시 만들지 않는다.
REQ-TKT-012, REQ-CHILD-001/003/006; D-004/007, CONC-001, CHG-001, UI-002/004 및 docs/31을 따른다.
기존 BACKGROUND ticket GET과 POST children/transfer를 사용한다.

## Scope, invariants and recovery

- 네트워크/5xx 이후 뒤따른 거절은 이전 시도의 미커밋을 증명하지 않으므로 attempt와 원본 payload를 보존한다.
- 서버의 409 client-command-id-reused를 받으면 새 ID 발급과 반복 제출을 막고 관련 협업 티켓의 새로고침/열기를 제공한다.
- 상담사가 기존 협업 요청의 접수를 확인했다고 명시적으로 선택해야 미확정 입력을 정리한다. 일반 새로고침이나 패널 닫기는 정리하지 않는다.
- 최초의 확정 validation/version 거절은 기존 수정·재조회 흐름을 유지한다.
- 서버 replay 정책/DB/계약을 확대하지 않는다. parent ownership, staff authorization, PUBLIC/INTERNAL 및 audit 경계는 기존 경로 그대로다.
- Reuse/Compose: 기존 SeedNotice/Button/Drawer와 관련 티켓 링크. 새 공통 컴포넌트 없음.

## Acceptance and validation

- 503 → 실제 서버와 같은 409 → 관련 티켓 새로고침은 두 요청의 ID/expectedVersion/payload를 유지하고 세 번째 생성 요청을 보내지 않는다.
- 결과 확인 전 편집/제출은 잠기고, 명시적 확인 후 다음 요청 폼은 비어 있다.
- 수정 전 실제 409 응답 story가 실패했고 수정 후 협업 재시도·이관·확정 버전충돌 MCP 3개와 typecheck가 통과했다.
- 전체 프런트 회귀는 스택 통합 후 기록한다. 서버 브라우저 E2E/부하/배포는 실행하지 않는다.
