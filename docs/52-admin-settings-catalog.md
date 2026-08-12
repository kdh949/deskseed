# Admin Settings Catalog

Status: **Normative configuration inventory v0.6**

관리자 설정을 임의 key/value dumping ground로 만들지 않는다. 각 설정은 type, default, validation, permission, audit, effective time, secret 여부, restart 필요 여부를 가진다.

## 1. Settings model

```text
SettingDefinition
  key
  value_type
  default_value
  validation_schema
  category
  secret
  restart_required
  supported_since

SettingValue
  key
  serialized_value or encrypted_value
  version
  effective_at
  changed_by
  changed_at
```

- typed registry가 허용한 key만 저장한다.
- secret은 일반 SettingValue와 분리하거나 envelope encryption한다.
- 설정 변경은 AdminSecurityAudit에 before/after 또는 protected reference를 남긴다.
- 설정 읽기 projection은 secret 원문을 절대 반환하지 않는다.
- runtime 설정 변경은 cache invalidation/version을 명시한다.

## 2. Customer access

| Key | Type | Proposed default | Phase | Audit | Notes |
|---|---|---|---|---|---|
| `customer.accessMode` | enum | `ANONYMOUS_ALLOWED` | M6 | yes | optional/required registration later |
| `customer.requestTokenTtlDays` | int | 30 | M1 hardening | yes | legal/security decision |
| `customer.allowAnonymousFollowUp` | bool | false | P1 | yes | token security required |
| `customer.emailVerificationRequiredForList` | bool | true | P1 | yes | prevents email-ownership assumption |
| `customer.authMethod` | enum | `EMAIL_MAGIC_LINK` | P1 | yes | password/OIDC later |
| `customer.magicLinkTtlMinutes` | int | 15 | P1 | yes | allowed 5–60 |
| `customer.reopenWindowDays` | int | 14 | P1 | yes | provisional |
| `customer.displayAgentFullName` | bool | false | M6 | yes | privacy/brand policy |

## 3. Ticket workflow

| Key | Type | Default | Phase | Notes |
|---|---|---|---|---|
| `ticket.statusesEnabled` | enum set | NEW,OPEN,PENDING,SOLVED | M0 | HOLD/CLOSED later |
| `ticket.defaultPriority` | enum | NORMAL | M1 | |
| `ticket.defaultGroupId` | group ID/null | null | M2 | validates active group |
| `ticket.warnSolveWithOpenChildren` | bool | true | M5 | warning only |
| `ticket.childMaxDepth` | int | 1 | M5 | do not silently increase |
| `ticket.internalChildDefaultPriority` | enum | NORMAL | M5 | |
| `ticket.commentMaxLength` | int | operator value | M1 | bounded |
| `ticket.allowPublicReplyFromInternalChild` | bool | false | M5 | keep customer invisible |
| `ticket.concurrencyPolicy` | enum | FIELD_AWARE | M3 | not freely mutable after release |

## 4. Staff authentication and sessions

| Key | Type | Default | Phase | Secret |
|---|---|---|---|---|
| `auth.sessionIdleMinutes` | int | 60 | M2 | no |
| `auth.sessionAbsoluteHours` | int | 12 | M2 | no |
| `auth.loginFailureLimit` | int | 10 | M2 | no |
| `auth.loginFailureWindowMinutes` | int | 15 | M2 | no |
| `auth.requireMfaForAdmin` | bool | false initially | P1 | no |
| `auth.requireMfaForAuditReveal` | bool | true when available | R2/P1 | no |
| `auth.oidc.enabled` | bool | false | P1 | no |
| `auth.oidc.clientSecret` | secret | none | P1 | yes |

## 5. Authorization

| Key | Type | Default | Phase | Notes |
|---|---|---|---|---|
| `authorization.agentTicketReadScope` | enum | ALL_TICKETS | M2 | ALL_TICKETS/OWN_GROUPS/ASSIGNED_ONLY/EXPLICIT_GROUP_MATRIX |
| `authorization.agentTicketWriteScope` | enum | GROUP_OR_ASSIGNEE | M3 | unresolved product choice; conservative default |
| `authorization.childParentRead` | bool | true | M5 | relationship permission |
| `authorization.groupPolicyEnabled` | bool | false | P2 | NONE/READ/READ_WRITE |
| `authorization.securityAuditorCanReveal` | bool | false | R2 | legacy catalog entry; runtime uses per-staff explicit grant |
| `authorization.securityAuditorCanExport` | bool | false | R2 | legacy catalog entry; runtime uses per-staff explicit grant |

