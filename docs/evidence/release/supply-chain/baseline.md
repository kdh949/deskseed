# Dependency, License and Container Security Baseline

- Executed: 2026-08-12 (Asia/Seoul)
- Repository: `kdh949/deskseed` (public)
- Branch: `chore/11-portfolio-release-hardening`
- Base revision: `d1f7bfbaea6946992d2bf7403f51c5dbc4948fdb`
- Evidence status: **LIMITED** — dependency and image builds pass, but the local
  Docker Scout indexer did not complete, so container CVE and SPDX verdicts are unknown.

The final no-cache build below was produced from the release working tree and tied to
captured backend/frontend Docker-context fingerprints. Its images were deliberately
run-scoped and deleted after exact cleanup, so the recorded IDs are provenance evidence,
not reusable local tags. Docker Scout did not complete against the earlier standalone
images and was not rerun against the final ephemeral images; no container vulnerability
or SPDX verdict is implied.

## Toolchain

| Tool | Version used |
|---|---|
| host Node.js / npm | 25.9.0 / 11.12.1 |
| Gradle wrapper | 9.7.0; distribution SHA-256 is pinned in wrapper properties |
| host JVM / OS for dependency report | Temurin 26.0.1; macOS 26.5.2 arm64 |
| Docker client / server | 29.3.0 / 29.6.2; Docker Desktop 4.85.0 |
| Docker Buildx | 0.35.0-desktop.2 |
| Docker Scout | 1.24.0 (`b1c9331b2166aef7ec690aa16fd655b8798ea4c6`) |

Input fingerprints used for this run:

| Input | SHA-256 |
|---|---|
| `frontend/package.json` | `b1ff50a11e35e9f10ed548a7812256992887727cf866c7c07fa461cb99108d3a` |
| `frontend/package-lock.json` | `80d63ebabdc612a5852847bfa48aeb29fae16110f3f5796879b183aea67588f7` |
| `frontend/Dockerfile` | `62f30cbafcb4e6b77c44983e90cc696f9baadc399526cc3ce4ac32261b59b728` |
| `scripts/audit-frontend-licenses.mjs` | `46063673ec8285fa7ec0c09043edd5c74b894e014f0227998a7e7bed40cd6dc0` |
| `backend/build.gradle.kts` | `9afdded91d12f269515c8669c6a4fbfd9d1a63b75e7641ecad4903595c4640c7` |
| `backend/gradle/wrapper/gradle-wrapper.properties` | `da66a5ffe08e2edfed40553bb83548943a0b522603a833852bce317699e6fa9c` |
| `backend/Dockerfile` | `d99159fea8a53ae90017d07ee6d464d71d539c174c545aaf198c8f8dd8bd309d` |

Regenerate the fingerprints with:

```bash
shasum -a 256 \
  frontend/package.json frontend/package-lock.json frontend/Dockerfile \
  scripts/audit-frontend-licenses.mjs \
  backend/build.gradle.kts \
  backend/gradle/wrapper/gradle-wrapper.properties backend/Dockerfile
```

## Frontend advisories

Commands:

```bash
cd frontend
npm audit --omit=dev --json
npm audit --json
```

Both commands exited 0 on 2026-08-12. Production-only and all-dependency
audits each reported **0 total advisories**: 0 critical, 0 high, 0 moderate,
0 low and 0 informational. npm reported 38 production, 228 development,
27 optional, 10 peer and 265 total dependency locations.

The pre-remediation production audit reported **2 high and 1 moderate** vulnerable
package paths. `react-router` 7.9.6 and `styled-components` 6.1.19 were upgraded within
their existing major versions to 7.18.2 and 6.5.2 respectively. The final local
production build produced 135.84 kB gzip JavaScript and 9.74 kB gzip CSS, below the
200 kB JavaScript release budget. The final no-cache container IDs and their captured
source fingerprints are recorded below.

The Garden dependency tree still emits npm peer-range warnings because transitive Reach
0.18 metadata declares React 16/17 while the tested application uses React 18.3.1. It also
contains deprecated `lodash.get` 4.4.2 transitively. Neither is a current npm advisory;
both remain dependency upgrade-watch items.

## Frontend license inventory

Command:

