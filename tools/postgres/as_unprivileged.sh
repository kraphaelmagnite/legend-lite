#!/bin/bash
# Runs a Bazel test as an unprivileged user, for a container that runs as root: initdb (the embedded Postgres,
# testing/.../EmbeddedPostgres.java) refuses root. Use it as the test's --run_under prefix, outside the sandbox:
#
#   chmod o+x /root    # once: the user may pass through the Bazel output base's parent (not list it)
#   bazel test //pct:pct_postgres --strategy=TestRunner=standalone \
#     --run_under="$PWD/tools/postgres/as_unprivileged.sh ubuntu"
#
# Bazel made the test's own directories as root, so they are handed to the user first; then root is dropped for
# good (setpriv: the real and effective uid and gid, the user's groups), the environment kept. Measured
# 2026-10-11 in a root container: PCT on Postgres, all five suites, green as "ubuntu". CI runs as a non-root user
# and needs none of this.
set -euo pipefail
if [ $# -lt 2 ]; then
  echo "usage: as_unprivileged.sh <user> <test binary> [args...]" >&2
  exit 2
fi
user="$1"
shift
if [ "$(id -u)" -ne 0 ]; then
  exec "$@"   # not root: nothing to drop
fi
id "$user" > /dev/null
if ! setpriv --reuid="$user" --regid="$user" --init-groups test -x "$1"; then
  echo "as_unprivileged.sh: $user cannot reach the test binary $1: let other users pass through the Bazel output" \
       "base's parent directories (chmod o+x /root)" >&2
  exit 2
fi
for d in "${TEST_TMPDIR:-}" "${TEST_UNDECLARED_OUTPUTS_DIR:-}" "${TEST_UNDECLARED_OUTPUTS_ANNOTATIONS_DIR:-}" \
         "$(dirname "${XML_OUTPUT_FILE:-/nonexistent/x}")"; do
  if [ -n "$d" ] && [ -d "$d" ]; then
    chown -R "$user" "$d"
  fi
done
exec setpriv --reuid="$user" --regid="$user" --init-groups "$@"
