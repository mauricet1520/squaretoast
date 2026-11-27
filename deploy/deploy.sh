#!/usr/bin/env bash
# Builds SquareToast on this PC and ships it to the server, then restarts it with Docker Compose.
#   deploy/deploy.sh <server-ip>
# Needs: the server set up with setup-server.sh, and ~/.ssh/squaretoast_deploy.
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
HOST=${1:?usage: deploy/deploy.sh <server-ip>}
KEY=${SQUARETOAST_SSH_KEY:-$HOME/.ssh/squaretoast_deploy}
SSH=(ssh -i "$KEY" -o StrictHostKeyChecking=accept-new "deploy@$HOST")

echo "== build"
./gradlew :backend:bootJar --console=plain -q
npm --prefix web-kds-qr run build --silent
JAR=$(ls backend/build/libs/*.jar | grep -v -- '-plain' | head -n1)

echo "== refuse live Stripe keys on the server (checks the prefix only; prints nothing)"
"${SSH[@]}" 'k=$(grep -E "^STRIPE_SECRET_KEY=" /opt/squaretoast/.env | cut -d= -f2); case "$k" in ""|sk_test_*|rk_test_*) ;; *) echo "live or unknown Stripe key on the server: refusing" >&2; exit 1;; esac'

echo "== ship"
STAGE=$(mktemp -d)
trap 'rm -rf "$STAGE"' EXIT
cp deploy/docker-compose.yml deploy/Caddyfile deploy/backend.Dockerfile "$STAGE/"
cp "$JAR" "$STAGE/app.jar"
cp -r web-kds-qr/dist "$STAGE/web"
tar -C "$STAGE" -czf - . | "${SSH[@]}" 'cd /opt/squaretoast && rm -rf web && tar xzf -'

echo "== start"
"${SSH[@]}" 'cd /opt/squaretoast && docker compose up -d --build --remove-orphans && docker image prune -f >/dev/null'

PUBLIC=$("${SSH[@]}" 'grep ^PUBLIC_HOST= /opt/squaretoast/.env | cut -d= -f2')
echo "== check https://$PUBLIC"
for i in $(seq 1 60); do
  code=$(curl -s -o /dev/null -w '%{http_code}' "https://$PUBLIC/api/v1/devices/me" || true)
  [ "$code" = "401" ] && { echo "up: https://$PUBLIC (API answers; KDS at https://$PUBLIC/kds)"; exit 0; }
  sleep 3
done
echo "not answering yet; see: ssh deploy@$HOST 'cd /opt/squaretoast && docker compose logs --tail 50 backend'" >&2
exit 1
