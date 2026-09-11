# 고객·직원 입력과 상태 문구 가독성

F18/F19, REQ-UI-001/004/005, D-030/031/032, ADR 0019/0044, docs28~31/40/51. 고객·상담사·관리자·감사 담당자가 입력·공개 범위·상태·복구 안내를 읽는 시나리오. 앱별 기존 토큰과 컴포넌트만 조정하며 새 제품 기능/API를 추가하지 않는다.

직원 작은 크기 토큰과 11~13px 고정값을 최소 14px로 조정하고 제목 18px, rich editor 16px 적용. 레거시 관리자/감사 UI의 xs 토큰도 14px. 고객 라벨/설명 14px, 입력 16px, 모바일 가입 제목 26~32px 및 단어 유지 줄바꿈. 새 reset form과 구조화 도움말 본문의 입력/코드/링크 레이아웃도 함께 확인. 로고·색상·폰트 패밀리 변경 없음.

UI-001/002/004/005/006: 양 앱 MCP full interaction/a11y, changed-stories/preview, desktop/mobile computed font와 overflow 확인, typecheck/lint/build/boundary. 브라우저 확인은 로컬 합성 fixture; 실제 iOS 자동 확대·모든 화면의 200% zoom·실 DB E2E는 미실행. 스토리 등록 범위는 별도 보강 슬라이스로 추적한다.

권한·PUBLIC/INTERNAL projection·actor/audit·transaction/concurrency/idempotency/retry·privacy/retention·API schema 변경 없음. migration 없음. CSS revert 가능. 한 화면의 밀도보다 중요 텍스트 가독성을 우선하며 긴 제목은 잘라 숨기지 않고 줄바꿈한다.