감사 검색어 원문 공개, export 요청, projection rebuild 권한은 조직 전체 boolean 설정이 아니다.
각 active `SECURITY_AUDITOR`에게 ADMIN이 별도로 부여하는 `staff_authority_grants`이며,
부여·회수와 canonical admin/security audit가 같은 transaction에서 처리된다.

## 6. Access and security audit

| Key | Type | Proposed default | Phase | Notes |
|---|---|---|---|---|
| `audit.access.enabled` | bool | true | R1 | fail-closed for defined reads |
| `audit.semanticViewDedupMinutes` | int | interaction-based | R1 | interaction ID is primary |
| `audit.search.redactedStorage` | bool | true | R1 | |
| `audit.search.fingerprintStorage` | bool | true | R1 | keyed HMAC |
| `audit.search.rawStorageMode` | enum | REQUIRED_ENCRYPTED | R1 | missing key fails readiness |
| `audit.search.activeKeyVersion` | string | required | R1 | secret key material is external |
| `audit.search.ciphertextRetentionDays` | int | 30 | R1 | provisional |
| `audit.accessRetentionDays` | int | 180 | R2 | provisional |
| `audit.adminRetentionDays` | int | 365 | R2 | provisional |
| `audit.exportArtifactTtlHours` | int | 24 | R2 | |
| `audit.revealRequiresReason` | bool | true | R2 | |

## 7. Integrations and Platform API

| Key | Type | Default | Phase | Notes |
|---|---|---|---|---|
| `platformApi.enabled` | bool | false | I1 | explicit activation |
| `platformApi.networkMode` | enum | PRIVATE_ONLY | I1 | accepted v1 boundary |
| `platformApi.defaultRateLimitPerMinute` | int | 60 | I2 | per client override |
| `platformApi.idempotencyRetentionDays` | int | 7 | I3 | |
| `platformApi.requireIpAllowlist` | bool | false | I1 | per client preferred |
| `externalReference.allowedHosts` | host list | empty | I4 | HTTPS only |
| `webhook.enabled` | bool | false | I5 | |
| `webhook.maxAttempts` | int | 10 | I5 | |
| `webhook.requestTimeoutSeconds` | int | 10 | I5 | |
| `webhook.allowPrivateNetworkTargets` | bool | false | I5 | SSRF boundary |

Secrets such as API key values and webhook HMAC secret are credential objects, not ordinary settings.
Integration client CRUD/disable/revoke/rotate is likewise a typed credential-management surface guarded by `integration:clients:manage`, not generic settings CRUD. Per-client IP/CIDR allowlists and required expiry are stored with the client/credential; rotation overlap is bounded to 24 hours. `platformApi.enabled=false` remains unchanged and no Platform endpoint is registered by the I1 management slice.

## 8. SLA and business time

| Key | Type | Default | Phase | Notes |
|---|---|---|---|---|
| `time.defaultZone` | IANA zone | `Asia/Seoul` | P3 | operator selected |
| `sla.defaultWeeklyHours` | schedule | Mon–Fri 09:00–18:00 | P3 | editable in schedule UI |
| `sla.firstReplyPauseStatuses` | enum set | PENDING | P3 | versioned policy field |
| `sla.enabled` | bool | false | P3 | |
| `sla.atRiskThresholdMinutes` | int | 30 | P3 | display threshold |
| `sla.breachScanBatchSize` | int | measured | P3 | operational |
| `sla.defaultBusinessScheduleId` | ID/null | null | P3 | |

Policy targets themselves are versioned domain records, not generic settings.

## 9. Automation

