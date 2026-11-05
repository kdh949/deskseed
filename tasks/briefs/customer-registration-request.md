# 고객 회원가입 요청

## Goal

고객이 현재 동의 약관, 비밀번호와 프로필을 제출하면 가입 요청·인증 토큰·메일 발송 요청을 함께 저장합니다. 계정이 이미 있는 이메일은 가입 데이터를 만들지 않고, 응답과 쿠키 형식은 신규 이메일과 같게 유지합니다. 쿠키는 가입을 시작한 브라우저를 확인하는 데 사용합니다.

## Decision and source references

- Decisions: D-057, D-058, D-060
- Accepted ADRs: ADR 0042, ADR 0043
- Requirements: REQ-AUTH-003, REQ-CONSENT-002, REQ-NOTIF-001
- Plan/API: docs/56 Task 8, `requestCustomerRegistration`
- Gates: AUTH-005, CONSENT-002, MAIL-001, MAIL-002, ARCH-004, CFG-006, DOC-001

## Actor and source

- 익명 고객의 요청은 `SYSTEM` actor와 `CUSTOMER_PORTAL` source로 기록합니다.
- 공개 API이며 고객·직원 세션이나 외부 scope를 권한으로 사용하지 않습니다.
- 제출한 약관 키는 현재 `REGISTRATION` 정책에 있어야 합니다. 현재 필수 약관은 각각 한 번씩 제출해야 합니다.
- 서버에서 검증한 request/correlation context를 가입 요청, 토큰, 메일 발송 요청과 감사 기록에 함께 저장합니다.

## Product and API contract

- `POST /api/v1/customer/registrations`는 확정한 고객 인증 계약을 따릅니다.
- 신규·기존·비밀번호 미설정·가입 대기 이메일 모두 같은 `202`, no-store 헤더와 43자 continuation cookie 형식을 반환합니다.
- 잘못되거나 오래된 동의 입력은 `400`, 요청 제한 초과는 `429`, 필수 limiter·약관·저장 장애는 `503`으로 응답합니다. 계정 상태는 오류에 노출하지 않습니다.
- 이 작업은 Backend API를 구현한 단계이며 프런트엔드 화면은 후속 작업입니다.

## In scope

- 이메일·프로필·비밀번호 입력 검사와 현재 동의 정책 조회
- 비밀번호 해시 계산과 DB 트랜잭션 전에 목적별 Redis 요청 제한 적용
- 신규·기존 계정 모두 실제 Argon2id 계산 수행
- 정규화된 이메일별 advisory lock을 사용한 계정 확인과 가입 대기 요청 교체
- `EMAIL_VERIFICATION` 토큰의 digest, 보호된 인증 메일 outbox와 본문 없는 보안 감사 저장
- HttpOnly, Secure, SameSite=Lax와 경로가 지정된 continuation cookie
- 약관, 계정 존재 여부 노출, 롤백, limiter 장애와 secret 출력에 대한 통합 테스트

## Out of scope

당시 후속 작업은 이메일 인증 완료, 고객·계정 생성, 동의 확정, 가입 쿠키 만료 응답, 비밀번호 로그인·재설정, passwordless 매직 링크 변경과 현재 고객 조회입니다. SMTP 전송, 프런트엔드 UI와 운영용 약관 데이터도 이 단계에 포함하지 않았습니다.

## Invariants and failure semantics

