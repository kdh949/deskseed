# 실제 접수 응답에 기반한 고객 완료 화면

F07, REQ-TKT-001, D-032 및 ADR 0044. 고객/익명 문의 CreateCustomerRequest 성공 직후 접수 결과를 표시한다. 해당 API 계약과 PUBLIC projection은 변경하지 않는다. 기존 CustomerIcon/ScreenState와 완료 레이아웃 재사용.

라우트의 양수 safe integer와 응답 번호·상태·접수일을 검증한다. 응답이 없거나 불일치하면 성공을 꾸미지 않고 문의 조회를 안내한다. 접수 응답의 상태와 현재 상태를 구분하며 authenticated 고객의 상세는 /account/requests로 연결한다. Router state에는 응답의 번호·상태·날짜만 전달하고 access token은 기존 전용 저장소만 사용한다. mutation/retry/idempotency/transaction/actor/audit/privacy/retention 서버 경계 유지.

UI-004/006: 잘못된 번호, 없는/불일치 응답, 잘못된 날짜, SOLVED replay unit 및 MCP interaction/a11y, typecheck/lint/build/boundary. 실제 문의 생성 DB E2E 미실행. DB migration 없음, UI revert 가능. 상세 화면은 현재 상태의 source of truth이며 완료 화면은 접수 응답만 설명한다.

## 리뷰 보완
모든 접수 API가 발급한 문의별 조회 proof를 사용해 문의를 연다. 이후 로그인한 계정으로 소유권을 추정하지 않으며, proof가 없는 브라우저는 문의 조회 복구로 이동한다. 익명 접수 뒤 다른 계정 로그인과 proof 없는 상태를 단위 테스트 및 Storybook으로 검증한다.
