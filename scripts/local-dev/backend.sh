#!/usr/bin/env bash
# Runs Postgres in Docker and the backend on https://localhost:8443 for local device testing.
# Stripe test-mode keys come from your own environment; this script never prints them:
#   SQUARETOAST_STRIPE_SECRET_KEY=sk_test_...        (live keys are refused)
#   SQUARETOAST_STRIPE_WEBHOOK_SECRETS=whsec_...      (from `stripe listen`, optional)
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
[ -f .local-dev/keystore.p12 ] || scripts/local-dev/make-cert.sh
case "${SQUARETOAST_STRIPE_SECRET_KEY:-}" in
  ""|sk_test_*|rk_test_*) ;;
  *) echo "refusing: SQUARETOAST_STRIPE_SECRET_KEY is not a test-mode key" >&2; exit 1 ;;
esac

if ! docker ps --format '{{.Names}}' | grep -qx squaretoast-pg; then
  docker rm -f squaretoast-pg >/dev/null 2>&1 || true
  docker run -d --name squaretoast-pg -p 127.0.0.1:55432:5432 \
    -e POSTGRES_DB=squaretoast -e POSTGRES_USER=squaretoast -e POSTGRES_PASSWORD=local-dev-only \
    postgres:17 >/dev/null
  until docker exec squaretoast-pg pg_isready -U squaretoast >/dev/null 2>&1; do sleep 1; done
fi

export SPRING_DATASOURCE_URL=jdbc:postgresql://127.0.0.1:55432/squaretoast
export SPRING_DATASOURCE_USERNAME=squaretoast
export SPRING_DATASOURCE_PASSWORD=local-dev-only
export SERVER_PORT=8443
export SERVER_ADDRESS=127.0.0.1
export SERVER_SSL_KEYSTORE="file:$(pwd -W 2>/dev/null || pwd)/.local-dev/keystore.p12"
export SERVER_SSL_KEYSTORETYPE=PKCS12
SERVER_SSL_KEYSTOREPASSWORD="$(cat .local-dev/keystore-password)"
export SERVER_SSL_KEYSTOREPASSWORD
# Spring binds squaretoast.stripe.secret-key from SQUARETOAST_STRIPE_SECRETKEY (no dash in env names).
export SQUARETOAST_STRIPE_SECRETKEY="${SQUARETOAST_STRIPE_SECRET_KEY:-}"
export SQUARETOAST_STRIPE_WEBHOOKSECRETS="${SQUARETOAST_STRIPE_WEBHOOK_SECRETS:-}"
exec ./gradlew :backend:bootRun --console=plain
