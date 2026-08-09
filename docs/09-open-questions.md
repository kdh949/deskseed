# Open Questions and Proposed Defaults

이 문서의 항목은 구현을 막지 않는다. 별도 답이 없으면 `Proposed default`로 시작하고, 실제 운영 요구가 나오면 ADR과 migration을 통해 바꾼다.

## 1. Product identity

| Question | Proposed default |
|---|---|
| 최종 제품명과 package namespace | 임시 `Deskseed`, `dev.deskseed` |
| 기본 UI 언어 | 한국어, message key로 국제화 가능하게 구성 |
| 첫 공개 배포 형태 | single-node Docker Compose self-hosted |

## 2. Customer security

| Question | Proposed default |
|---|---|
| 익명 조회 token 만료 | 개발 30일; 운영 전에 설정화 |
| 고객 답변 인증 | magic link 또는 검증된 session, MVP 후 |
| 동일 이메일 프로필 병합 | 자동 병합 금지; 검증 후 명시적 연결 |
| 해결 티켓 고객 답변 | 재오픈 정책을 관리자 설정으로, 기본 재오픈 |

## 3. Staff and audit security

| Question | Proposed default |
|---|---|
| Security Auditor가 ticket UI도 볼 수 있는가 | 별도 ticket read 권한 없이 Audit Explorer projection만 |
| audit content reveal 재인증 | privileged role은 최근 로그인 확인, MFA 후 강화 |
| 민감 read에서 audit DB 장애 시 | `STRICT`: 응답 실패, silent loss 금지 |
| audit export 승인 | 단일 auditor 권한으로 시작; 2인 승인 later |
| IP 저장 | 원문 저장하되 retention/마스킹 설정 제공 |

## 4. Search query audit

| Question | Proposed default |
|---|---|
| 검색어 원문 저장 여부 | 암호화 저장 + 마스킹본 + HMAC fingerprint |
| 암호화 key | DB 밖 환경/KMS에서 공급; key 없으면 raw 저장 기능 비활성 또는 startup failure 정책 결정 |
| 일반 auditor에게 원문 표시 | 기본 비표시 |
| 원문 공개 권한 | `audit:search-query:reveal` + reason + self-audit |
| raw query retention | 30일 proposal; metadata는 180일 proposal |
| search event와 ticket view 연결 | `originSearchEventId` 필수 when opened from results |

검색어가 카드번호·주민번호·비밀번호 같은 민감 데이터를 포함할 수 있으므로 “감사니까 무조건 평문 저장”은 채택하지 않는다.

## 5. Access event semantics

| Question | Proposed default |
|---|---|
| 같은 티켓 tab의 polling을 매번 view로 남길까 | 아니오. interaction별 semantic view 1건 |
| 새로고침 | 새 interaction이면 새 view |
| 목록 row preview | 본문/고객 PII를 가져오지 않으면 queue view만; 상세 data를 가져오면 별도 preview event 검토 |
| API client GET | `API_RESOURCE_READ` 매 요청 기록 |
| 권한 실패 | `ACCESS_DENIED` security event, 보호 대상 ID 최소화 |

## 6. Integration authentication

| Question | Proposed default |
|---|---|
| 첫 machine auth | scoped API key |
| key expiry | 생성 시 필수; 최대 365일 proposal |
| rotation overlap | 24시간 proposal |
| IP allowlist | optional per client |
| OAuth client credentials | Integration v2 |
| delegated staff OAuth | third-party interactive app 요구가 생길 때 |
| API key로 staff impersonation | 금지 |

## 7. Integration resource scope

| Question | Proposed default |
|---|---|
| 외부 client가 볼 수 있는 티켓 | 명시된 group/ticket kind/external system reference로 제한 가능 |
| external client public comment | 별도 고위험 scope; 기본 internal only |
| customer PII | `customers:read`와 field allowlist 필요 |
| audit API | 기본 미제공; 별도 audit scopes와 운영자 승인 |
| admin API | Platform v1에서 제공하지 않음 |

## 8. External references

| Question | Proposed default |
|---|---|
| 지원 object types | ORDER, PAYMENT, REFUND, USER, STORE, OPS_CASE, CUSTOM |
| deep link host | ExternalSystem allowlist 필수 |
| metadata snapshot 크기 | 8 KiB proposal, allowlisted scalar fields only |
| 서버가 외부 데이터를 fetch | 기본 금지 |
| 동일 외부 object를 여러 티켓에 연결 | 허용 |
| 동일 티켓에 같은 reference 중복 | 금지 |

## 9. API contract

| Question | Proposed default |
|---|---|
| update concurrency | ETag/If-Match, 내부에서는 ticket version과 매핑 |
| idempotency in-progress duplicate | 409 + Retry-After |
| idempotency retention | 7일 proposal; operation별 연장 가능 |
| public SDK languages | TypeScript, Python, JVM/Kotlin |
| SDK release | API contract와 같은 semver release |
| breaking change | `/api/v2` 또는 major SDK; v1 silent break 금지 |

## 10. Retention proposal requiring operator confirmation

| Category | Proposed launch default | Note |
|---|---:|---|
| Ticket change audit | ticket lifecycle + 5년 또는 indefinite | 운영 정책 결정 필요 |
| Admin/security audit | 365일 | privileged change investigation |
| Access metadata | 180일 | storage/privacy trade-off |
| Raw search query ciphertext | 30일 | highly sensitive |
| Audit export files | 7일 | short-lived object URL |
| Webhook attempts | 90일 | delivery troubleshooting |
| Idempotency records | 7일 | retry window보다 길어야 함 |

이 숫자는 법률 자문이 아니라 제품 기본값 proposal이다.

## 11. App/Embed SDK

| Question | Proposed default |
|---|---|
| 첫 extension location | ticket sidebar |
| app execution | sandboxed iframe only |
| third-party secret | browser에 전달 금지; server-side proxy |
| embed token lifetime | 5분 proposal |
| embed initial capability | ticket create/list/detail read; full write later |
| external user attribution | signed subject + mapping; delegated OAuth later |

## 12. Decisions that should be answered before production exposure

다음은 local portfolio 구현에는 기본값으로 진행할 수 있지만 실제 조직 배포 전에는 운영자가 명시적으로 선택해야 한다.

- 감사·검색어·IP 보존기간
- 암호화 key 관리 방식과 rotation
- privileged audit reveal에 MFA/재인증 요구 여부
- integration API 인터넷 공개 여부와 network allowlist
- 외부 client public comment 권한 승인 프로세스
- audit archive의 외부 저장소와 복구 절차
- 개인정보 삭제 요청과 감사 보존의 충돌 처리
