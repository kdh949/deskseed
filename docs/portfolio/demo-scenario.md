# Portfolio Demo Scenario

The automated demo is one command and uses disposable Compose projects with synthetic
data. It exercises the real frontend, HTTP API, PostgreSQL migrations and database-ledger
assertions; it does not use the development fixture routes.

```bash
cd /path/to/deskseed
npm --prefix frontend ci
npm --prefix frontend exec playwright install chromium
bash scripts/run-release-e2e.sh
```

Expected result:

- customer/staff full stack: 5 Playwright scenarios pass
- Audit Explorer full stack: 1 Playwright scenario passes
- each stack and named volume is removed on exit
- no access token or bootstrap password is printed

The stable-release browser smoke is separate from the production-Compose demo. From
`frontend/`, install the remaining engines with `npx playwright install firefox webkit`,
then run `PLAYWRIGHT_BROWSER=firefox npm run test:e2e:dev` and
`PLAYWRIGHT_BROWSER=webkit npm run test:e2e:dev`. Chromium owns the platform-specific
pixel baseline; Firefox and WebKit execute the same functional, axe and keyboard checks.

## What the automated run demonstrates

1. anonymous request creation and token-bound PUBLIC lookup;
2. staff login, view/search/detail and search-to-access-audit linkage;
3. PUBLIC reply visibility and INTERNAL-note customer non-disclosure;
4. two independent staff sessions causing a real same-field 409, preserving drafts and
   proving no partial ticket/comment/audit write;
5. transfer versus child ownership, child INTERNAL-only behavior and customer
   non-discovery;
6. Audit Explorer list/detail, authorization, self-audit, protected query reveal and
   canonical-to-projection row parity.

Export-request atomicity, canonical append-only rejection and explicit projection
rebuild are backend/database gates, not steps in this six-scenario browser wrapper. Their
commands and results are linked from the release verification summary.

## Interactive walkthrough

For a presentation, start a persistent local stack using a secret file outside the
repository:

```bash
cp .env.example .env
install -m 600 /dev/null /absolute/path/deskseed-first-admin.secret
# Put a unique 12–128 character development password in that file.
# Set the matching bootstrap *_FILE path and admin identity in .env.
DESKSEED_RUNTIME_USER="$(id -u):$(id -g)" docker compose up --build
```

Run the bootstrap as a non-root host account. The explicit uid/gid lets the non-root
backend read that account's mode-`0600` file-backed Compose secret on Linux.

Then use this route order:

| Step | Route | Story |
|---|---|---|
| 1 | `/requests/new` | Create a synthetic customer request; the body becomes the first PUBLIC comment |
| 2 | `/requests/{number}` | Follow the returned capability in the same browser and show the PUBLIC-only projection |
| 3 | `/agent/login` | Sign in with the bootstrapped admin; disable bootstrap after first login |
| 4 | `/admin/staff`, `/admin/groups` | Create an Agent and Security Auditor and assign the Agent to an active group |
| 5 | `/agent/views/my-open`, `/agent/search` | Show dense queue, keyboard row open and audited search |
| 6 | `/agent/tickets/{number}` | Add separate PUBLIC and INTERNAL drafts, save a combined command, and compare customer visibility |
| 7 | same ticket in two browser profiles | Save the same field in both; show focused conflict banner and retained drafts |
| 8 | transfer/child controls | Contrast ownership movement with INTERNAL child creation and the non-blocking solve warning |
| 9 | `/audit/activity` as auditor | Traverse change/access/search/admin activity and show that opening audit data is itself audited |

Visible export state must be described as an export **request** only. No artifact or
download is generated in this release.

## Stop and clean up

```bash
docker compose down --volumes --remove-orphans
```

Remove the local bootstrap secret after confirming first login and never reuse it for a
public or production environment. The current release is a local/private-network
portfolio deployment; anonymous email ownership and layered abuse controls are not yet
complete.
