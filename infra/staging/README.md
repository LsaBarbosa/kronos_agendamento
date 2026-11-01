# AGD-003 HTTPS staging preparation

This directory contains a local, disposable HTTPS topology only. It binds the published edge port to loopback, uses synthetic `.test` names and a per-run private CA, and does not provision public DNS or certificates.

Run the complete local check from the repository root:

```sh
scripts/staging/run-disposable.sh
```

Requirements: Linux amd64, Docker Engine with Compose v2, OpenSSL, curl, Python 3, `ip`, and Google Chrome/Chromium. The disposable runner builds a digest-pinned frontend/Caddy edge image plus the existing backend and Keycloak Dockerfiles, creates separate PostgreSQL databases, imports a synthetic realm and provisions one associated and one unassociated user. It verifies the HTTPS chain/hostnames, discovery issuer, fixed authorization/callback and PKCE, session cookie flags, tenant association, CSRF, logout and association revocation. It waits for readiness by polling; no sleep is used as completion evidence.

The local CA is trusted only by `curl` through `--cacert`, Python's SSL context, and the isolated Chrome process through the SHA-256 SPKI allowlist for the temporary leaf certificate. The host trust store is untouched. The per-run env file and temporary files are mode-restricted and removed. Compose project, internal and ingress subnets, images, volumes and loopback port are run-specific; cleanup is limited to that project, without prune commands. App, Keycloak and databases attach only to the internal network; edge alone also joins the ingress bridge so the loopback-published port is reachable. This permits egress from edge, but the image is unprivileged/read-only and has only static routes to app and Keycloak; no external provider is configured.

To prove cleanup does not remove another project's stopped resources:

```sh
scripts/staging/test-cleanup-isolation.sh
```

This creates a synthetic stopped container, network and volume under a separate unique Compose project, runs the disposable stack and asserts all three sentinel resources still exist before removing only its own sentinel project.

For external configuration review without starting services:

```sh
chmod 0600 /secure/path/staging.env
scripts/staging/preflight.sh --env-file /secure/path/staging.env --project-name agendamento-staging
```

`infra/staging/staging.env.example` is a shape-only template; secret fields are blank and domains use the reserved `.invalid` suffix. The Compose configuration intentionally refuses non-loopback binding in this increment. A hosted staging endpoint, domains, DNS, public certificate issuance/renewal, secret-manager integration, operator access and approval are separate operational work. Do not use the generated local realm, credentials or certificate outside the disposable runner.