- 가입 요청에는 Argon2id 해시와 continuation secret의 digest만 저장합니다. 일회용 토큰도 원문 대신 digest를 저장합니다.
- 기존 계정도 같은 약관 검사와 비밀번호 해시 계산을 수행합니다. 가입 요청·토큰·메일은 만들지 않고, 신규 계정과 구별되지 않는 임시 continuation 값을 반환합니다.
- 필수 약관을 확인하지 못하거나 중복·미등록·버전 불일치·필수 항목 누락이 있으면 요청을 거부합니다.
- 계정 확인과 가입 대기 요청 교체는 모든 계정 생성 경로와 같은 `customer-account:<normalized-email>` 락 키를 사용합니다. 계정 생성이 먼저 락을 잡았다면 해당 커밋을 기다린 뒤 계정 유무를 다시 확인합니다.
- 가입 요청, 토큰, 메일 outbox와 `CUSTOMER_REGISTRATION_REQUESTED` 감사는 함께 커밋하거나 롤백합니다.
- Redis의 요청 횟수는 별도 트랜잭션에서 차감합니다. 이후 DB·감사·outbox 저장이 실패해도 차감한 횟수는 복구하지 않습니다.
- 메일은 DB 커밋 후 전송합니다. 가입 요청 트랜잭션에서는 메일 서버를 호출하지 않습니다.

## Data and privacy

- 신규 가입이 가능한 이메일의 대기 프로필만 저장합니다. 기존 계정의 제출 내용은 이 흐름에서 저장하지 않습니다.
- 비밀번호, 원문 이메일 토큰과 continuation secret은 일반 로그·감사 기록·DB 평문 컬럼에 남기지 않습니다.
- 인증 메일 내용은 암호화해 저장하고 일반 메일 본문에는 정해진 placeholder를 넣습니다.
- 제출한 약관 선택은 가입 요청의 변경 불가 기록으로 보존합니다. 계정의 최종 동의 내역은 인증 완료 단계에서 저장합니다.
- 티켓·webhook·export·직원용 응답의 데이터 범위는 유지합니다.

## Acceptance scenarios

- 신규 이메일과 유효한 필수 약관을 제출하면 `202`, 보호된 인증 데이터·outbox와 본문 없는 감사 기록을 생성합니다.
- 기존 계정도 같은 응답·쿠키 형식을 받으며 가입 데이터와 메일은 생성되지 않습니다.
- 약관이 오래됐거나 미등록·중복·조회 불가 상태이면 가입 데이터를 저장하지 않습니다.
- 목적·수신지·요청자 네트워크별 예산을 초과하면 계정 정보 없이 `429`와 `Retry-After`를 반환합니다.
- Redis 장애는 DB 가입 처리 전에 `503`으로 응답합니다.
- 감사 또는 outbox 저장 실패는 `503`이며 가입 요청·토큰·outbox·감사를 모두 롤백합니다. Redis 요청 횟수는 차감된 상태를 유지합니다.

## Validation

- 개별 테스트: `CustomerAuthPropertiesTest`, `CustomerRegistrationRequestIntegrationTest`, `CustomerAuthenticationLimiterUnavailableIntegrationTest`
- Task 8 head: fast, contract, integration, Mailpit, docs 검사
- Checkpoint C 최종 head: AUTH-005/006/007/008과 계획 전체 checkpoint 검사

## Compatibility and migration

구현한 `requestCustomerRegistration`은 실제 payload·status·problem·보안 동작과 대조해 `FROZEN`으로 표시합니다. 미구현 작업은 아직 계약 확정 대상이 아닙니다. 고객 인증 계약은 별도 원본에서 관리하며 Core 자동 생성 번들의 변경분은 없고 원본 일치 검사를 유지합니다.

V81/V81.1 저장 구조를 사용하며 새 마이그레이션이나 backfill은 없습니다. 애플리케이션을 롤백해도 대기 데이터는 기존 보호·만료·취소 규칙을 따릅니다.

## Human explanation

Argon2id는 계정 상태를 응답하기 전에 실행하되 DB·Redis 트랜잭션 밖에서 계산합니다. 계정 유무에 따른 응답 비용 차이를 줄이면서 트랜잭션 점유 시간을 제한하기 위해서입니다.

이메일 링크만으로 다른 브라우저에서 정한 비밀번호가 활성화되는 일을 막기 위해, 가입을 시작한 브라우저의 continuation secret도 확인합니다. 실제 활성화는 후속 PR에서 구현합니다. 요청 제한은 D-060에서 정한 Redis를 사용하며, 지원 배포 환경의 처리량 목표는 당시 `Not run`입니다.
