#!/bin/sh
set -eu

: "${APP_HOST:?APP_HOST is required}"
: "${KEYCLOAK_HOST:?KEYCLOAK_HOST is required}"
: "${PUBLIC_HTTPS_PORT:?PUBLIC_HTTPS_PORT is required}"
case "$PUBLIC_HTTPS_PORT" in
  ''|*[!0-9]*) echo "PUBLIC_HTTPS_PORT must be numeric" >&2; exit 1 ;;
esac

umask 077
mkdir -p /tmp/nginx/client_body /tmp/nginx/proxy_temp /tmp/nginx/fastcgi_temp \
  /tmp/nginx/uwsgi_temp /tmp/nginx/scgi_temp
envsubst '$APP_HOST $KEYCLOAK_HOST $PUBLIC_HTTPS_PORT' \
  < /etc/nginx/nginx.conf.template > /tmp/nginx/nginx.conf
/usr/sbin/nginx -t -c /tmp/nginx/nginx.conf
exec /usr/sbin/nginx -c /tmp/nginx/nginx.conf -g 'daemon off;'
