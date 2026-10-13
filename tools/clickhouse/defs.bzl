"""EMBEDDED_CLICKHOUSE_COMPATIBLE: the target_compatible_with of a target that starts the embedded ClickHouse."""

load("//tools/platforms:defs.bzl", "compatible_with")
load(":clickhouse.bzl", "BUILDS")

# A test that starts the embedded ClickHouse declares it, so it is skipped where no build is pinned (Linux x86_64 only).
EMBEDDED_CLICKHOUSE_COMPATIBLE = compatible_with(BUILDS.keys())
