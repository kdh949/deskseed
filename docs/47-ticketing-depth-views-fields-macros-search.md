# Ticketing Breadth: Views, Tags, Custom Fields, Macros, and Search

Status: Staged implementation specification v0.6

These capabilities are intentionally after Core MVP. The seams are documented now so they can be added without weakening ticket visibility, audit, or command semantics.

## 0. P1 implemented baseline

The following narrow P1 slices are implemented without changing the broader staged decisions below.

- V30 seeds five read-only SYSTEM definitions and stores versioned PERSONAL/SHARED definitions. The P1 AST allowlists status, priority, group, assignee/current actor, First Reply SLA state, ticket kind, and bounded updated-age predicates. It rejects tags, raw SQL, SpEL, JavaScript, and scripts.
- View rows, preview, and counts share the condition compiler and SQL authorization predicate. The first 20 visible views receive exact counts with one parameterized `UNION ALL` database round-trip; queue rows remain authoritative.
- `searchAgentWorkspace` is PostgreSQL-first with SQL-side authorization, an opaque query/filter/sort/snapshot-bound cursor, score plus ticket-number tie breaking, exact count, SLA-state filtering, and protected query/audit handling.
- V35 adds a versioned staff-only search document with distinct PUBLIC/INTERNAL comment segments, a `pg_trgm` GIN index, and same-transaction refresh for canonical searchable fields. Exact ticket-number rank and literal substring behavior are unchanged.
- Canonical writes take a shared transaction advisory lock while `rebuild_ticket_search_documents()` takes the exclusive form. A committed write therefore has zero projection lag, and an atomic rebuild cannot overwrite a concurrent refresh. Operators invoke `scripts/rebuild-ticket-search-documents.sql`; no runtime HTTP rebuild endpoint is exposed.
- Tags, custom fields, forms, and macros remain out of this slice. In particular, `REQ-CFG-001` stays `BLUEPRINT_READY` because P1 deliberately excludes tag conditions.
- The committed 1M evidence records both the former full-scan baseline and the V35 projection result. PostgreSQL remains the only search store until a measured relevance, concurrency, size, or latency limit justifies an external engine.

## 1. Saved Views

### 1.1 Purpose

A View is a versioned, authorized definition that produces a ticket queue. It is not a stored list of ticket IDs.

### 1.2 Definition

```text
SavedView
  id
  name
  description
  active
  ownerType: SYSTEM | SHARED | PERSONAL
  ownerStaffId?
  visibility: ALL_AGENTS | GROUPS | OWNER
  allConditions[]
  anyConditions[]
  columns[]
  groupBy?
  orderBy[]
  version
```

### 1.3 Condition AST

```json
{
  "field": "status",
  "operator": "LESS_THAN_SOLVED",
  "value": null
}
```

P1 fields:

- status
- priority
- group
- assignee/current user
- First Reply SLA state
- ticket kind
- bounded updated time

Future fields requiring their own vertical slice:

- requester
- channel
- tags
- selected custom fields when queryable

Operators are allowlisted per field type. No raw SQL or script.

### 1.4 Query behavior

- translate AST to parameterized repository query
- apply ticket authorization before returning rows
- stable sort includes ticket number tie-breaker
- preview returns sample rows and estimated/actual query evidence in admin tooling later
- prohibit pathological unbounded text conditions until search index supports them

### 1.5 UI

- shared/personal/system groups in left pane
- P1 counts are exact for the first 20 visible definitions; list rows remain authoritative
- admin view builder: all/any conditions, columns up to a configured limit, group/sort, preview
- PERSONAL views are available in P1; later UI work may add broader builder ergonomics without changing the server AST contract

### 1.6 Audit

- create/update/activate/reorder/security change → SecurityAuditEvent
- executing a view → VIEW_EXECUTED access event
- opening a row → TICKET_VIEWED linked to view interaction

## 2. Tags

### 2.1 Rules

- normalized lowercase machine value and display value
- bounded length and allowed character policy
- no duplicate after normalization
- add/remove through ticket command
- audit event `TAG_ADDED` / `TAG_REMOVED`
- customer projection excludes tags by default

### 2.2 Uses

- queue filters
- search
- trigger conditions/actions
- analytics dimensions with high-cardinality caution

Tags do not replace structured fields when a stable finite domain exists.

## 3. Custom fields and ticket forms

### 3.1 Supported types in first release

- text
- multiline text
- integer/decimal
- checkbox
- date
- single select
- multi-select
- user/staff reference only after permission semantics

### 3.2 Stable identity and versions

A field has a stable machine key. Display label may change. Type changes that would invalidate data are prohibited; create a new field or run an explicit migration.

Field definition includes:

