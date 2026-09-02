#!/usr/bin/env sh
set -eu

source_acl=/run/secrets/deskseed-redis-acl
target_acl=/run/deskseed-redis/users.acl

redis_uid="$(id -u redis)"
redis_gid="$(id -g redis)"
install -m 0400 -o "$redis_uid" -g "$redis_gid" "$source_acl" "$target_acl"

exec /usr/local/bin/docker-entrypoint.sh "$@"
