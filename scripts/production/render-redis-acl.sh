#!/usr/bin/env bash
set -Eeuo pipefail

target=${1:?usage: render-redis-acl.sh OUTPUT_FILE}
: "${DESKSEED_CUSTOMER_AUTH_REDIS_PASSWORD:?set the Redis password before rendering the ACL}"

if [[ -e "$target" || -L "$target" ]]; then
  printf 'Refusing to overwrite existing Redis ACL: %s\n' "$target" >&2
  exit 2
fi
if [[ ! -d "$(dirname "$target")" ]]; then
  printf 'Redis ACL parent directory does not exist: %s\n' "$(dirname "$target")" >&2
  exit 2
fi

if command -v sha256sum >/dev/null 2>&1; then
  password_hash=$(printf '%s' "$DESKSEED_CUSTOMER_AUTH_REDIS_PASSWORD" | sha256sum)
elif command -v shasum >/dev/null 2>&1; then
  password_hash=$(printf '%s' "$DESKSEED_CUSTOMER_AUTH_REDIS_PASSWORD" | shasum -a 256)
else
  printf 'Required SHA-256 utility is unavailable.\n' >&2
  exit 127
fi
password_hash=${password_hash%% *}

umask 077
printf '%s\n' \
  'user default off resetkeys resetchannels -@all' \
  'user health on nopass resetkeys resetchannels -@all +ping' \
  "user deskseed on #$password_hash ~deskseed:customer-auth:limiter:v1:* resetchannels -@all +ping +get +pttl +incr +pexpire +eval +evalsha +script|load +client|setinfo" \
  >"$target"

printf 'Redis ACL written with mode 0600: %s\n' "$target"
