# Deskseed Design QA — Canonical Agent UI Cutover

## Evidence

- North-star reference: `/var/folders/rg/k27jblsn7sn4qsddc_5ckkvw0000gn/T/codex-clipboard-190733da-f65c-4250-a6a0-a1e40a86c086.png`
- API-backed Queue: `frontend/e2e/__screenshots__/darwin/agent-queue-canonical-1472.png`
- API-backed Workspace: `frontend/e2e/__screenshots__/darwin/agent-workspace-canonical-1472.png`
- In-app browser Queue: `artifacts/design-system-cutover/agent-queue-1440.png`
- In-app browser Workspace: `artifacts/design-system-cutover/ticket-workspace-1440.png`

## Comparison

The reference and API-backed Workspace were reviewed together at 1472×1046. The resulting Agent family preserves the reference grammar:

- 64px white global navigation rail and dark-teal brand cell
- dark-teal top chrome with ticket context, global search, and agent identity
- approved Deskseed brand mark and real icon components
- compact white/neutral workspace, 1px dividers, low-radius controls, and no heavy card/shadow treatment
- Ticket Workspace properties/conversation/customer-context structure
- Queue-specific Views/sidebar/table structure inside the same shell
- shared semantic hover, focus, disabled, status, and density treatment

The API-backed Workspace contains fewer comments and only fields present in the current projection, so its central conversation has more empty space and its context panel omits unsupported organization/order detail. These are truthful data differences, not shell differences.

## Interaction and accessibility review

- Queue URL filters, current-page search, pagination, selection, ArrowUp/ArrowDown row focus, Space selection, and Enter navigation are covered by browser tests.
- Workspace refresh preserves the navigation interaction ID; properties and customer context can be collapsed/opened.
- PUBLIC and INTERNAL composer modes retain separate drafts and expose distinct text, icons, tabs, and tabpanels.
- Unsupported Queue actions are absent rather than shown with engineering explanations.
- Axe reports zero violations for the API-backed Queue and Workspace at 1280, 1440, 1472, and 1920 widths.
- One muted-text contrast failure was found during review and fixed at the canonical neutral token; the post-fix axe pass and screenshots are the evidence.
- In-app browser inspection found no console errors while opening and interacting with Queue and Workspace production components.

## Visual decision

The canonical Queue and Workspace now read as one Deskseed Agent product family. Screen information architecture differs by task, while brand, navigation, chrome, typography, controls, density, dividers, and interaction language remain consistent.

final result: passed

---

# Agent Queue 주석 반영 QA — 툴바·보기·상태 아이콘

## Evidence

- Source visual truth: `/Users/donghyunkim/.codex/generated_images/019ff716-546d-7140-8152-edd21729be2a/exec-8cf92164-7d0d-46dc-a899-7e0a63d59e12.png`
- Rendered implementation: `frontend/e2e/__screenshots__/darwin/agent-queue-canonical-1440.png`
- Full-view comparison input: `/Users/donghyunkim/.codex/generated_images/019ff716-546d-7140-8152-edd21729be2a/exec-32cc4932-ca2f-490c-a1f1-12bc8ed79303.png`
- Focused browser evidence: in-app fixture route `/__fixtures__/frontend-system/view-queue`, with the filter opened, ticket-ID sort toggled, and an alternate View selected.

Source: 1635×962 px. Implementation: 1440×922 px, captured at 1440×900 CSS px with device scale factor 1 and full-page height. The comparison board uniformly places the two uncropped captures side by side; it is used for hierarchy and density review rather than pixel-diff scoring.

State: Deskseed Agent Queue, 15 visible tickets, `내 티켓` selected, standard light appearance. The final in-app interaction pass confirmed one selected View, one selected global Views icon, rectangular View selection, visible filter, sort, refresh, and actions controls.

## Comparison history

- [P1 resolved] The fixture omitted the selected reference composition's filter, refresh/action toolbar, and sortable table headers. The reusable Queue table now owns labeled sort controls; the feature and fixture render the same token-based filter and toolbar pattern.
- [P1 resolved] The fixture gave every View the same route, so multiple rows could appear selected. Each View now has a distinct route and the shell accepts a semantic active-navigation item for deterministic fixture selection.
- [P2 resolved] Selected View rows retained the default rounded control shape. The selected-state radius now resolves from the shared zero-radius token while unselected rows retain their normal control treatment.
- [P2 resolved] Urgent and solved Views used generic icons, and several table states shared the same clock. View presentation metadata now supplies semantic icon/tone pairs; new, in-progress, pending, on-hold, and solved states map to distinct Garden icons with text.
- [P2 resolved] The brand image cell and chrome resolved through different dark surfaces. Both now use the same Deskseed chrome token, producing `rgb(2, 45, 61)` in the rendered browser.

## Focused comparison

The full-view board makes the navigation, title/filter row, toolbar, table header, and status column readable together. The in-app browser pass additionally checked the small fidelity surfaces that are difficult to judge at board scale: urgent red icon, solved green icon, rectangular selection, selected global Views icon, exact tip emphasis, toolbar action names, and the status-icon variants.

## Fidelity review

