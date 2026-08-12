# UI, Visual and Accessibility Evidence

- Date: 2026-08-12 (Asia/Seoul)
- Final frontend tree: `724ffe9d47cc8e26f933881ba4643473b6dae6b4` at PR #22 head `ca3b910c3107dd86b2df6e7f0a727f899c5b2db8` (subsequent working-tree changes were documentation and root license only)
- Pixel baseline: Playwright 1.62.1 Chromium, 42 images on each of Darwin and Linux
- Automated result: **PASS — final-source Chromium 41/41, Firefox 41/41 and WebKit 35/35 + 6/6; 0 retry/skip**
- Browser-assisted direct visual/keyboard review: **PASS**
- VoiceOver/NVDA screen-reader sign-off: **NOT RUN** (owner decision)

## Commands and results

From `frontend/`, the final source was checked with:

```bash
PLAYWRIGHT_DEV_SERVER_PORT=46310 PLAYWRIGHT_BROWSER=chromium npm run test:e2e:dev
PLAYWRIGHT_DEV_SERVER_PORT=46311 PLAYWRIGHT_BROWSER=firefox npm run test:e2e:dev
PLAYWRIGHT_DEV_SERVER_PORT=46312 PLAYWRIGHT_BROWSER=webkit npm run test:e2e:dev
```

| Engine | Result | Duration | Process shape |
|---|---:|---:|---|
| Chromium | 41/41 | 25.5s | one process; final frontend source, also PASS in PR #22 CI run `31564637144` |
| Firefox | 41/41 | 32.7s | one process; final frontend source |
| WebKit | 35/35 + 6/6 | 2.9m + 53.8s | two fresh processes in `mcr.microsoft.com/playwright:v1.62.1-noble`; final frontend source, no retry/skip |

The WebKit split is implemented by `scripts/run-frontend-browser-e2e.sh`. On the prior
release-hardening base source, a single Playwright 1.62 WebKit process reproducibly stopped
responding near test 38, so both fresh processes are mandatory and either failure fails the
wrapper. On the final PR #22 source, local macOS WebKit launched but page creation timed
out before any route, application code or assertion ran. The exact wrapper was therefore
run in the official Playwright 1.62.1 Noble image, where both processes passed. This is an
explicit host-runtime limitation with an equivalent pinned-browser verification, not a
retry or skipped test.

The final-source WebKit replacement run used the same pinned Noble image and isolated
node-modules volume pattern shown below for Linux Chromium. It set
`PLAYWRIGHT_BROWSER=webkit`, used port `46441`, ran `npm run test:e2e:dev`, and removed the
task-labelled volume after verifying its owner label. The exact result was 35/35 + 6/6.

Linux Chromium baselines were force-regenerated from the release-hardening base in the
official Playwright 1.62.1 Noble image. That run passed 41/41 in 3.7 minutes and wrote all
42 Linux images; 15 differed from the previous baseline. The final PR #22 Chromium run
matched those committed baselines. The reproducible sequence from repository root is:

```bash
docker volume create deskseed-release-linux-playwright-node-modules
docker run --rm --user 0:0 \
  --volume deskseed-release-linux-playwright-node-modules:/workspace/frontend/node_modules \
  mcr.microsoft.com/playwright:v1.62.1-noble \
  sh -lc 'chown -R 501:20 /workspace/frontend/node_modules'
docker run --rm --init --user 501:20 --env HOME=/tmp \
  --workdir /workspace/frontend --volume "$PWD:/workspace" \
  --volume deskseed-release-linux-playwright-node-modules:/workspace/frontend/node_modules \
  mcr.microsoft.com/playwright:v1.62.1-noble npm ci --no-audit --no-fund
docker run --rm --init --user 501:20 --env HOME=/tmp \
  --env PLAYWRIGHT_BROWSER=chromium --env PLAYWRIGHT_DEV_SERVER_PORT=46261 \
  --workdir /workspace/frontend --volume "$PWD:/workspace" \
  --volume deskseed-release-linux-playwright-node-modules:/workspace/frontend/node_modules \
  mcr.microsoft.com/playwright:v1.62.1-noble npm run test:e2e:update
docker volume rm deskseed-release-linux-playwright-node-modules
```

Replace `501:20` with the non-root host uid/gid when reproducing on another machine.
The named volume was removed after the recorded run.

## Automated coverage

- deterministic Chromium screenshots at 1280, 1440 and 1920 pixels;
- Agent Views, three-panel Workspace and actual queue-row keyboard open;
- Audit Explorer list/detail/protected-query reveal;
- public request create/detail and customer token storage/URL non-exposure;
- staff login/admin authorization, PUBLIC/INTERNAL modes and conflict recovery;
- transfer/child dialogs, busy-state focus containment and solve warning;
- axe checks, skip link, resize, tabs, dialog entry/trap/restore and keyboard workflows.

Baseline count is 42 per platform directory, 84 committed images total. Firefox and
WebKit execute the same functional, axe and keyboard assertions but intentionally do not
reuse Chromium pixel baselines. Both did so on the final source; the macOS-only WebKit
page-fixture limitation and the pinned Noble replacement run are recorded above.

## Direct browser inspection performed

At 2026-08-12 15:58 KST, Codex's in-app browser loaded a local read-only gallery of every
committed Darwin and Linux baseline. The page reported 84 figures and 84 complete images
with non-zero natural dimensions; the complete gallery was inspected, including all
1280/1440/1920 layouts, modal states, conflict states, mobile request detail and protected
Audit Explorer reveal states. The routine value is consistently labelled `protected
query` and rendered as `[PROTECTED]`; the raw value appears only in the visibly separated,
reason-gated reveal panel. No clipping, overlap, hidden primary action, Zendesk
logo/screenshot or color-only PUBLIC/INTERNAL distinction was observed.

The live development-only fixture was also exercised directly in the in-app browser:

- the workspace property separator moved from `aria-valuenow=300` to `316` with
  `ArrowRight`;
- right-arrow navigation selected the `기록` tab and Home selected `공개 답변`;
- 200% page scale preserved the workspace content boundary;
- a 390×844 viewport with `prefers-reduced-motion: reduce` produced a single-column public
  form with `scrollWidth == clientWidth == 390` and no horizontal overflow.

This is an AI-assisted direct browser review, not a claim that VoiceOver/NVDA was run.

## Open human gates

- VoiceOver or NVDA reading order and announcement smoke.

The screen-reader smoke remains `NOT RUN` by explicit owner decision; automated axe, DOM
semantics and the direct browser checks above are not presented as a substitute.
