# 직원 알림 복구 수직 슬라이스

F20, REQ-COL-003 및 REQ-AUT-001. AGENT/ADMIN의 현재 staff actor에 대한 기존 알림 REST/읽음 처리 및 WebSocket hint만 사용한다. D-032, ADR 0046, docs34 및 기존 계약 유지. 재사용: SeedNotificationMenu와 shell; hook만 분리. 감사 담당자는 연결하지 않는다.

연결 종료/오류에 1~30초 bounded backoff, 재연결 성공·focus·online·visibility 및 30초 foreground polling에서 authoritative REST 재조회. 요청 직렬화와 후속 hint 재조회로 오래된 응답 경쟁 방지. actor 변경/unmount 뒤 응답 폐기, timer/socket/listener 정리. 읽음은 서버 성공 후 재조회한다. semantic TICKET_VIEWED 호출 없음. 서버 transaction/audit/권한/retention 변경 없음.

UI-004/006: fake socket/timer 단위 테스트로 단절·fallback·actor 전환·cleanup 검증, 기존 알림 MCP story와 staff 전체 suite, typecheck/lint/build/boundary. 실제 WebSocket/DB E2E 미실행. DB/contract migration 없음. UI revert 가능. 느린 네트워크에서도 마지막 REST 상태를 표시하며 실패는 retry 가능한 상태로 보인다.
