# Build the frontend and package it with the official, digest-pinned Caddy.
# The final edge artifact is built once and serves the exact static bundle used
# by the disposable HTTPS browser smoke.
ARG NODE_IMAGE=node:22-alpine3.23@sha256:6abbcf5dd6b231da109bcde1181db1d92bf78015ae1a9514d9807c1f857869db
ARG CADDY_IMAGE=caddy:2.11.4-alpine@sha256:6aeddd44c3078b0f9a35206472a11420648a79c184603ef95957d0a20044cb2b

FROM ${NODE_IMAGE} AS frontend-build
WORKDIR /workspace
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run check

FROM ${CADDY_IMAGE} AS edge
RUN setcap -r /usr/bin/caddy && test -z "$(getcap /usr/bin/caddy)"
COPY --from=frontend-build /workspace/dist/ /srv/
COPY infra/staging/Caddyfile /etc/caddy/Caddyfile
USER 1000:1000
EXPOSE 8443
ENTRYPOINT ["caddy", "run", "--config", "/etc/caddy/Caddyfile", "--adapter", "caddyfile"]
