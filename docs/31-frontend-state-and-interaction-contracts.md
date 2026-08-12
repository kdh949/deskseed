# 프론트엔드 상태·상호작용 계약

## 1. 상태 구분

```text
Server state: TanStack Query cache
Form state: local form model
Draft state: local storage/session storage
Navigation state: URL
Layout preference: user preference/local storage
Security context: short-lived authenticated session
```

서버 상태를 Redux 같은 전역 client store에 복제하지 않는다.

## 2. Query key 규칙

예:

```text
['ticket', ticketNumber]
['ticket-comments', ticketNumber, cursor]
['view', viewKey, filters, sort]
['audit-activities', normalizedFilters, cursor]
['customer-requests', accountId, filters]
```

권한 또는 사용자 변경 시 관련 cache를 전부 폐기한다.

## 3. Ticket edit model

현재 운영 Ticket Workspace는 읽기 전용이다. 아래 edit model은 Storybook/개발 fixture와 headless 계약에서 유지되며 실제 mutation 연결은 별도 수직 슬라이스다.

Ticket detail은 다음을 유지한다.

```ts
serverVersion
serverFields
localDraftFields
dirtyFieldNames
commentDraft
commentVisibility
```

전송 payload에는 사용자가 실제 바꾼 field만 포함한다.

```json
{
  "expectedVersion": 17,
  "changedFields": ["priority", "assigneeId"],
  "priority": "HIGH",
  "assigneeId": "...",
  "comment": null
}
```

## 4. Combined submit

댓글과 field 변경은 하나의 command다.

- 성공: 모두 반영.
- validation/permission/conflict: 모두 반영되지 않음.
- UI가 필드 일부 성공을 가정하면 안 됨.
- 동일 request 재시도는 client-generated command ID 또는 idempotency key로 보호한다.

`clientCommandId`는 한 logical submit의 수명 동안 유지한다. network/5xx처럼 commit 여부가 모호한 실패에서는 staff/ticket별
12시간 draft snapshot에 ID와 original base/request state를 함께 보존해 reload 후 deliberate retry도 같은 ID를 보낸다.
background/manual refresh는 성공 여부와 무관하게 ambiguous command의 outcome을 ID에 결부할 수 없으므로 original
base/request state와 ID를 바꾸지 않는다. Payload edit, definite validation/conflict 또는 확인된 command 성공에서만 ID를
회전한다. 성공 응답 뒤 detail refresh보다 먼저 submitted comment와
confirmed field/base version을 하나의 local-storage write로 정리해 crash/reload가 이미 저장된 comment를 새 ID로 재전송하지 않게 한다.

## 5. Field-aware conflict

서버 응답 예:

```json
{
  "type": "/problems/ticket-field-conflict",
  "status": 409,
  "currentVersion": 19,
  "conflictingFields": ["assigneeId"],
  "currentValues": {"assigneeId": "agent-c"}
}
```

UI:

1. conflict banner 표시.
2. 서버 최신 값을 fetch.
3. 충돌하지 않은 local draft는 유지.
4. 충돌 field는 사용자에게 server/local 비교를 제공.
5. 사용자가 선택해 다시 제출.

## 6. Draft recovery

key:

```text
deskseed:draft:ticket:{ticketNumber}:{staffId}
```

저장 내용:

- text body
- visibility
- dirty fields
- base version
- saved at

금지:

- access token/API secret
- attachment binary
- 고객의 고위험 민감정보를 장기 저장

TTL과 관리자 정책을 둔다.

## 7. Ticket view interaction audit

사용자 click/open에서 client가 `interactionId`를 생성한다.

```text
click row → navigate → ticket query with interactionId + NAVIGATION intent
background/manual refetch → same interactionId + BACKGROUND intent
new tab/refresh → new interactionId
```

서버가 권한 검사와 성공 read 후 intent를 해석해 semantic view event를 기록한다. `NAVIGATION`만 한 interaction에서 semantic view를 만들고 `BACKGROUND`는 만들지 않는다. client 이벤트만 믿지 않는다.

## 8. Search session

검색 실행:

```text
searchSessionId
query redacted/fingerprint server-side
filter/sort
result count
```

결과 open:

```text
searchSessionId + interactionId + ticketNumber
```

URL에는 원문 검색어가 남을 수 있으므로 browser history와 referrer 정책을 검토한다. 민감 검색이 예상되면 POST search 또는 URL redaction 전략을 사용한다.

