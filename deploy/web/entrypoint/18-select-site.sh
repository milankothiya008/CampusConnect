#!/bin/sh
# Serves HTTPS when certificates are mounted, plain HTTP otherwise. Runs before 20-envsubst-on-templates.sh.
# Generated files go to tmpfs directories: the container's file system is read-only.
set -eu
mkdir -p /etc/nginx/templates /etc/nginx/conf.d /etc/nginx/generated
CERT="${TLS_CERT:-/etc/nginx/certs/fullchain.pem}"
if [ -f "$CERT" ]; then
    cp /etc/nginx/sites/tls.conf.template /etc/nginx/templates/site.conf.template
    printf 'add_header Strict-Transport-Security "max-age=31536000; includeSubDomains" always;
' > /etc/nginx/generated/hsts.conf
    echo "campusconnect: TLS enabled ($CERT)"
else
    cp /etc/nginx/sites/plain.conf.template /etc/nginx/templates/site.conf.template
    : > /etc/nginx/generated/hsts.conf
    echo "campusconnect: no certificate at $CERT, serving plain HTTP"
fi
# Content-Security-Policy with the hashes of the SPA's inline scripts.
envsubst '${CSP_SCRIPT_HASHES}' < /etc/nginx/snippets/security-headers.conf.template > /etc/nginx/generated/security-headers.conf
