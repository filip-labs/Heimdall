#!/usr/bin/env bash
# Generate disposable local-development keys, never deployment credentials.
set -euo pipefail
umask 077

if (( $# > 1 )); then
  echo "Usage: bash scripts/dev/generate-compose-env.sh [output-file]" >&2
  exit 1
fi
repo_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
output=${1:-"$repo_root/.env"}
if [[ -e "$output" || -L "$output" ]]; then
  echo "Refusing to overwrite existing file: $output" >&2
  exit 1
fi
command -v openssl >/dev/null || { echo "OpenSSL is required" >&2; exit 1; }
key_dir=$(mktemp -d)
trap 'rm -rf -- "$key_dir"' EXIT
openssl genpkey -algorithm Ed25519 -out "$key_dir/private.pem"
openssl pkcs8 -topk8 -nocrypt -in "$key_dir/private.pem" \
  -outform DER -out "$key_dir/private.der"
openssl pkey -in "$key_dir/private.pem" -pubout \
  -outform DER -out "$key_dir/public.der"
vehicle_secret=$(openssl rand -base64 32)
private_key=$(openssl base64 -A -in "$key_dir/private.der")
public_key=$(openssl base64 -A -in "$key_dir/public.der")

{
  echo '# Generated local-development keys. Do not commit or use for deployment.'
  echo "HEIMDALL_SECURITY_VEHICLE_TOKEN_SECRET=$vehicle_secret"
  echo 'HEIMDALL_SECURITY_VEHICLE_TOKEN_TTL=5m'
  echo "HEIMDALL_SECURITY_RELEASE_SIGNING_PRIVATE_KEY=$private_key"
  echo 'HEIMDALL_SECURITY_RELEASE_SIGNING_KEY_ID=local-compose-ed25519-v1'
  echo "HEIMDALL_OTA_SIGNING_PUBLIC_KEY=$public_key"
  echo 'HEIMDALL_OTA_SIGNING_KEY_ID=local-compose-ed25519-v1'
} > "$key_dir/compose.env"

# noclobber also prevents overwriting a file created since the check above.
(set -o noclobber; cat "$key_dir/compose.env" > "$output")
echo "Created local-development environment: $output (mode 600). Keys were not printed."
