# License Decision — Open

이 저장소 시드에는 의도적으로 `LICENSE`가 없다. 실제 공개 배포 전에 프로젝트 목표에 맞는 라이선스를 선택한다. 이 문서는 법률 자문이 아니다.

## Option A — AGPL-style network copyleft

적합한 의도:

- 수정한 self-hosted/network service 버전도 사용자에게 소스 제공을 요구하고 싶다.
- 호스팅 사업자가 개선을 독점하는 것을 줄이고 싶다.

고려점:

- 일부 기업의 도입/기여 장벽이 높을 수 있다.
- 의존 코드와 배포 방식의 license compatibility 검토가 중요하다.

## Option B — Apache-2.0

적합한 의도:

- 상업적/사내 사용과 파생 제품을 넓게 허용하고 싶다.
- 명시적인 patent grant를 선호한다.

고려점:

- 제3자가 개선 소스를 공개하지 않아도 된다.

## Option C — MIT

적합한 의도:

- 매우 단순하고 permissive한 사용 조건을 원한다.

고려점:

- 개선 환원 요구가 없고 patent 조항이 Apache-2.0보다 단순하다.

## Decision questions

1. 다른 회사가 비공개로 호스팅/판매해도 괜찮은가?
2. community edition과 enterprise edition을 나눌 계획인가?
3. 기여자 patent/CLA 정책이 필요한가?
4. 참고하거나 포함한 dependency/source code의 license와 호환되는가?

결정 후 해야 할 일:

- root `LICENSE`
- README license section
- source headers 필요 여부
- dependency/license scanner
- contribution terms and DCO/CLA
