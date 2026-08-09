# Attachments, Rich Text, and Content Redaction

## 1. 도입 순서

```text
plain text comments
→ attachment metadata/object storage
→ safe download
→ rich text canonical format
→ redaction workflow
```

파일 업로드는 단순 `bytea` column 추가가 아니다. 권한, 악성 파일, object storage 정합성, 감사, 보존을 함께 구현한다.

## 2. Attachment model

```text
Attachment
  id
  object_key
  original_filename
  safe_filename
  content_type_claimed
  content_type_detected
  size_bytes
  sha256
  scan_status PENDING|CLEAN|INFECTED|FAILED
  storage_status PENDING|AVAILABLE|QUARANTINED|DELETED
  created_by_actor
  created_at
```

`AttachmentLink` connects attachment to comment or draft/upload session.

## 3. Upload flow

Preferred:

```text
request upload slot
→ permission/size/type validation
→ upload to quarantined object key
→ checksum and malware/content inspection
→ mark clean
→ attach in comment command
```

For small MVP, backend streaming upload is acceptable if bounded and tested, but still quarantine and scan abstraction must exist before public deployment.

## 4. Download flow

- server authorizes current actor and linked ticket/comment visibility
- create short-lived signed object URL or stream
- disposition uses sanitized filename
- unsafe inline types force attachment
- download access event
- infected/quarantined/deleted never downloadable
- no public bucket

## 5. Limits

Operator-configurable:

```text
max files per comment
max single size
max total ticket storage
allowed/blocked MIME families
archive handling
retention
```

Extension is not trusted as MIME truth.

## 6. Rich text canonical format

Choose one canonical source:

- sanitized HTML subset, or
- structured editor JSON with deterministic safe HTML rendering.

Do not store arbitrary browser HTML as trusted.

Allowed baseline:

```text
paragraphs, line breaks
strong/emphasis
ordered/unordered lists
links
blockquote
code/pre
```

Later: tables/images only after accessibility and sanitization.

## 7. Sanitization

- allowlist elements/attributes/protocols
- strip scripts, event handlers, styles unless explicitly safe
- links: http/https/mailto according to policy
- `rel="noopener noreferrer"` for external target
- server sanitizes before persistence
- client renderer uses same or stricter policy
- unit corpus for XSS payloads

## 8. Public/internal attachment isolation

Attachment visibility follows the linked comment.

- internal attachment inaccessible through customer URL/API
- moving/relinking attachment between visibility classes forbidden without explicit audited command
- external webhook/export excludes object URL and content by default

## 9. Redaction

Redaction is a privileged, audited replacement operation, not ordinary edit/delete.

```text
CommentContentRedacted
  comment_id
  reason_code
  redacted_by
  redacted_at
  original_content_reference protected/retention-policy controlled
```

UI shows:

```text
이 내용은 보안 정책에 따라 마스킹되었습니다.
```

Whether original content is cryptographically erased or preserved in protected vault is an operator/legal decision.

## 10. Attachment deletion/redaction

- mark metadata state and delete/quarantine object asynchronously
- audit request/completion/failure
- backups follow retention limits
- hash may remain for forensic/dedup policy only if allowed

## 11. Audit

Events:

```text
ATTACHMENT_UPLOADED
ATTACHMENT_LINKED
ATTACHMENT_VIEWED
ATTACHMENT_DOWNLOADED
ATTACHMENT_QUARANTINED
ATTACHMENT_DELETED
COMMENT_REDACTED
```

Do not put file bytes or full comment body into ordinary audit metadata.

## 12. Gates

- `FILE-001`: size/type/count limits and streaming bounds
- `FILE-002`: XSS sanitization corpus
- `FILE-003`: customer cannot fetch internal attachment by ID/URL
- `FILE-004`: unsafe file cannot inline or download
- `FILE-005`: redaction is privileged, audited, and non-ordinary edit
- `FILE-006`: upload/view/download/delete access and retention events
