"""THE MARIADB THE MARIADB LANES RUN ON (docs/CLOUD_BACKENDS_PCT_2026_10_11.md §13).

A real MariaDB server's binaries -- MariaDB's own release tarball ("bintar"), pinned by version and sha256, downloaded
from archive.mariadb.org and unpacked by Bazel itself: no Docker, no host tool, no jar. A test starts the server from
them (//testing EmbeddedMariaDb). MariaDB 11.4, the older of the two long-term releases supported in 2026, so nothing
newer creeps into the dialect.

Only the server, its bootstrap script and its share/ are kept: the tarball's test suite, benchmarks, headers, client
tools and storage-engine plugins (about 1 GB unpacked) are deleted at fetch. MariaDB publishes a bintar for Linux
x86_64 only (its other platforms ship packages), so the hub skips the MariaDB targets as incompatible elsewhere.
"""

VERSION = "11.4.13"

# platform (//tools/platforms) -> (the bintar's platform name, its sha256 from MariaDB's sha256sums.txt)
BUILDS = {
    "linux_x86_64": ("linux-systemd-x86_64", "3cebf63914df3154b7cb6d548337eb2be42c773aeb641110446b72e2746cfed7"),
}

# the server's own files: the server, the bootstrap script and the two tools it calls
KEEP_BIN = ["mariadbd", "my_print_defaults", "resolveip"]

def _embedded_mariadb_platform_impl(rctx):
    name = "mariadb-{v}-{p}".format(v = VERSION, p = rctx.attr.bintar)
    rctx.download_and_extract(
        url = "https://archive.mariadb.org/mariadb-{v}/bintar-{p}/{n}.tar.gz".format(v = VERSION, p = rctx.attr.bintar, n = name),
        sha256 = rctx.attr.sha256,
        output = "mariadb",
        stripPrefix = name,
    )
    for d in ["mariadb-test", "sql-bench", "include", "man", "docs", "lib", "support-files"]:
        rctx.delete("mariadb/" + d)
    for f in rctx.path("mariadb/bin").readdir():
        if f.basename not in KEEP_BIN:
            rctx.delete(f)

    # MARIADB_ROOT marks the install's root for the launcher (its bin/, scripts/, share/ are beside it)
    rctx.file("mariadb/MARIADB_ROOT", "mariadb " + VERSION + " " + rctx.attr.platform + "\n")
    rctx.file("BUILD.bazel", """\
# MariaDB {v} for {p}: the server's install, and the marker a test finds its root by.
filegroup(
    name = "mariadb",
    srcs = glob(["mariadb/**"]),
    visibility = ["//visibility:public"],
)

exports_files(["mariadb/MARIADB_ROOT"])
""".format(v = VERSION, p = rctx.attr.platform))

embedded_mariadb_platform = repository_rule(
    implementation = _embedded_mariadb_platform_impl,
    doc = "One platform's pinned MariaDB server, unpacked and trimmed (the platform is an attribute, never the host's).",
    attrs = {
        "bintar": attr.string(mandatory = True),
        "platform": attr.string(mandatory = True),
        "sha256": attr.string(mandatory = True),
    },
)

def _embedded_mariadb_hub_impl(rctx):
    platforms = sorted(rctx.attr.platforms)
    def select_of(target):
        return "select({\n" + "".join([
            '        "@@//tools/platforms:{p}": "@embedded_mariadb_{p}//:{t}",\n'.format(p = p, t = target)
            for p in platforms
        ]) + '    }, no_match_error = "no embedded MariaDB build is pinned for this platform (tools/mariadb/mariadb.bzl: ' + ", ".join(platforms) + ')")'
    compatible = "select({\n" + "".join(['        "@@//tools/platforms:{p}": [],\n'.format(p = p) for p in platforms]) + '        "//conditions:default": ["@platforms//:incompatible"],\n    })'
    rctx.file("BUILD.bazel", """\
# This platform's MariaDB {v} (tools/mariadb/mariadb.bzl): the install and its root marker, selected through
# //tools/platforms; skipped as incompatible where no build is pinned.
package(default_visibility = ["//visibility:public"])

alias(
    name = "mariadb",
    actual = {files},
    target_compatible_with = {compatible},
)

alias(
    name = "mariadb/MARIADB_ROOT",
    actual = {root},
    target_compatible_with = {compatible},
)
""".format(v = VERSION, files = select_of("mariadb"), root = select_of("mariadb/MARIADB_ROOT"), compatible = compatible))

embedded_mariadb_hub = repository_rule(
    implementation = _embedded_mariadb_hub_impl,
    doc = "@embedded_mariadb: aliases to the platform repositories, by //tools/platforms.",
    attrs = {"platforms": attr.string_list(mandatory = True)},
)
