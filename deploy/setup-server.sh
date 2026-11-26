#!/usr/bin/env bash
# One-time setup of a fresh Ubuntu 24.04 server for SquareToast. Run as root:
#   ssh -i ~/.ssh/squaretoast_deploy root@<ip> 'bash -s' < deploy/setup-server.sh
# Safe to run again. Installs Docker from Ubuntu's own packages, opens only SSH/HTTP/HTTPS, adds a `deploy` user,
# creates /opt/squaretoast/.env with a random database password, and a nightly database backup kept 7 days.
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive

apt-get update -q
apt-get install -yq docker.io docker-compose-v2 ufw openssl curl
systemctl enable --now docker

ufw allow OpenSSH >/dev/null; ufw allow 80/tcp >/dev/null; ufw allow 443/tcp >/dev/null
ufw --force enable >/dev/null

# Key-only SSH.
sed -i 's/^#\?PasswordAuthentication .*/PasswordAuthentication no/' /etc/ssh/sshd_config
systemctl reload ssh || systemctl reload sshd || true

# A 1 GB swap file so a memory spike slows down instead of killing the backend.
if ! swapon --show | grep -q /swapfile; then
  fallocate -l 1G /swapfile && chmod 600 /swapfile && mkswap /swapfile >/dev/null && swapon /swapfile
  grep -q '^/swapfile' /etc/fstab || echo '/swapfile none swap sw 0 0' >> /etc/fstab
fi

id deploy >/dev/null 2>&1 || useradd -m -s /bin/bash deploy
usermod -aG docker deploy
install -d -m 700 -o deploy -g deploy /home/deploy/.ssh
install -m 600 -o deploy -g deploy /root/.ssh/authorized_keys /home/deploy/.ssh/authorized_keys

install -d -o deploy -g deploy /opt/squaretoast /opt/squaretoast/backups
ENV=/opt/squaretoast/.env
if [ ! -f "$ENV" ]; then
  ip=$(curl -4fsS https://api.ipify.org || hostname -I | awk '{print $1}')
  {
    echo "PUBLIC_HOST=${ip//./-}.sslip.io"
    echo "POSTGRES_PASSWORD=$(openssl rand -hex 24)"
    echo "STRIPE_SECRET_KEY="
    echo "STRIPE_WEBHOOK_SECRETS="
  } > "$ENV"
fi
chown deploy:deploy "$ENV"; chmod 600 "$ENV"

cat > /etc/cron.daily/squaretoast-backup <<'CRON'
#!/bin/sh
# Nightly database dump, 7 days kept.
cd /opt/squaretoast || exit 0
f=backups/squaretoast-$(date +%F).sql.gz
docker compose exec -T postgres pg_dump -U squaretoast squaretoast | gzip > "$f" && chown deploy:deploy "$f"
find backups -name 'squaretoast-*.sql.gz' -mtime +7 -delete
CRON
chmod 755 /etc/cron.daily/squaretoast-backup

echo "ready: $(grep PUBLIC_HOST "$ENV")"
