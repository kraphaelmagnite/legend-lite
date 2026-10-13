"""EMBEDDED_MARIADB_COMPATIBLE: the target_compatible_with of a target that starts the embedded MariaDB."""

load("//tools/platforms:defs.bzl", "compatible_with")
load(":mariadb.bzl", "BUILDS")

# A test that starts the embedded MariaDB declares it, so it is skipped where no build is pinned (Linux x86_64 only).
EMBEDDED_MARIADB_COMPATIBLE = compatible_with(BUILDS.keys())
