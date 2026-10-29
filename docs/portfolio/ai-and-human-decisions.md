# AI 도구 사용과 릴리스 결정 기록

AI를 코드 탐색, 구현 초안, 테스트 작성과 문서 정리에 사용했습니다. 이번 릴리스에서 진행한 작업과 채택한 결정을 정리했습니다.

## AI를 활용한 작업

- 기존 코드·API 계약·테스트와 릴리스 작업 목록 정리
- 수정할 기능과 문서 내용의 차이 조사
- 변경 전 동작을 재현하는 regression test 작성
- 성능 측정과 운영 작업용 스크립트 작성
- 보안 항목을 읽기 전용으로 점검하고 변경 후 별도 테스트 실행
- 아키텍처·실행 가이드·테스트 결과 문서 정리
- 로컬 build, 테스트, 브라우저·Docker·DB 실행

변경한 코드는 diff와 테스트 명령으로 살펴본 뒤 반영했습니다.

## 릴리스에서 결정한 내용

| 항목 | 결정 | 이유 및 내용 |
|---|---|---|
| 공개 서비스 배포 | 미승인 | 익명 고객의 이메일 소유 확인과 남용 방지 기능 보완 필요 |
| 저장소 라이선스 | MIT (2026-08-12) | 루트 `LICENSE` 적용. 외부 라이브러리의 라이선스는 별도로 유지 |
| 보안 이슈 | 담당자 결정 후 처리 | 남은 이슈는 담당자·수정 일정·배포 범위를 기록하고 위험 수용 여부를 명시적으로 결정 |
| 운영용 secret/KMS provider | 미선정 | 필수 secret 설정은 검사하지만 실제 KMS 제공업체는 운영 환경에서 결정 |
| 백업 RPO/RTO | 복원 연습 기록 | 운영 목표는 데이터 중요도·저장소·인력 구성에 따라 결정 |
| 화면·접근성 | 브라우저 점검 승인 | 84개 baseline과 키보드·확대/축소·reflow·reduced-motion 항목을 살펴봄. VoiceOver/NVDA 미실행 |
| OPS-004 운영 모니터링 | 후속 진행 | 상태 확인 endpoint·경보·중앙 로그는 공개 운영 배포 준비 시 추가 |
| 릴리스 병합 | PR #22 수동 병합 승인 | 테스트 결과와 남은 항목을 검토한 뒤 병합 승인 |

## 릴리스 준비 중 변경한 내용

| 항목 | 변경 내용 |
|---|---|
| PostgreSQL·modular monolith 유지 | 이 릴리스에서는 Kafka·Redis·Elasticsearch·Kubernetes를 추가하지 않음. 이후 성능 측정 결과에 따라 도입 여부 결정 |
| 감사 기록 실패 처리 | 민감정보 조회 중 필수 감사 기록 저장에 실패하면 보호된 데이터를 반환하지 않고 `503` 응답 |
| 내보내기 기능 | 요청 접수와 감사 기록까지 테스트. 파일 생성·다운로드·만료·삭제는 후속 구현 대상 |
| Migration·일반 실행 DB 권한 분리 | 배포 설정의 별도 계정을 유지하고, 권한 부여와 DB 사용자 권한 테스트 추가 |
| 의존성 업데이트 | 기존 npm 보안 경고가 발생한 경로를 같은 major 버전 안에서 업데이트한 뒤 테스트와 build 실행 |
| Core API 멱등 요청 | 별도 receipt 테이블 대신 변경 이력 metadata를 사용. 같은 요청은 저장된 결과를 반환하고 다른 요청이 같은 key를 사용하면 거부. 거부한 요청을 별도 보안 이벤트로 남기는 기능은 추후 작업 |
| 기대 사용자 ID 대조 | 브라우저가 보낸 ID는 로그인 사용자를 선택하는 용도가 아니라 현재 서버 세션과 비교하는 용도로 사용 |
| 공개 문서 | 구현한 API·화면과 이후 계획을 구분해 작성 |

## PR 병합 전 체크리스트

1. 릴리스 테스트 표의 `FAIL`, `LIMITED`, `N/A`, `NOT RUN` 항목 읽기
2. 보안 이슈와 수정한 항목의 regression test 살펴보기
3. 1280/1440/1920 화면 비교와 키보드·스크린리더 관련 기록 살펴보기
4. MIT 라이선스와 공개 서비스 배포 대상 검토
5. 새 환경에서 Quick Start와 복원 smoke test 실행
6. README의 기능 설명을 실제 API·migration·테스트와 대조
7. CI와 리뷰를 마친 뒤 수동 병합

## 관련 문서

- 릴리스 작업 내용: [`tasks/briefs/release-hardening.md`](../../tasks/briefs/release-hardening.md)
- 테스트 결과 모음: [`docs/evidence/release/README.md`](../evidence/release/README.md)
- 전체 테스트 결과: [`docs/evidence/release/verification-summary.md`](../evidence/release/verification-summary.md)
- 설계 결정 목록: [`docs/25-implementation-decision-register.md`](../25-implementation-decision-register.md)
- ADR 목록: [`docs/portfolio/adr-index.md`](adr-index.md)
