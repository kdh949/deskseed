# 프론트엔드 감사 후 수정 결과

기준 커밋 `72a5439ba321a89212494643cb0f2113babcd34e`에서 확인한 F01~F22를 수직 슬라이스로 수정했다. 원래 main 작업 디렉터리와 감사 자료는 보존하고 별도 worktree에서 작업했다. 머지와 배포는 수행하지 않았다. **시각 기준 이미지 12장 갱신은 사람의 검토·승인 대기**다. 자동 승인 검토가 기존 기준선 덮어쓰기를 거절했으며 현재 기준 이미지는 그대로 유지한다.

## PR과 수정 범위

각 PR의 base는 직전 PR의 branch다. 기능 커밋은 각각의 사용자 흐름으로 나눴고 원격 이력은 재작성하지 않았다.

| PR | 수직 슬라이스 | 감사 항목 |
| --- | --- | --- |
| [#170](https://github.com/kdh949/deskseed/pull/170) | 가입 약관별 수락·인증·목적별 이메일 안내 | F01, F05, F06 |
| [#171](https://github.com/kdh949/deskseed/pull/171) | 비밀번호 우선 로그인·passwordless 가입 완료·원래 화면 복귀 | F03, F04, F17 |
| [#172](https://github.com/kdh949/deskseed/pull/172) | 비밀번호 재설정 메일 요청·새 비밀번호 설정 | F02 |
| [#173](https://github.com/kdh949/deskseed/pull/173) | 역할별 진입·관리자 메뉴·알림 연결 복구 | F08~F11, F20, F21 |
| [#174](https://github.com/kdh949/deskseed/pull/174) | 도움말 탐색·본문·평가·캐시, 확인된 접수 완료 | F07, F12~F16, R01 |
| [#175](https://github.com/kdh949/deskseed/pull/175) | 고객·상담 화면 타이포그래피와 모바일 가입 | F18, F19 |
| `feature/frontend-storybook-coverage` | 운영 화면 스타일 복구·전체 Storybook 등록·검토 자료 | F22, 추가 확인된 CSS·대비·날짜 필터 문제 |

## 항목별 결과

| 항목 | 변경 후 행동 | 핵심 검증 |
| --- | --- | --- |
| F01 | 가입 인증 링크를 처리하고 완료·만료·실패 상태 표시 | fragment 제거, browser-bound proof, 중복 소비 방지 |
| F02 | 로그인에서 reset 요청 후 메일의 `/customer/password/reset`에서 비밀번호 변경 | 일회성 proof, 401/429/503, 자동 재시도 없음 |
| F03 | REGISTRATION_REQUIRED를 가입 완료 화면으로 연결 | 현재 약관·비밀번호·profile·CSRF 제출, 티켓 자동 claim 없음 |
| F04 | 비밀번호 로그인을 기본으로 제공하고 passwordless 흐름을 구분 | 실제 password session API, 오류별 복구 |
| F05 | 정책별 본문·버전·필수/선택 동의를 표시 | 필수 수락 검사, 선택 동의는 선택 시만 제출, 가짜 약관 fallback 제거 |
| F06 | 가입과 로그인 메일 안내·재전송 API 구분 | 목적을 유지하는 재요청 |
| F07 | URL만으로 성공을 확정하지 않고 실제 접수 응답의 번호·시각·상태 표시 | 잘못된 번호/직접 진입/응답 불일치와 서버 상태 회귀 |
| F08 | 감사 전용 계정은 감사 화면, ADMIN은 운영 화면으로 진입 | role/capability 및 안전한 복귀 경로 |
| F09 | 권한 있는 ADMIN에게 운영 메뉴 진입점 제공 | 감사 전용 계정과 작업 공간 분리 |
| F10 | 티켓 태그·업무 상태·트리거·시간 자동화 메뉴 연결 | 실제 등록된 route 대상으로 탐색 |
| F11 | 감사 메뉴와 guard를 서버의 SECURITY_AUDITOR 권한에 맞춤 | 감사 전용 shell의 ticket/search/notification 요청 차단 |
| F12 | 도움말 canonical block document를 타입별로 렌더링 | 목록·제목·인용·코드·안전한 링크, 알 수 없는 블록/unsafe URL 처리 |
| F13 | 분류 전체 목록·분류 상세·섹션 상세와 공지 전체 보기 제공 | 제목 검색으로 대체하지 않는 실제 분류 API |
| F14 | 검색·섹션 cursor로 다음 페이지 로드 | 이전 결과 보존, 중복 요청 방지 |
| F15 | 평가 성공 응답 후에만 저장 완료 표시 | 실패 복구·재시도·문서 변경 시 상태 초기화 |
| F16 | 문서 없음과 서비스 장애를 구분 | 404와 일반 오류의 별도 안내 |
| F17 | 로그인 오류 복구와 원래 문의 복귀 보강 | 안전한 내부 목적지, 입력 보존 |
| F18 | 상담 속성·공개 범위·상태 14px, 제목 18px, 답변 입력 16px | 브라우저 computed style·가로 넘침 확인 |
| F19 | 고객 입력 16px·라벨 14px와 모바일 제목 줄바꿈 개선 | 390×844 실제 브라우저 측정 |
| F20 | WS 재연결과 REST 재조회로 알림 복구 | 1~30초 backoff, focus/online/주기 재조회, 이전 계정 응답 폐기 |
| F21 | 잘못된 직원/관리자 URL은 명시적인 찾을 수 없음 화면 | 정상 화면으로 자동 대체하지 않음 |
| F22 | 수동 allowlist를 앱 내부 story glob으로 교체 | source 60개 파일 = index 60개 파일, 278개 story, 누락 0 |
| R01 | 도움말 query를 session/customer별로 분리하고 전환 시 취소·제거 | 이전 audience 데이터가 남지 않는 fixture 회귀 |

추가 발견: 직원 production과 Storybook은 Agent용 CSS만 읽고 있었다. 문서화된 앱 전체 `index.css`로 두 진입점을 맞추고 현재 운영 토큰·primitive·Admin/Audit 스타일을 연결했다. 퇴역 AgentShell CSS는 포함하지 않았다. 날짜 필터 열 폭은 198px에서 250.5px로 확보되어 218~228px 날짜 입력이 옆 필드를 가리지 않는다. 대기 상태 3개 story의 4.44:1 대비 실패도 기존 amber 토큰 조정 후 해소했다. `sr-only` 규칙은 현재 primitive stylesheet로 이동했다.

## 검증 결과와 한계

| 검증 | 상태 | 범위 |
| --- | --- | --- |
| 고객 단위 테스트 | Passed | 27 files, 94 tests |
| 직원 단위 테스트 | Passed | 41 files, 242 tests |
| 고객 MCP Storybook | Passed | interaction 65, 접근성 위반 0 |
| 직원 MCP Storybook | Passed | interaction 278, 접근성 위반 0, source/index 파일 60/60 |
| typecheck / lint / 두 앱 build | Passed | 최종 코드 및 앱 분리 |
| 디자인 시스템 경계 | Passed | 교차 앱 import/token/build manifest 검사, boundary test 4 |
| `contract:check` | Passed | 기존 21개 FROZEN operation의 OpenAPI/MSW 검사. 전체 API runtime parity 주장이 아님 |
| `make docs-check` | Passed | OpenAPI bundle/documentation 계약 검사 |
| 페이지 E2E 기능·접근성 | Passed | `npm run test:e2e:dev -- --ignore-snapshots`: 20 tests. 합성 API 개발 서버 suite이며 기존 시각 비교 실패는 아래에 별도 기록 |
| 기존 스크린샷 비교 | Failed / 승인 대기 | macOS Queue 1280/1440/1920에서 약 2% 차이. 기존 기준선 유지 |
| 시각 후보 생성 | Passed, 기준선 승인 아님 | macOS·Linux 각각 Queue/Workspace 3개 폭, 총 12장. 각 플랫폼의 실제 Chromium에서 생성 |
| #170~#175 원격 CI | Passed | 각 최신 head의 CI gate SUCCESS. #175의 browser/backend/compose는 분류에 따라 Skipped |
| 마지막 PR 원격 CI | 별도 결과 기록 | head별 terminal 결과는 PR checks를 확인 |
| 실제 SMTP/DB 고객 가입·reset·완료 E2E | Not run | Storybook·MSW·페이지 mock 검증으로 대체 주장하지 않음 |
| 전체 수동 screen reader / 모든 장치 시각 검토 | Not run | 자동 axe와 대표 viewport 검토만 수행 |
| 성능·부하·실서비스 배포 | Not run | staff JS 920.80kB, gzip 263.72kB 경고 유지. 실제 LCP/INP 측정 없음 |

초기 로컬 Chromium 실행은 macOS sandbox가 막아 앱 assertion 전에 실패했고 허용된 별도 실행으로 기능 검증을 진행했다. Storybook의 장시간 개발 서버는 메모리 오류 후 CI와 같은 Node 22.23.2로 재시작해 전체 검증을 완료했다. 이러한 환경 실패를 제품 테스트 통과로 계산하지 않았다.

## 시각 검토 후보

[후보 안내와 이미지 목록](evidence/frontend-remediation-2026-09-11/visual-review.md). 후보는 `docs/evidence/.../visual-candidates/`에만 추가했으며 `frontend/e2e/__screenshots__/`는 수정하지 않았다. docs/40의 “사람의 화면 검토 없이 대량 snapshot 갱신을 승인하지 않는다”에 따라 사람의 검토와 갱신 승인이 필요하다. 승인 후 각 플랫폼 후보를 해당 플랫폼 기준선에 반영하고 E2E/CI를 다시 실행해야 한다.

[모바일 입력 크기](evidence/frontend-remediation-2026-09-11/registration-mobile-fonts.json), [상담 글자 크기](evidence/frontend-remediation-2026-09-11/workspace-fonts.json), [감사 날짜 입력 폭](evidence/frontend-remediation-2026-09-11/audit-fonts.json), [Storybook 등록 증거](evidence/frontend-remediation-2026-09-11/storybook-coverage.json).

## 계약·권한·데이터 경계

REQ-AUTH-003/004/005, REQ-CONSENT-002, REQ-TKT-001/003, REQ-KB-001/003/004, REQ-PERM-002, REQ-AUD-002, REQ-COL-003, REQ-UI-001/005/007을 따른다. D-030/032/057 및 Accepted ADR 0042/0043/0044 등 각 task brief의 기존 결정을 사용하고 도메인 결정을 변경하지 않았다. AUTH-005/006/007/008, CONSENT-002, UI-002/004/005/006은 이번 프론트엔드 검증 범위이며 서버 전체 gate 완료를 의미하지 않는다.

CUSTOMER/CUSTOMER_PORTAL과 STAFF/AGENT_WORKSPACE·ADMIN_UI·AUDIT_EXPLORER의 기존 actor/source/request/correlation과 서버 감사 의무를 유지한다. 고객 응답의 PUBLIC-only projection, 직원 역할·capability·resource constraint, background revalidation의 비의미적 read intent는 변경하지 않았다. 계정 전환은 서버 sign-out 완료 후 로그인 화면으로 이동하고 알림·도움말은 소유자별로 갱신한다.

새 endpoint, DB migration, retention, SDK, outbox 또는 audit schema 변경은 없다. 서버의 transaction·동시성·일회성 인증 proof·세션 revoke·CSRF·감사 원자성을 기존 계약대로 사용한다. proof를 URL에서 제거하고 장기 저장하지 않으며 고객 이메일만으로 익명 문의를 자동 claim하지 않는다. 가입 정책 실패, 인증/권한 거부, 재설정 오류를 성공으로 위장하지 않는다. 서버 mutation의 자동 재시도는 추가하지 않았다.

디자인 시스템은 앱별 기존 문서화된 계약을 재사용했다. 재사용 가능한 도움말 문서 renderer와 고객 인증 상태 구성은 고객 앱 내부에 두고, 직원 운영 스타일은 직원 앱 내부 기존 계약만 연결했다. 두 앱의 component/token/assets를 교차 복사하지 않았다. rollback은 해당 프론트엔드 커밋 revert로 가능하며 서버 저장 데이터나 이미 소비한 proof를 되돌리지는 않는다.

## 의도적으로 구현하지 않은 범위

API가 있어도 현재 제품 계획에서 deferred로 구분한 integration/client credential, webhook 운영, consent-admin, 명시적 익명 문의 claim, protected reveal/rebuild, draft/index 운영 등을 새 화면으로 확장하지 않았다. 도움말 첨부 다운로드 계약도 새로 만들지 않았다. R02 번들 최적화는 실제 사용자 성능 측정 후 결정할 항목이다. 커밋·PR 생성까지만 수행하며 머지·배포·부하 테스트는 하지 않았다.

## Storybook 미리보기

변경 목록: [고객](http://localhost:6027/?statuses=affected;modified;new), [직원](http://localhost:6026/?statuses=affected;modified;new). 로컬 서버가 실행 중일 때 열 수 있다.

- [고객 가입](http://localhost:6027/?path=/story/customer-portal-onboarding-pages--registration)
- [가입 인증 완료](http://localhost:6027/?path=/story/customer-portal-registration-verification--verified)
- [가입 완료 오류와 입력 보존](http://localhost:6027/?path=/story/customer-portal-registration-completion--denied-preserves-input)
- [비밀번호 재설정](http://localhost:6027/?path=/story/customer-portal-password-reset--ready)
- [감사 전용 탐색](http://localhost:6026/?path=/story/07-screens-staff-navigation--auditor)
- [도움말 문서](http://localhost:6027/?path=/story/customer-portal-help-document--structured)
- [상담 작업 공간](http://localhost:6026/?path=/story/06-domain-workspace-agentticketeditorworkspace--writable)
- [감사 목록](http://localhost:6026/?path=/story/06-domain-workspace-auditexplorer--with-results)
- [관리자 메뉴](http://localhost:6026/?path=/story/05-shells-layouts-adminshell--mail-operations)
- [그룹 구성원 관리](http://localhost:6026/?path=/story/06-admin-admin-groups-page--manage-members)
- [대기 상태 대비](http://localhost:6026/?path=/story/02-primitives-dsstatusindicator--pending)