```bash
node scripts/audit-frontend-licenses.mjs
```

Result: **PASS**. The deterministic auditor reads the committed lockfile rather than
network or `node_modules`. It found 265 installed package locations and no missing
license field.

| License identifier | Installed locations |
|---|---:|
| MIT | 203 |
| Apache-2.0 | 23 |
| MPL-2.0 | 14 |
| ISC | 9 |
| BSD-2-Clause | 8 |
| BSD-3-Clause | 3 |
| BlueOak-1.0.0 | 2 |
| MIT-0 | 2 |
| CC0-1.0 | 1 |

The direct runtime packages resolved by the lockfile are:

| Package | Version | License |
|---|---:|---|
| `@tanstack/react-query` | 5.101.4 | MIT |
| `@zendeskgarden/react-buttons` | 9.15.7 | Apache-2.0 |
| `@zendeskgarden/react-theming` | 9.15.7 | Apache-2.0 |
| `react` | 18.3.1 | MIT |
| `react-dom` | 18.3.1 | MIT |
| `react-router` | 7.18.2 | MIT |
| `styled-components` | 6.5.2 | MIT |

No AGPL/GPL production runtime dependency was identified. MPL dependencies occur in the
development graph and remain subject to their file-level terms. This is a mechanical
inventory, not legal advice. Notices are maintained in `THIRD_PARTY_NOTICES.md`.

## Backend dependency inventory

Commands:

```bash
cd backend
./gradlew --version
./gradlew -q dependencies --configuration runtimeClasspath
```

Result: **PASS**. The resolved runtime graph includes Kotlin 2.4.10, Spring Boot 4.1.0,
Spring Framework 7.0.8, Spring Security 7.1.0, Hibernate 7.4.1.Final, Flyway 12.4.0,
PostgreSQL JDBC 42.7.11, Jackson 3.1.4 and Spring Modulith 2.1.0.

The Gradle distribution itself is protected by
`distributionSha256Sum=84fbba45c7f4c64abc77460e1c00f541e9f960e3c7ed2538f1ede19eacd873ae`.
The project does not currently commit Gradle dependency locks or verification metadata;
the resolved report is therefore an inventory, not a reproducible checksum allowlist or
a vulnerability verdict.

GitHub Dependabot alerts/security updates are disabled for this repository: the alert API
returned HTTP 403. Code scanning returned HTTP 404 `no analysis found`. Consequently no
server-side Gradle advisory baseline is available. Enabling those repository-owner
settings is outside this PR and remains an explicit limitation.

## Container build evidence

The current proof is the full operations rehearsal:

```bash
./scripts/run-operations-rehearsal.sh \
  --evidence-file docs/evidence/release/operations/2026-08-12-macos-arm64-full.md
```

The runner used a task-owned anonymous Docker client, pulled public base images, bypassed
build-cache reuse, captured the application build contexts immediately before build, and
rechecked them after build, restore and cleanup. All three exact images were pinned into
both Compose projects before the first application start.

| Image | Result | Final run-scoped image ID | Current source proof |
|---|---|---|---|
| backend | PASS; clean `bootJar` and health | `sha256:a4234ae421fee9d6d21bd50ca9e91c92a956b4ee2f284390381469f56d117f7f` | backend context `sha256:710f3628…`, 148 files |
| frontend | PASS; `npm ci`, production build and health | `sha256:a0a4e6e97aa1c5043c23972b0369b62460d21ffb81c375272f9751b5dc2e4af5` | frontend context `sha256:12701749…`, 194 files |
| PostgreSQL | PASS; identical source/restore image | `sha256:ca8550761466dbd3d4dd6f69686d372966ec7eb0721ed8fb945486aa30de3b4f` | `postgres@sha256:742f40ea20b9ff2ff31db5458d127452988a2164df9e17441e191f3b72252193` |

The rehearsal removed these run-scoped images and verified their absence before publishing
PASS evidence. Consequently exact final image sizes were not retained. The backend
Dockerfile runs as `deskseed`; the frontend image retains the nginx default root master
needed for port 80 and is not a fully non-root container.

An earlier standalone build, now superseded for source provenance, produced backend
`sha256:1cbca2f…` (136,873,860 B) and frontend `sha256:a6a8a3d…`
(26,105,874 B). Those were the inputs to the incomplete Scout attempts below. BuildKit
resolved these base-image digests during that earlier observation:

