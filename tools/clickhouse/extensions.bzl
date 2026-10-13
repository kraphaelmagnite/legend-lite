"""The embedded ClickHouse repositories: one per platform with a pinned build, lazily fetched, and the hub."""

load(":clickhouse.bzl", "BUILDS", "embedded_clickhouse_hub", "embedded_clickhouse_platform")

def _embedded_clickhouse_impl(module_ctx):
    for platform, (arch, sha256) in BUILDS.items():
        embedded_clickhouse_platform(
            name = "embedded_clickhouse_" + platform,
            arch = arch,
            platform = platform,
            sha256 = sha256,
        )
    embedded_clickhouse_hub(name = "embedded_clickhouse", platforms = BUILDS.keys())
    return module_ctx.extension_metadata(reproducible = True)

embedded_clickhouse = module_extension(implementation = _embedded_clickhouse_impl)
