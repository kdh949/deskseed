# UI, Visual and Accessibility Evidence

- Date: 2026-08-12 (Asia/Seoul)
- Final source: `feature/pr17-pr18-review-followup` at `06a0f8b1b2dacc68589d14b5bd88857da3978ffe`
- Pixel baseline: Playwright 1.62.1 Chromium, 42 images on each of Darwin and Linux
- Automated result: **LIMITED — Chromium and Firefox 41/41; current-source WebKit page setup not verified**
- Human visual/screen-reader sign-off: **NOT RUN**

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
| WebKit | NOT VERIFIED | setup timeout | browser launch succeeded, but `browserContext.newPage()` timed out before application setup in three exact-suite attempts and one bounded protocol smoke |

The WebKit split is implemented by `scripts/run-frontend-browser-e2e.sh`. On the prior
release-hardening base source, a single Playwright 1.62 WebKit process reproducibly stopped
responding near test 38, while the required two fresh processes passed 35/35 + 6/6 with
no retry/skip. On the final PR #22 frontend source, the local macOS WebKit executable
launched, but page creation timed out before any route, application code or assertion ran.
The wrapper failed closed; the prior pass is retained only as historical evidence and is
not presented as current-source proof.

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
WebKit are configured to execute the same functional, axe and keyboard assertions but
intentionally do not reuse Chromium pixel baselines. Firefox did so on the final source;
the WebKit final-source setup limitation is recorded above.

## Visual inspection performed

AI-assisted inspection after force regeneration reviewed the Darwin and Linux
1280/1440/1920 Audit Explorer reveal images and representative Views/Workspace layouts.
The routine value is consistently labelled `protected query` and rendered as
`[PROTECTED]`; the raw value appears only in the visibly separated, reason-gated reveal
panel. No clipping, overlap, hidden action, Zendesk logo/screenshot or color-only
PUBLIC/INTERNAL distinction was observed in those samples.

This inspection is not the human approval required by `checklists/release.md`.

## Open human gates

- VoiceOver or NVDA reading order and announcement smoke;
- keyboard-only human walkthrough at zoom/reflow and reduced-motion settings;
- human review of the complete 84-image final diff.

These remain `NOT RUN`; automated axe, keyboard and image assertions are not presented as
a substitute.
