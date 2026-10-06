#!/bin/sh
set -eu
umask 077
# Compose bind-mounted secrets retain host ownership. Read as root once, then
# drop privilege. /run/private is tmpfs; no secret is written into an image layer.
mkdir -p /run/private
chmod 700 /run/private
for input in /run/input/*; do
  [ -f "$input" ] || continue
  # Refuse world/group-readable host inputs, including deployment configuration.
  mode=$(stat -c '%a' "$input")
  case "$mode" in 400|600) ;; *) echo '{"event":"secret_permissions_invalid"}' >&2; exit 1;; esac
  cp "$input" "/run/private/$(basename "$input")"
  chmod 600 "/run/private/$(basename "$input")"
done
chown -R 10001:10001 /run/private
if [ -d /state ]; then chown 10001:10001 /state; chmod 700 /state; fi
if command -v gosu >/dev/null; then exec gosu 10001:10001 "$@"; fi
exec su-exec 10001:10001 "$@"