- staff/customer visibility
- staff/customer editability
- requiredness by ticket form and state
- validation/length
- searchability
- analytics eligibility
- sensitive-data classification

### 3.3 Ticket forms

A form selects and orders fields for a workflow. Initial product can have one default form before form selection is exposed.

- form version freezes placement/order, condition rules, customer visibility/editability/requiredness, field identity/type/validation, and option identity/order; semantic field/option changes require a new stable ID
- customer label/description is current display copy rather than a historical form snapshot, so exact past wording is intentionally not reproducible
- customer form does not receive staff-only field definitions
- hidden required fields cannot block customer submission
- admin preview shows customer and staff perspectives

### 3.4 Storage decision gate

Before coding custom field values, choose a physical strategy with an ADR:

- typed value columns per value row
- JSONB with generated/indexed columns for selected fields
- hybrid

The choice must demonstrate:

- type safety
- query/index plan for views/search
- schema evolution
- before/after audit representation
- no unbounded EAV query collapse

### 3.5 Audit

Field change event stores stable field key, definition version, and typed before/after values. Sensitive custom values use protected-audit handling.

## 4. Macros

### 4.1 Purpose

A Macro is a staff-invoked, reusable set of comment and ticket-field actions. It differs from a Trigger because a human explicitly chooses and previews it.

### 4.2 Definition

```text
Macro
  id, name, active, availability
  version
  actions[]
```

Initial actions:

- set status
- set priority
- set group/assignee
- add/remove tag
- set supported custom field
- insert PUBLIC or INTERNAL comment template

### 4.3 Application flow

1. agent selects macro
2. UI previews all field/comment effects
3. agent edits the resulting draft if allowed
4. one normal ticket command is submitted
5. audit source remains AGENT_UI with macro ID/version metadata

No macro bypasses permission or assignment invariant.

### 4.4 Template safety

- allowlisted placeholders
- HTML/rich content sanitized when enabled
- missing placeholder is visible before submit
- no arbitrary code/expression language in first release

## 5. PostgreSQL search first

### 5.1 Search scope

Initial searchable fields:

- ticket number exact
- subject
- PUBLIC/INTERNAL comments according to permission
- requester name/email according to permission
- group/assignee
- tags
- selected custom fields
- external reference IDs/labels

### 5.2 Search projection

Recommended evolution:

```text
Ticket/comment audit facts
  → transactional or post-commit search projection
  → PostgreSQL tsvector/GIN plus exact normalized columns
```

The projection may denormalize authorized searchable text but must preserve visibility classes so customer/staff/internal queries cannot mix.

V35 uses one `ticket_search_documents` row with lower-cased field segments and separate `public_comment_text` and `internal_comment_text`. Its generated `staff_document` combines both only for the staff endpoint. There is no customer ticket-search consumer of this table. Primary-row delete cascades and comment refresh remove retained text; backup deletion follows the existing retention policy rather than claiming immediate physical erasure.

Possible separation:

- `ticket_search_public`
- `ticket_search_staff`

or one row with distinct public/internal vectors. The design must prove internal terms cannot influence customer result existence/count.

### 5.3 Query behavior

- exact ticket number/reference lookup before full text
- language-aware tokenization where practical
- quoted/advanced syntax only after UX and injection review
- stable cursor based on score plus ticket number
- permission filter applied in query, not client
- query, filters, result count, and result-open chain audited for staff

### 5.4 Search quality benchmark

Create a committed corpus with:

- Korean morphology variations
- English terms
- order/payment identifiers
- misspellings
- internal-only terms
- permission boundary cases

Measure:

- p50/p95 latency
- relevant result rank for known queries
- no internal leakage
- index/update lag

### 5.5 Elasticsearch migration trigger

Add Elasticsearch/OpenSearch only when PostgreSQL misses an accepted goal such as:

- p95 latency under representative corpus/traffic
- relevance requirements including typo/fuzzy/multilingual behavior
- index size or concurrent query load

Migration requires:

- versioned search document
- rebuild/reindex process
- dual-read comparison
- authorization filter design
- outage fallback
- operational ownership

## 6. Feature dependency and order

Recommended sequence:

1. tags
2. system/default views represented as definitions
3. shared/personal view builder
4. PostgreSQL search projection and UI
5. custom field definitions and one default form
6. custom field query support
7. macros

Custom fields before query/storage strategy are not accepted.

## 7. Verification gates

- view AST rejects unsupported field/operator combinations
- view query cannot bypass group/resource authorization
- tag normalization/audit works under concurrency
- customer never receives internal custom fields or search influence
- macro preview equals committed command effects
- search audit redaction/fingerprint policy applies
- 1M tickets/10M comments benchmark is documented before external search engine
