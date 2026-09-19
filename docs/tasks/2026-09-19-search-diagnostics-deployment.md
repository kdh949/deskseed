# 검색 SQL 진단 계측 배포

## 목표와 범위

운영자가 개인 스테이징에서 agent 검색의 count/page/audit 지연을 구분하도록 계측한다. 서버 기준 SHA는 `2c417b249dfefd7743804c11e4608675fb049ddc`다. SQL, 인덱스, 검색 권한, 응답 계약 및 다른 제품 기능은 변경하지 않는다.

- Actor/source: 기존 STAFF 세션 / AGENT_UI. 필수 SEARCH_EXECUTED 감사와 transaction의 fail-closed 동작을 유지한다.
- Decisions: D-005, D-018, D-039, D-064, D-065; Accepted ADR-0047/0048.
- Requirements: REQ-OPS-002, REQ-PERF-002. Verification: OPS-004의 metrics/log/trace/privacy 부분과 ACC-007. CPU profiling을 포함한 OPS-004 전체 검증은 범위 밖이다.
- 활성화: 관측 profile에서 `DESKSEED_SEARCH_DIAGNOSTICS_ENABLED=true`. 기본값은 false다.
- 고정 allowlist의 검색군만 metric label에 사용한다. run ID/case index/request ID는 bounded trace attribute이며 SQL, 검색어, bind 값, 예외 메시지, 사용자/티켓 ID를 수집하지 않는다.
- JDBC 단계는 client wait/row mapping을 포함하며 audit 단계는 최종 transaction commit을 제외한다. 이 시간만으로 SQL 연산자 원인을 확정하지 않는다.
- telemetry 저장 실패는 검색 결과나 원래 예외를 바꾸지 않는다. 민감한 예외를 trace event로 남기지 않는다. 기존 audit 보존 정책과 retry/idempotency/concurrency 경계를 변경하지 않는다.

## 검증 및 배포

실제 서버 기준 버전의 관련 테스트 17개가 통과했다. `SearchDiagnosticsTest`, `LoadObservabilityConfigurationTest`, `PersonalStagingObservabilityConfigurationTest`, `AgentTicketSearchIntegrationTest`, personal staging deployment/production Compose contract를 실행한다. file-backed secret 소유 UID:GID를 `DESKSEED_RUNTIME_USER`로 명시하고 실제 backend user와 대조한다. 미지정 기본값은 이미지 사용자 `deskseed`다. 현재 SHA 기반 전용 브랜치의 GitHub Actions workflow_dispatch로 SHA-tagged backend/frontend 이미지를 게시한다. 서버 deployer의 clean checkout 및 OCI revision 일치 검증을 유지한다.

실제 검색 1회 수준의 smoke에서 같은 HTTP trace의 count/page/audit와 Loki trace_id 연결을 확인한다. 임시 sampling 1.0은 기존 0.05로 복원한다. 본 부하와 성능 개선은 수행하지 않는다. 새로운 migration/OpenAPI/retention 변경은 없다.

## 복원

계측 flag false로 끄거나 기준 SHA와 해당 이미지를 기존 deployer로 재배포한다. 데이터와 볼륨을 삭제하지 않는다. 관측 비용이 있으므로 향후 전후 성능 비교에서는 동일 flag/sampling 설정을 유지한다.