Audit Explorer의 달력 날짜는 브라우저 local midnight에서 다음 local midnight까지의 half-open UTC instant 범위로 변환한다. `to`는 exclusive이며 inclusive `23:59:59.999`를 만들지 않는다. Cursor는 필터 key와 하나의 pagination state로 묶는다. browser Back/Forward로 URL filter가 바뀌면 렌더 단계에서 새 필터의 첫 페이지를 사용하고 이전 필터 cursor를 보내지 않는다.

## 9. Loading patterns

- 예측 가능한 ticket/table: Skeleton.
- 작은 action: button dots/spinner.
- 전역 block overlay는 최소화.
- background refresh는 content를 지우지 않고 subtle indicator만 표시.

## 10. Error model

RFC Problem Details의 `type`, `title`, `detail`, `errors`, `requestId`를 공통 parser로 처리한다.

분류:

```text
400 validation → field messages
401 → sign-in flow
403 → permission screen/toast
404 → resource not found, 정보 노출 최소화
409/412 → conflict flow
429 → retry-after 표시
5xx/network → draft 보존 + retry
```

모호한 5xx/network retry는 같은 logical payload와 `clientCommandId`를 재사용한다. 4xx validation/permission/conflict는 definite
failure이므로 ID를 폐기하고, 사용자가 수정·해결한 다음 새 logical command ID로 제출한다.

## 11. Unsaved navigation

- comment 또는 dirty field가 있으면 route leave guard.
- “나가기/초안 유지/취소” 제공.
- browser unload에서 서버 저장을 시도하지 않는다.

## 12. Panel and user preferences

저장 가능:

- properties/context width
- context active tab
- view columns/order
- density

권한에 따라 숨겨진 field를 preference가 다시 노출하면 안 된다.

## 13. Optimistic UI

허용:

- local draft editing
- view count의 임시 refresh indicator

초기에는 금지:

- ticket mutation을 성공처럼 즉시 표시
- comment를 서버 ID 없이 확정 표시
- audit-sensitive action의 optimistic success

## 14. Real-time later

SSE/WebSocket을 추가할 때도 Query cache가 source of UI truth다.

- remote update notification.
- dirty form이 없으면 invalidate/refetch.
- dirty form이 있으면 “다른 사용자가 업데이트함” 배너.
- event payload만으로 민감한 full data를 갱신하지 않는다.

## 15. Staff actor consistency guard

D-050의 `X-Deskseed-Expected-Staff-Id`는 인증 수단이나 actor 선택 입력이 아니다. 브라우저 realm은 마지막으로
확인한 staff ID를 짧은 수명의 client security context로 유지하고, 일반 staff read와 인증된 CSRF/write 요청에 optional
header로 보낸다. 서버가 검증한 session principal만 실제 actor이며 header는 그 principal과 같은지 비교하는
defense-in-depth guard다. `localStorage`의 draft-session owner marker는 탭 간 변경 신호와 draft 정리에만 쓰며 임의
요청의 actor를 공급하지 않는다.

세션 설정에는 다음 예외가 있다.

- 로그인 전 CSRF와 `POST /api/v1/agent/session`에는 아직 확인된 actor가 없으므로 header를 보내지 않는다.
- 로그인 직후 새 session을 확인하는 `GET /api/v1/agent/me` 호출도 header를 생략한다. 일반 refresh의 `/me`는 마지막으로
  확인한 actor가 있으면 guard를 사용할 수 있다.
- mutation 한 번은 시작 시 confirmed actor와 session generation을 한 번 snapshot한다. CSRF 발급과 이어지는 write는
  같은 snapshot을 사용하며 중간에 다른 탭이 session owner를 바꾸면 새 actor로 다시 snapshot해 write하지 않는다.

present header가 canonical single UUID가 아니거나 중복이면 `400 /problems/invalid-staff-session-actor`, 검증된 session
principal과 다르면 `409 /problems/staff-session-actor-mismatch`다. 둘 다 controller, success audit, mutation, session activity
renewal 전에 fail closed한다. mismatch는 공유 server session이나 새 owner marker를 지우지 않고 stale tab의 인증 UI와
그 tab이 소유한 draft만 정리한다. 구현된 operation별 parameter와 400/409 계약은
`api/core-api-outline-v1.yaml`이 source of truth이며 blueprint-only staff operation은 구현 동결 시 같은 binding을 추가한다.