- Fonts and typography: the existing Deskseed reference type scale preserves the compact title, count, 12px column labels, 14px rows, text truncation, and icon-plus-text status labels. No source font or proprietary wordmark was copied.
- Spacing and layout rhythm: the 64px rail, 280px Views navigation, stacked title/filter hierarchy, quiet right toolbar, and high-density table remain aligned to the selected composition at 1280, 1440, 1472, and 1920px. No persistent control clips or overlaps.
- Colors and tokens: all changed surfaces resolve from `--ds-*` semantic/reference tokens. The shared chrome token, neutral table surfaces, teal selection, and semantic red/amber/green/blue states contain no new raw color values.
- Image quality and assets: the existing Deskseed brand image and Zendesk Garden icon library remain in use. No screenshot, inline SVG, CSS art, or placeholder image was introduced.
- Copy and content: the sidebar now presents `새 보기 만들기` and the requested bold `Tip` copy. Dynamic ticket data stays API-backed; the deliberate absence of SLA/channel columns is a known projection constraint, not invented UI data.
- Icons and interaction: the filter expands, headers toggle sort direction, the Queue action menu offers applicable local-view actions, and Views remain single-selected. Icon buttons and menu items have accessible names; row keyboard navigation and selection still operate.
- Accessibility and resilience: browser Axe passes for the 26-test development E2E suite; canonical Queue screenshots were refreshed at 1280/1440/1472/1920px. Focus, labels, and semantic `aria-sort` values are present.

## Findings

No actionable P0, P1, or P2 differences remain. The implementation intentionally uses available Queue fields instead of creating SLA/channel data; keep that constraint in a future view-builder requirement if those columns become product data.

final result: passed

---

# Agent Queue 재설계 QA — 선택 시안 1

## Evidence

- Source visual truth: `/Users/donghyunkim/.codex/generated_images/019ff716-546d-7140-8152-edd21729be2a/exec-8cf92164-7d0d-46dc-a899-7e0a63d59e12.png`
- Browser-rendered implementation: `artifacts/design-system-cutover/agent-queue-1633.png`
- Canonical implementation comparison capture: `artifacts/design-system-cutover/agent-queue-redesign-1440.png`
- Side-by-side comparison board: `/Users/donghyunkim/.codex/generated_images/019ff716-546d-7140-8152-edd21729be2a/exec-69e173b1-1637-4ef3-b5c0-d59b1c4f4def.png`

Source is 1635×962 px. The canonical implementation capture is 1440×912 px from a 1440×900 CSS viewport at device scale factor 1; its full-page height includes the 15-row table and footer. The comparison board uniformly fits each uncropped source into its own panel, so it is used for hierarchy and density comparison rather than pixel-diff scoring.

State: signed-in Deskseed agent Queue, `내 티켓` selected, 15 visible tickets / 28 approximate total, Views navigation expanded. The production contract does not expose SLA or channel values, so those columns are deliberately absent rather than populated with invented data.

## Comparison history

- [P2 resolved] Initial canonical API fixture had only two rows and a minimal Views list, so it did not demonstrate the chosen high-density Queue composition. The canonical test projection now supplies 15 realistic rows, 28 total tickets, the full shared/personal navigation hierarchy, and the selected view's counts.
- [P2 resolved] The first Queue header put filter and refresh actions on the title line. The revised header places the outlined filter beneath the title and aligns the quiet refresh control to the table toolbar line, matching the selected composition while retaining the existing Deskseed button tokens.

## Focused comparison

The full Queue view and a focused visual pass of the left Views navigation, title/filter strip, and dense table were compared together in the side-by-side board above. A separate focused crop is not needed: both regions remain readable at the supplied comparison scale and no unresolved P0/P1/P2 difference was found.

## Fidelity review

- Fonts and typography: existing `--ds-ref-font-*` hierarchy keeps the compact title, 14px table text, 12px column labels, tabular counts, truncation, and text-plus-icon states readable. The implementation intentionally follows the existing Deskseed type scale instead of recreating the reference's proprietary font rendering.
- Spacing and layout rhythm: the prototype uses the specified 64px global rail, 280px Views navigation, low-radius 1px surfaces, title/filter stack, and single dense table. The reference rail/sidebar are visually a little wider; the 64px/280px tracks are intentional implementation constraints from this task and remain non-actionable.
- Colors and tokens: dark teal chrome, teal selected state, neutral table/header layers, blue subjects, and semantic green/amber/red status states all resolve from existing `--ds-*` tokens. No second theme root or raw Queue color was introduced.
- Image and asset fidelity: Deskseed's existing brand mark and icon library are used. No source screenshot, logo, custom SVG, placeholder, or generated UI asset was shipped.
- Copy and content: the canonical data uses Korean support vocabulary and the actual API projection fields. New local-view copy explicitly says that conditions are not yet configured, preventing it from being mistaken for a server-backed saved view.
- Icons and interaction: every status and attention priority pairs an icon with text. The personal-view drawer uses named icon controls, required-name validation, keyboard order buttons, Escape, focus trapping, and focus restoration to the invoking button.

## Verification

- Browser interactions checked: filter query updates, keyboard row focus/Space selection, ticket navigation intent, local view creation without an additional tickets query, and drawer Escape focus return.
- In-app browser console errors/warnings: none.
- Axe: zero violations for the Queue, including the local view empty state.

## Findings

No actionable P0, P1, or P2 findings remain. The absent SLA/channel columns and the narrower global track are expected, documented product constraints rather than visual defects.

final result: passed
