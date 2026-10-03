# UX 회귀의 브라우저 CI 자동 포함

## Goal / actor / sources

새 고객·상담사·관리자 mock browser 회귀가 파일명 목록을 따로 고치지 않아도 CI에 포함된다. actor는 합성 CUSTOMER/STAFF fixture이며 운영 API 쓰기는 없다.

- REQ-UI-005/006/007, UI-004/005/006, DOC-001. D-059/061/062 및 ADR-0044의 앱 격리와 기존 개발 fixture 경계를 유지한다.
- docs/21 및 현재 `.github/workflows/ci.yml`의 Frontend browser E2E job.
- API operation/권한/계약/화면은 변경하지 않는다. 공개·내부·감사 데이터 projection, transaction, concurrency, idempotency, retry, retention 불변.

## Scope / minimal choice

`test:e2e:dev`의 고정된 spec 파일 목록을 Playwright 기본 탐색으로 교체한다. 제목에 `real stack`이 있는 실제 Compose 전용 시나리오는 명시적으로 제외한다. 기존 `test:e2e`와 소유권 격리 real-stack runner는 그대로 둔다.

별도 test runner나 파일명 registry를 만들지 않는다. 이 변경은 P01–P20에서 추가한 mock spec이 향후 CI에서 빠지지 않도록 하는 검증 후속이다. 기존 이메일 링크 모드 전환 테스트 보완을 포함한 #241 위에 적층한다.

## Acceptance / verification

- `npm run test:e2e:dev -- --list`가 기존 고객 portal/auth continuation와 신규 auth layout spec을 포함하고 real stack 시나리오를 제외한다.
- 해당 branch의 전체 mock Chromium 시나리오와 CI용 Linux visual baseline 확인.
- package format, docs-check, diff-check. 제품 코드/컴포넌트/스토리 변경이 없어 Storybook·unit·backend gate의 로컬 재실행은 해당 없음.
- 환경 변수로 실제 서버/인증을 주입하지 않는다. 모든 시나리오는 기존 fixture/route mock을 사용하며 운영·이메일 전달·실제 DB 검증으로 주장하지 않는다.

## Compatibility / rollback / result

의존성/API/migration 변경 없음. package script revert로 이전 목록 방식 복구 가능. 불필요한 수동 목록 갱신 대신 이미 설정된 Playwright testDir와 기존 이름 경계를 재사용한다. 성능 측정은 하지 않았고 검증 시간은 실제 테스트 결과로만 기록한다.

- `test:e2e:dev -- --list`: mock 29개 포함, real stack 0개 확인. 이전 고정 목록에서 제외됐던 고객 magic link/첨부·인증 continuation·auth layout을 포함한다.
- `PLAYWRIGHT_DEV_SERVER_PORT=45310 npm run test:e2e:dev`: Chromium 29개 PASS(54.4초). 기존 macOS 시각 기준선 포함.
- package format, docs-check, diff-check PASS. Linux는 PR의 실제 CI 결과로 별도 확인한다.
- 제품/스토리/API/backend 코드 변경 없음. backend/Storybook/실제 Compose·운영 검증은 이 변경의 로컬 확인에서 실행하지 않음.
