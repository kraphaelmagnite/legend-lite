"""The embedded MariaDB repositories: one per platform with a pinned build, lazily fetched, and the hub."""

load(":mariadb.bzl", "BUILDS", "embedded_mariadb_hub", "embedded_mariadb_platform")

def _embedded_mariadb_impl(module_ctx):
    for platform, (bintar, sha256) in BUILDS.items():
        embedded_mariadb_platform(
            name = "embedded_mariadb_" + platform,
            bintar = bintar,
            platform = platform,
            sha256 = sha256,
        )
    embedded_mariadb_hub(name = "embedded_mariadb", platforms = BUILDS.keys())
    return module_ctx.extension_metadata(reproducible = True)

embedded_mariadb = module_extension(implementation = _embedded_mariadb_impl)
