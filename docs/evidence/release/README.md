# 릴리스 검증 기록

포트폴리오 릴리스를 확인할 때 사용한 명령, 환경과 결과를 모았습니다. 개별 기록의 날짜와 대상 버전을 기준으로 읽습니다.

## Layout

- `verification-summary.md`: 검사별 상태, 실행 명령과 결과
- `security/`: 위협 모델, 확인한 문제와 로컬 보안 검사 결과
- `performance/`: 테스트 데이터 구성, 원본 실행계획과 측정 요약
- `operations/`: 초기 설치·마이그레이션·백업·복원·롤백 연습 기록
- `ui/`: 화면·접근성 자동 검사와 수동 검토 기록
- `supply-chain/`: 의존성·보안 권고·라이선스·이미지 검사 기준

## Reproduction

저장소 루트에서 아래 명령을 실행합니다. 브라우저 테스트는 API 테스트 데이터를 사용하고, P1 계약·고객 첨부파일 스크립트는 각각 별도 Compose 환경을 만듭니다.

```bash
make check
npm --prefix frontend exec playwright install chromium
bash scripts/run-frontend-browser-e2e.sh
bash scripts/run-p1-contract-e2e.sh
bash scripts/run-authenticated-customer-attachment-e2e.sh
bash scripts/run-release-performance.sh
bash scripts/run-operations-rehearsal.sh \
  --evidence-file /tmp/deskseed-operations-full-evidence.md
```

의존성과 보안 권고 검사 명령은 `supply-chain/baseline.md`에 있습니다. 로컬 검사와 별도로 실행합니다.

스크립트의 큰 임시 데이터는 Git에서 제외하는 `build/release-evidence/` 아래에 생성할 수 있습니다. 이 디렉터리에는 검토에 필요한 결과만 저장하고, 비밀번호·접근 토큰·`Authorization` 헤더·세션 쿠키·댓글 본문·검색 원문·실제 고객 정보는 포함하지 않습니다.

## Result vocabulary

| 상태 | 의미 |
| --- | --- |
| `PASS` | 명령이 성공했고 결과 파일에서도 해당 내용을 확인함 |
| `FAIL` | 명령 또는 통과 기준 실패. 담당자가 위험을 명시적으로 수용한 경우를 제외하면 릴리스 중단 |
| `LIMITED` | 검사를 실행했지만 환경 제약으로 일부 확인이 제한됨 |
| `N/A` | 해당 릴리스의 미구현 기능으로 검사 대상에서 제외 |
| `NOT RUN` | 실행 기록 없음 |

생성된 결과를 갱신할 때는 diff를 검토하고, 결과의 해석에 영향을 주는 코드·문서 변경과 함께 커밋합니다.