| Key | Type | Default | Phase | Notes |
|---|---|---|---|---|
| `automation.triggersEnabled` | bool | false | P4 | |
| `automation.scheduledEnabled` | bool | false | P4 | |
| `automation.maxDepth` | int | 10 | P4 | safety |
| `automation.maxActionsPerRoot` | int | 50 | P4 | safety |
| `automation.executionTimeBudgetMs` | int | bounded | P4 | |
| `automation.scanBatchSize` | int | measured | P4 | |

## 10. Analytics and export

| Key | Type | Default | Phase | Notes |
|---|---|---|---|---|
| `analytics.enabled` | bool | false | P5 | |
| `analytics.reportingZone` | IANA zone | time.defaultZone | P5 | |
| `analytics.backlogSnapshotFrequency` | enum | DAILY | P5 | hourly later |
| `analytics.maxInteractiveRangeDays` | int | 365 | P5 | |
| `export.maxRows` | long | operator | P5 | |
| `export.artifactTtlHours` | int | 24 | P5 | |
| `export.includeInternalCommentsByDefault` | bool | false | P5 | privileged explicit field |

## 11. Attachments and content

| Key | Type | Default | Phase | Notes |
|---|---|---|---|---|
| `attachment.enabled` | bool | false | P8 | |
| `attachment.maxFileBytes` | long | operator | P8 | |
| `attachment.allowedMimeTypes` | list | safe allowlist | P8 | |
| `attachment.malwareScanRequired` | bool | true | P8 | fail closed |
| `attachment.downloadUrlTtlSeconds` | int | 300 | P8 | |
| `content.richTextEnabled` | bool | false | P8 | canonical safe format required |
| `content.redactionEnabled` | bool | false | P8 | privileged workflow |

Object-store credentials are secrets outside this registry.

## 12. Email and notifications

| Key | Type | Default | Phase | Notes |
|---|---|---|---|---|
| `notification.emailOutboundEnabled` | bool | true in dev | P1/P8 | outbox required |
| `notification.devAdapter` | enum | MAILPIT | P1 | development/test only |
| `notification.mailpitSmtpHost` | host | mailpit | P1 | Compose-internal |
| `notification.mailpitSmtpPort` | int | 1025 | P1 | Compose-internal |
| `notification.fromAddress` | email | unset | P8 | |
| `notification.replyDomain` | domain | unset | P8 | threading/security |
| `email.inboundEnabled` | bool | false | P8 | provider adapter |
| `email.rawMessageRetentionDays` | int | operator/legal | P8 | provisional |
| `email.maxInboundBytes` | long | operator | P8 | abuse boundary |
| `email.rejectUnknownReplyToken` | bool | true | P8 | no ticket enumeration |

Provider secrets are named connections/credential records.

## 13. Branding and frontend

| Key | Type | Default | Phase | Notes |
|---|---|---|---|---|
| `brand.productName` | string | Deskseed placeholder | M0 | final name before release |
| `brand.primaryHue` | semantic hue | own brand | M0 | not copied from screenshots |
| `brand.logoAssetId` | asset/null | null | M0 | own asset only |
| `ui.darkModeEnabled` | bool | later | post-MVP | visual gates |
| `ui.defaultContextPanelOpen` | bool | true | M2 | |
| `ui.defaultConversationOrder` | enum | OLDEST_TO_NEWEST | M2 | |

Per-agent panel width/draft/tab state is user preference, not organization setting.

## 14. Feature flags

Feature flag is rollout control, not permanent business configuration.

```text
feature key
state OFF|INTERNAL|PERCENT|ON
scope
expires/review date
owner
```

Flags must be removed after rollout. A flag cannot weaken authorization or audit obligations.

## 15. Settings UI behavior

- category navigation.
- search by setting name/key.
- description, default, current effective value.
- validation before save.
- preview/dry run where applicable.
- diff and actor in change history.
- secret fields show configured/not configured, never current value.
- danger settings require explicit confirmation and impact text.
- restart-required setting shows pending/effective state.

## 16. Verification

- unknown key rejected.
- wrong type/range rejected.
- secret never returned/logged.
- setting change audit before/after safe values.
- concurrent edit version conflict.
- cache invalidation/effective time deterministic.
- feature flag cannot bypass permission/audit.
- restore/upgrade preserves settings and version history.
