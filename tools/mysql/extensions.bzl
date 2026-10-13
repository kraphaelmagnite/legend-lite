"""The embedded MySQL repositories: one per platform with a pinned build, lazily fetched, and the hub."""

load(":mysql.bzl", "BUILDS", "EXTRA_LIBS", "embedded_mysql_hub", "embedded_mysql_platform")

def _embedded_mysql_impl(module_ctx):
    for platform, (tarball, sha256) in BUILDS.items():
        extras = EXTRA_LIBS[platform]
        embedded_mysql_platform(
            name = "embedded_mysql_" + platform,
            tarball = tarball,
            platform = platform,
            sha256 = sha256,
            extra_debs = [e[0] for e in extras],
            extra_pools = [e[1] for e in extras],
            extra_sha256s = [e[2] for e in extras],
            extra_sonames = [e[3] for e in extras],
        )
    embedded_mysql_hub(name = "embedded_mysql", platforms = BUILDS.keys())
    return module_ctx.extension_metadata(reproducible = True)

embedded_mysql = module_extension(implementation = _embedded_mysql_impl)
