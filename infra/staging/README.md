# AGD-003 HTTPS staging preparation

This directory contains a local, disposable HTTPS topology only. It binds the published edge port to loopback, uses synthetic `.test` names and a per-run private CA, and does not provision public DNS or certificates.

Run the complete local check from the repository root:

```sh
scripts/staging/run-disposable.sh
```

Requirements: Linux amd64, Docker Engine with Compose v2, OpenSSL, curl, Python 3, `ip`, and Google Chrome/Chromium. The disposable runner builds a digest-pinned frontend/official NGINX edge image plus the existing backend and Keycloak Dockerfiles, creates separate PostgreSQL databases, imports a synthetic realm and provisions one associated and one unassociated user. It verifies the HTTPS chain/hostnames, discovery issuer, fixed authorization/callback and PKCE, session cookie flags, tenant association, CSRF, logout and association revocation. It waits for readiness by polling; no sleep is used as completion evidence.

The edge allows only matching SNI/Host pairs for the application and Keycloak origins; crossed pairs are rejected with an empty 421 response. `/api` and `/api/…` are proxied to the backend so API misses cannot fall through to the SPA document.

The runner takes a private per-project `flock` before checking for pre-existing containers, volumes, networks and image tags, and retains it through project-scoped cleanup. A failed cleanup, including timeout, makes the runner exit nonzero and leaves its mode-restricted temporary configuration available for cleanup recovery. Lock files remain under the private `/tmp/agd003-runner-locks` directory so a waiter can never lock a replacement inode after a prior run exits.

The local CA is trusted only by `curl` through `--cacert`, Python's SSL context, and the isolated Chrome process through the SHA-256 SPKI allowlist for the temporary leaf certificate. The host trust store is untouched. The per-run env file and temporary files are mode-restricted and removed. Compose project, internal and ingress subnets, images, volumes and loopback port are run-specific; cleanup is limited to that project, without prune commands. App, Keycloak and databases attach only to the internal network; edge alone also joins the ingress bridge so the loopback-published port is reachable. This permits egress from edge, but the image is unprivileged/read-only and has only static routes to app and Keycloak; no external provider is configured.

To prove cleanup does not remove another project's stopped resources:

```sh
scripts/staging/test-cleanup-isolation.sh
```

This creates a synthetic stopped container, network and volume under a unique Compose project. It first tags a local synthetic image with a tag that collides with that project, runs the runner with the colliding project name, and asserts refusal without removing the container, volume, network or image tag. It then runs the normal disposable stack and proves the foreign sentinel resources survive its cleanup.

Run the deterministic lifecycle regressions without Docker:

```sh
python3 scripts/staging/test-run-disposable-lifecycle.py
```

These tests use separate processes and mocked Docker/Compose operations to cover project-lock contention, collision refusal without cleanup, partial creation followed by cleanup, nonzero status on cleanup timeout, and preservation of the primary failure. They complement, but do not replace, the Docker-backed sentinel test above.

To build, smoke-test and scan the exact same edge Image ID under the established policy:

```sh
scripts/staging/scan-edge.sh
```

The script runs the HTTPS/OIDC smoke, verifies the unique edge tag resolves to the emitted Image ID, exports that image, refreshes the official Trivy 0.75.0 vulnerability database, scans with HIGH/CRITICAL and `ignore-unfixed=false`, and saves the full JSON report, CycloneDX inventory, APK inventory and database metadata under a digest-named directory in `docs/handoffs/`. The exit status remains nonzero when findings violate policy. It removes only this run's project tags after artifacts are written; no ignore file or suppression is used. The App CI `edge-staging` job runs this same script. Its scan is a separate gate from the backend and Keycloak image scans.

For external configuration review without starting services:

```sh
chmod 0600 /secure/path/staging.env
scripts/staging/preflight.sh --env-file /secure/path/staging.env --project-name agendamento-staging
```

`infra/staging/staging.env.example` is a shape-only template; secret fields are blank and domains use the reserved `.invalid` suffix. The Compose configuration intentionally refuses non-loopback binding in this increment. A hosted staging endpoint, domains, DNS, public certificate issuance/renewal, secret-manager integration, operator access and approval are separate operational work. Do not use the generated local realm, credentials or certificate outside the disposable runner.
