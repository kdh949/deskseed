# UI, Visual and Accessibility Evidence

- Date: 2026-08-12 (Asia/Seoul)
- Final source: working tree on `chore/11-portfolio-release-hardening`
- Pixel baseline: Playwright 1.62.1 Chromium, 42 images on each of Darwin and Linux
- Automated result: **PASS — 41/41 on Chromium, Firefox and WebKit; 0 retry/skip**
- Human visual/screen-reader sign-off: **NOT RUN**

## Commands and results

From `frontend/`, the final source passed:

```bash
PLAYWRIGHT_DEV_SERVER_PORT=46310 PLAYWRIGHT_BROWSER=chromium npm run test:e2e:dev
PLAYWRIGHT_DEV_SERVER_PORT=46311 PLAYWRIGHT_BROWSER=firefox npm run test:e2e:dev
PLAYWRIGHT_DEV_SERVER_PORT=46312 PLAYWRIGHT_BROWSER=webkit npm run test:e2e:dev
```

| Engine | Result | Duration | Process shape |
|---|---:|---:|---|
| Chromium | 41/41 | 26.7s | one process |
| Firefox | 41/41 | 34.1s | one process |
| WebKit | 35/35 + 6/6 | 27.2s + 8.3s | two fresh processes, no retry/skip |

The WebKit split is implemented by `scripts/run-frontend-browser-e2e.sh`. A single
Playwright 1.62 WebKit process reproducibly stopped responding near test 38; both fresh
processes are mandatory and either failure fails the wrapper. This is isolation of an
engine-process lifetime issue, not a hidden retry.

Linux Chromium baselines were force-regenerated from the same source in the official
Playwright 1.62.1 Noble image. The run passed 41/41 in 3.7 minutes and wrote all 42 Linux
images; 15 differed from the previous baseline. The reproducible sequence from repository
root is:

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
reuse Chromium pixel baselines.

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
