# ADR 0045: Versioned structured rich-text comment content

## Status

Accepted — 2026-08-29

## Context

Deskseed comments and recoverable drafts currently persist plain text. The Agent Workspace now needs a real formatting toolbar while customer projection, search, outbound mail, attachments, audit hashing, replay, and PUBLIC/INTERNAL isolation must remain deterministic and safe. Arbitrary browser HTML would make sanitization and future schema evolution implicit.

## Decision

- `RICH_TEXT_V1` is a closed, versioned JSON document. Allowed blocks are paragraphs, headings 1–3, ordered and unordered lists, blockquotes, code blocks, hard breaks, and attachment-backed images. Allowed marks are strong, emphasis, underline, link, and inline code. Alignment is limited to start, center, end, and justify.
- Emoji are ordinary Unicode text. Rich-text images reference only an attachment ID already submitted with the comment, require alternative text, and never accept a remote URL.
- The server validates and normalizes the document before persistence, derives bounded plain text for the existing `body` column, search, knowledge suggestions, and plain-text outbound mail, and hashes canonical JSON for audit/replay descriptors.
- API responses retain `body` as a backwards-compatible derived projection and add a versioned `content` envelope. Writes accept exactly one of legacy `body` or `content`.
- Existing rows remain `PLAIN_TEXT`; additive columns do not rewrite historical bodies. The customer portal receives rich content only for PUBLIC comments and renders the same closed node set without accepting raw HTML.
- Rich content remains append-only. Redaction is still a separate future privileged workflow.

## Consequences

- Staff and customer clients need strict content decoders and safe renderers.
- Draft persistence and macro comment overrides carry the same content envelope.
- Invalid nodes, attributes, protocols, excessive depth/size, unsafe attachment references, or mixed body/content fail before ticket mutation or audit persistence.
- Existing integrations continue to use and receive plain text unless they explicitly adopt the rich-content envelope.

## References

- D-003, D-005, D-007, D-018, D-030, D-032, D-037, D-038
- REQ-FILE-001, REQ-FILE-002, REQ-TKT-007, REQ-TKT-013, REQ-COL-001
- FILE-002, FILE-003, FILE-004, CHN-005