| Stage | Resolved digest |
|---|---|
| `eclipse-temurin:21-jdk-alpine` | `sha256:1ff763083f2993d57d0bf374ab10bb3e2cb873af6c13a04458ebbd3e0337dc76` |
| `eclipse-temurin:21-jre-alpine` | `sha256:3f08b13888f595cc49edabea7250ba69499ba25602b267da591720769400e08c` |
| `node:26-alpine` | `sha256:aadf416b2cdce311a8811ba3f0608a61b77dbf997500e2eafe781b51f6a0b019` |
| `nginx:1.31-alpine` | `sha256:4a73073bd557c65b759505da037898b61f1be6cbcc3c2c3aeac22d2a470c1752` |

The Dockerfiles still name mutable tags rather than these digests. The final rehearsal's
anonymous pull succeeded and its exact resulting image IDs are preserved above, but the
stage-level base digests were not separately exported. This is reproducible local build
evidence, not a registry freshness or provenance-attestation claim.

## Container CVE and SBOM baseline

The final operations images were deliberately deleted after exact cleanup. To rerun Scout,
start from a clean checkout of the exact release commit, build new scan-only tags, capture
their IDs/revision labels, and then scan those tags rather than any older local tag:

```bash
test -z "$(git status --porcelain)"
release_revision="$(git rev-parse --verify HEAD)"

docker build --pull --no-cache \
  --label "org.opencontainers.image.revision=$release_revision" \
  -t "deskseed-scout-backend:$release_revision" backend
docker build --pull --no-cache \
  --label "org.opencontainers.image.revision=$release_revision" \
  -t "deskseed-scout-frontend:$release_revision" frontend
docker image inspect \
  "deskseed-scout-backend:$release_revision" \
  "deskseed-scout-frontend:$release_revision"

docker scout cves --only-severity critical,high --exit-code \
  "local://deskseed-scout-backend:$release_revision"
docker scout cves --only-severity critical,high --exit-code \
  "local://deskseed-scout-frontend:$release_revision"
docker scout sbom --format spdx --output backend.spdx.json \
  "local://deskseed-scout-backend:$release_revision"
docker scout sbom --format spdx --output frontend.spdx.json \
  "local://deskseed-scout-frontend:$release_revision"
```

Evidence status: **LIMITED**. Container CVE/SPDX verdict: **UNKNOWN**.

- Backend CVE indexing produced no result for more than two minutes and was interrupted;
  the CLI returned 255. It must not be interpreted as zero findings.
- Backend SPDX generation stored the image for indexing, then produced no result for more
  than 90 seconds and was interrupted with exit 255. No partial output file remained.
- Repeating the same stuck indexer for frontend would not add evidence, so frontend CVE
  and SPDX generation are recorded as **NOT RUN after tool failure**.
- No Scout process remained after interruption.

The lockfile license report, Gradle runtime graph, successful clean image builds and exact
base/final image IDs are useful alternatives, but they do **not** replace an OS/JVM image
CVE database scan or a complete image SBOM. A release owner must rebuild from the final
clean commit and rerun all four scan commands in an environment where Scout indexing
completes; any critical/high result is a release blocker until remediated or explicitly
risk-accepted.

## Repository secret scanning

Commands:

```bash
gh api repos/kdh949/deskseed --jq '{visibility,security_and_analysis}'
gh api 'repos/kdh949/deskseed/secret-scanning/alerts?state=open&per_page=100' \
  --jq 'length'
```

Result: **PASS for provider-pattern coverage**. GitHub secret scanning and push
protection are enabled; open secret-scanning alerts are **0**. Non-provider patterns and
validity checks are disabled. Local synthetic/default development credentials are
documented, while production profile startup requires separately supplied runtime,
migration, audit and cursor secrets.

## Project license and distribution

The project itself has no `LICENSE`. A public repository does not grant an open-source
reuse license by implication. Dependency notices remain in `THIRD_PARTY_NOTICES.md`, but
the copyright owner must choose distribution terms before an open-source release. The
portfolio release must not claim to be MIT, Apache-licensed or open source until that human
decision is made.
