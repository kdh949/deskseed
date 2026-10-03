# P04 고객 문의 조회와 모바일 탐색

## Goal / sources

익명·로그인 고객이 모든 화면에서 문의 조회와 도움말 검색을 찾고, 문서 둘러보기로 실제 분류 목록에 들어가며, 이메일에 보이는 DS 문의 번호를 그대로 사용할 수 있다. C-F04/07/09와 C-F10의 모바일 탐색 부분이다.

REQ-TKT-003/008, REQ-KB-001, REQ-UI-005/007; D-061, Accepted ADR 0044, docs 28~31/40/51/55, PUB-000/002/004를 따른다. 기존 `/requests/lookup`, `/requests/:ticketNumber`, `/categories`, `/search`와 frozen `listHelpCategories`, `searchHelpArticles`, `getPublicRequest`만 사용한다. 서버 계약 변경 없음.

## Actor / invariants

CUSTOMER_PORTAL의 익명·인증 고객 읽기 동선이다. 숫자 문의 번호의 표현만 정규화하며 ticket-scoped sessionStorage proof 확인과 서버 authorization/PUBLIC-only projection은 그대로 유지한다. 번호를 proof로 취급하지 않고 토큰을 URL/query/localStorage/log에 저장하지 않는다. no-referrer, 세션 격리, 감사/transaction/idempotency/retention 의미 변화 없음. 신규 API·외부 I/O·법률 문구·seed 없음.

## Reuse / UI contract

기존 customer CustomerSiteLayout/CustomerRequestLookupPanel/DsButton/CustomerIcon을 조합한다. 헤더·푸터에 문의 조회를 표시하고 익명 문서 둘러보기는 `/categories`로 연결한다. 작은 화면에서 검색과 탐색을 숨기지 않고 기존 customer DS의 responsive grid로 배치한다. 번호는 trim 후 숫자·DS-n·#DS-n을 수용하고, zero/unsafe integer/잘못된 접두사·URL은 거부한다. 처음 조회할 때 이메일 링크가 필요함을 작업 중심 문구로 설명한다. staff UI/검색 relevance/부모 경로/공지 준비는 다른 슬라이스다.

## Acceptance / verification

- header/footer 문의 조회와 문서 목록 경로, 익명·인증·긴 이름의 390/768/1448 반응형·키보드 검색을 확인한다.
- 허용 번호 표현은 같은 numeric route로 이동하고 기존 해당 문의 proof가 없으면 조회하지 않는다. invalid/missing 안내는 동일한 비열거 경계를 유지한다.
- UI-002/004/005/006, TKT-003/008의 frontend 경계: 실제 customer Storybook MCP 문서/지침→focused/full/a11y→changed/preview, customer unit/type/build/boundaries, mock Chromium 전체 페이지와 캡처 확인.
- 서버/DB authorization 회귀와 운영 배포는 이번 계약 불변 UI 슬라이스에서 실행하지 않는다. migration/backfill 없음, UI commit revert로 롤백. 성능 수치 주장 없음.

## Completion

구현: 모든 고객의 헤더·푸터 문의 조회, 익명 문서 둘러보기의 `/categories` 연결, 모바일 검색·전체 탐색 노출, 숫자/DS-n/#DS-n 정규화를 추가했다. 모바일 헤더는 문서 흐름 안에 두어 키보드로 이동한 내용을 가리지 않는다. 기존 proof/no-referrer/서버권한과 API 계약은 그대로다.

- Passed: customer unit 130개/28파일, typecheck/customer build, design-system boundaries, 변경 파일 ESLint/Prettier 및 diff check.
- Passed: 실제 customer MCP inventory/지침·문서, focused 5개 및 full 75개 story/a11y. changed-stories의 coverage 경고는 get-stories-by-component에서 실제 shell/lookup 소비자(쉘 71개 story)를 확인하고 전체 실행으로 검증했다.
- Passed: mock Chromium E2E 4개. 익명 390/768/1448의 조회·분류 목록·Enter 검색·proof 없는 조회의 zero request·이메일 fragment 소비·표시 번호 재조회와 인증 고객 긴 이름 320 화면을 확인했다. a11y·가로 넘침 통과. `frontend/test-results/customer-navigation-*/{lookup,signed-in}-navigation.png` 생성 및 대표 320/390/1448 직접 확인.
- Not run: 운영 서버·backend DB authorization·SMTP·배포, Firefox/WebKit, pixel baseline 비교. 로컬 합성 검증을 운영 성공으로 주장하지 않는다.

UI-002/004/005/006과 TKT-003/008 frontend 경계를 검증했다. 기존 앱 분리와 no-referrer 유지, 새 데이터 저장/성능 수치/서버 mutation 없음. UI commit revert로 롤백한다. 핵심 trade-off는 모바일 헤더에 조회·검색을 노출하면서 기존 proof 경계를 바꾸지 않는 것이다.

MCP preview (대표 5개; 전체 변경은 fallback):

- http://localhost:6007/?statuses=affected;modified;new
- http://localhost:6007/?path=/story/customer-design-system-site-layout--anonymous-navigation
- http://localhost:6007/?path=/story/customer-design-system-site-layout--signed-in-navigation
- http://localhost:6007/?path=/story/06-customer-customer-request-lookup-page--formatted-number-with-email-link
- http://localhost:6007/?path=/story/06-customer-customer-request-lookup-page--no-saved-email-link
- http://localhost:6007/?path=/story/customer-portal-help-center-pages--authenticated-mobile-home
