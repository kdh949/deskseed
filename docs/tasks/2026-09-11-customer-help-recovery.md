# 고객 도움말 탐색·본문·상태 복구

## 시나리오와 범위
F12/F13/F14/F15/F16 및 R01, REQ-KB-001/003/004. 카테고리 전체 → slug 기반 섹션 → published 문서, 검색·섹션 cursor 다음 페이지, 구조화된 본문과 실제 저장된 피드백 상태를 제공한다. D-032, ADR 0018/0044, docs33/40 및 FROZEN listHelpCategories/getHelpCategory/getHelpSection/searchHelpArticles/getHelpArticle/recordHelpArticleFeedback 계약 사용. 새 API 없음.

## 데이터 경계와 재사용
PUBLIC 또는 현재 CUSTOMER_SESSION audience, 서버 permission projection이 기준이다. 고객 DsButton/ScreenState/RetryButton과 기존 help 페이지 구성 재사용; 고객 앱 내부 canonical document decoder/renderer 추가. 직원 앱 컴포넌트를 가져오지 않는다. 제목/목록/code/quote/callout/divider/HTTPS link를 안전한 React text로 표시한다. 공개 문서는 첨부를 금지하며 별도 help attachment download 계약이 없어 다운로드 API를 발명하지 않는다.

## 실패·동시성·개인정보
404와 서비스 오류를 분리하고 retry 제공. 피드백은 pending/success/error이며 자동 retry 없이 수동 재선택, 문서 변경 시 초기화. search와 section은 서버 cursor와 query를 보존한다. 고객 상태/ID별 query key와 actor 변경 시 help query cancel/remove로 이전 audience cache를 제거한다. AbortSignal을 읽어 늦은 응답을 폐기한다. token/검색 본문을 로그로 남기지 않는다. 기존 서버 감사/transaction/retention/cache-policy 유지, projection에 없는 문서 노출 없음.

## 검증과 호환성
UI-002/004/006, REQ-KB-004 frontend safe-renderer: 구조 보존/HTML text 처리/위험 URL 차단, 6번째 카테고리·공지 다음 페이지·검색 cursor, 피드백 pending/실패/문서 전환, 세션 logout cache 제거 unit, MCP interaction/a11y 및 preview, typecheck/lint/build/boundary. 실 DB audience E2E 및 검색 부하/전체 backend gate 미실행. migration 없음, UI revert 가능. 사용자에게 없는 문서와 서버 실패를 구분해 설명한다.

## 리뷰 보완
도움말 6개 화면은 audience가 anonymous 또는 고객 ID가 확인된 authenticated 상태일 때만 조회한다. loading/error에서는 기존 결과와 refetch 액션을 숨기고 세션 확인·재시도를 안내한다. 세션 확인 시작/완료/실패 때 도움말 요청을 취소하고 캐시를 제거해 최초 error/null 상태도 복구 시 재사용하지 않는다. 실제 세션 Provider를 사용하는 오류→익명 복구 회귀를 추가한다. section slug는 기존 V52의 전역 unique index가 보장하므로 추가 migration이나 API 변경은 필요하지 않다.
