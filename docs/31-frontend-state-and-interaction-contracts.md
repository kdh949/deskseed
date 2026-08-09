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
click row → navigate → ticket query with interactionId
background refetch → same interactionId
new tab/refresh → new interactionId
```

서버가 권한 검사와 성공 read 후 semantic view event를 기록한다. client 이벤트만 믿지 않는다.

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
