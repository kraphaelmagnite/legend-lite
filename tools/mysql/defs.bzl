"""EMBEDDED_MYSQL_COMPATIBLE: the target_compatible_with of a target that starts the embedded MySQL."""

load("//tools/platforms:defs.bzl", "compatible_with")
load(":mysql.bzl", "BUILDS")

# A test that starts the embedded MySQL declares it, so it is skipped where no build is pinned (Linux x86_64 only).
EMBEDDED_MYSQL_COMPATIBLE = compatible_with(BUILDS.keys())
