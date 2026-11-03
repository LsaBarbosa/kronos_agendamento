# Build the frontend and package it with the official, digest-pinned NGINX.
# The final edge artifact is built once and serves the exact static bundle used
# by the disposable HTTPS browser smoke.
ARG NODE_IMAGE=node:22-alpine3.23@sha256:6abbcf5dd6b231da109bcde1181db1d92bf78015ae1a9514d9807c1f857869db
ARG NGINX_IMAGE=nginx:1.30.6-alpine3.24-slim@sha256:dede8895648b461178c3bc604f7eed4423efe5220b2c4d839f5eb3d34ac3f8f7

FROM ${NODE_IMAGE} AS frontend-build
WORKDIR /workspace
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run check

FROM ${NGINX_IMAGE} AS edge
COPY --from=frontend-build /workspace/dist/ /srv/
COPY infra/staging/nginx.conf.template /etc/nginx/nginx.conf.template
COPY infra/staging/empty-response /etc/nginx/empty-response
COPY infra/staging/nginx-entrypoint.sh /usr/local/bin/kronos-nginx-entrypoint
RUN chmod 0555 /usr/local/bin/kronos-nginx-entrypoint
USER 1000:1000
EXPOSE 8443
ENTRYPOINT ["/usr/local/bin/kronos-nginx-entrypoint"]
