# 고객 비밀번호 재설정 수직 슬라이스

## 시나리오 / 범위
F02, REQ-AUTH-003. 고객이 로그인에서 재설정 메일을 요청하고 기존 메일 URL /customer/password/reset의 fragment proof로 비밀번호를 바꾼다. 요청과 재요청은 같은 password-reset 목적 API를 사용한다. D-032/057, ADR 0042, docs56, FROZEN requestCustomerPasswordReset/resetCustomerPassword를 따른다.

## 경계 / 재사용
CUSTOMER_PORTAL, anonymous one-time proof. 기존 고객 DsButton/Notification/ScreenState 사용; 새 DS API 없음. token은 URL에서 동기 제거하고 component memory에만 둔다. password는 12~128자. 로그인 세션 생성/claim 없음. 서버 원자적 credential 교체 및 전체 세션 revoke, CUSTOMER_PASSWORD_RESET_REQUESTED/COMPLETED 감사, durable mail intent 유지. API/DB/retention 변경 없음.

## 실패 / 동시성
계정 존재와 무관한 같은 accepted 안내. pending 중 중복 제출 방지, 자동 retry 없음. 429/503은 입력 유지하고 수동 재시도. invalid proof는 폐기하고 새 메일 요청 경로. 성공 후 현재 세션을 재조회한다. 외부 주소나 원문을 로그/스토리지로 전달하지 않는다.

## 검증 / 호환성
UI-002/004/006, AUTH-004/006 frontend 부분: customer unit, typecheck, lint, boundary, customer build, MCP interaction/a11y 및 preview. 실 DB/SMTP 및 전체 backend gate 미실행. 기존 메일 URL 호환, migration 없음, UI revert 가능. 사용자에게 재설정 성공과 별도 로그인을 구분해 설명한다.
