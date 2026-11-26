#!/usr/bin/env bash
# Makes a self-signed localhost certificate for local device testing:
#   .local-dev/keystore.p12 + .local-dev/keystore-password   the backend's TLS key (Spring server.ssl)
#   android-pos/local-dev/res/raw/local_dev_ca.pem            trusted by debug builds with -Psquaretoast.localCloudUrl
# All three are gitignored. Re-run to rotate.
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
mkdir -p .local-dev android-pos/local-dev/res/raw
rm -f .local-dev/keystore.p12
pass=$(head -c 24 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 24)
printf '%s' "$pass" > .local-dev/keystore-password
keytool -genkeypair -alias local -keyalg EC -groupname secp256r1 -validity 30 \
  -dname "CN=localhost, O=SquareToast local dev" -ext "SAN=dns:localhost,ip:127.0.0.1" \
  -storetype PKCS12 -keystore .local-dev/keystore.p12 -storepass "$pass" -keypass "$pass" >/dev/null 2>&1
keytool -exportcert -rfc -alias local -keystore .local-dev/keystore.p12 -storepass "$pass" \
  -file android-pos/local-dev/res/raw/local_dev_ca.pem >/dev/null 2>&1
echo "certificate ready (valid 30 days)"
