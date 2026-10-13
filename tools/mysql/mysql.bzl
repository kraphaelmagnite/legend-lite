"""THE MYSQL THE MYSQL LANES RUN ON (docs/CLOUD_BACKENDS_PCT_2026_10_11.md §14).

A real MySQL server's binaries -- Oracle's own "minimal" Linux tarball, pinned by version and sha256, downloaded from
cdn.mysql.com and unpacked by Bazel itself: no Docker, no host tool, no jar. A test starts the server from them
(//testing EmbeddedMySql). MySQL 8.4, the long-term release.

Only the server (bin/mysqld), its share/ (error messages, character sets) and its private libraries are kept; the
client tools, headers, MeCab dictionaries and client libraries are deleted at fetch. mysqld links two libraries the
tarball does not carry, libaio.so.1 and libnuma.so.1 (Ubuntu 24.04 has no libaio.so.1: its t64 rename), so the
repository carries Debian bookworm's builds of them in lib/extra, which the launcher puts on LD_LIBRARY_PATH. Oracle
publishes the minimal tarball for Linux x86_64 and aarch64; only x86_64 is pinned, so the hub skips the MySQL targets as
incompatible elsewhere.
"""

VERSION = "8.4.11"

# platform (//tools/platforms) -> (the tarball's platform name, its sha256)
BUILDS = {
    "linux_x86_64": ("linux-glibc2.28-x86_64", "383f54e124d5f325d67f0c6912a8f96814eedc761a17ea30112e52fa4cc6b143"),
}

# Debian bookworm's packages of the two libraries mysqld links that the tarball lacks: (package, pool path, sha256)
EXTRA_LIBS = {
    "linux_x86_64": [
        ("libaio1_0.3.113-4_amd64.deb", "main/liba/libaio", "d1476e4beab3d85f8a7d31de94c27472711ed3ef399ce70708e24b259426125b", "libaio.so.1"),
        ("libnuma1_2.0.16-1_amd64.deb", "main/n/numactl", "639e1ab6bd66ead40db8a22c332d7199679fa22db261cac34444eb8eb4c17dda", "libnuma.so.1"),
    ],
}

def _embedded_mysql_platform_impl(rctx):
    name = "mysql-{v}-{p}-minimal".format(v = VERSION, p = rctx.attr.tarball)
    file = name + ".tar.xz"
    rctx.download_and_extract(
        url = [
            "https://cdn.mysql.com/Downloads/MySQL-8.4/" + file,
            "https://cdn.mysql.com/archives/mysql-8.4/" + file,
        ],
        sha256 = rctx.attr.sha256,
        output = "mysql",
        stripPrefix = name,
    )
    for d in ["docs", "include", "man", "support-files", "lib/mecab", "lib/pkgconfig", "lib/plugin"]:
        rctx.delete("mysql/" + d)
    for f in rctx.path("mysql/lib").readdir():
        if f.basename != "private":
            rctx.delete(f)
    for f in rctx.path("mysql/bin").readdir():
        if f.basename != "mysqld":
            rctx.delete(f)
    for i in range(len(rctx.attr.extra_debs)):
        deb = rctx.attr.extra_debs[i]
        unpacked = "deb_" + str(i)
        rctx.download_and_extract(
            url = [
                "https://deb.debian.org/debian/pool/" + rctx.attr.extra_pools[i] + "/" + deb,
                "https://snapshot.debian.org/archive/debian/20240601T000000Z/pool/" + rctx.attr.extra_pools[i] + "/" + deb,
            ],
            sha256 = rctx.attr.extra_sha256s[i],
            output = unpacked,
            type = "deb",
        )
        rctx.extract(unpacked + "/data.tar.xz", output = unpacked + "/data")
        lib = rctx.path(unpacked + "/data/usr/lib/x86_64-linux-gnu/" + rctx.attr.extra_sonames[i]).realpath
        rctx.symlink(lib, "mysql/lib/extra/" + rctx.attr.extra_sonames[i])

    # MYSQL_ROOT marks the install's root for the launcher (its bin/, share/, lib/ are beside it)
    rctx.file("mysql/MYSQL_ROOT", "mysql " + VERSION + " " + rctx.attr.platform + "\n")
    rctx.file("BUILD.bazel", """\
# MySQL {v} for {p}: the server's install, and the marker a test finds its root by.
filegroup(
    name = "mysql",
    srcs = glob(["mysql/**"]),
    visibility = ["//visibility:public"],
)

exports_files(["mysql/MYSQL_ROOT"])
""".format(v = VERSION, p = rctx.attr.platform))

embedded_mysql_platform = repository_rule(
    implementation = _embedded_mysql_platform_impl,
    doc = "One platform's pinned MySQL server, unpacked and trimmed, with the libraries it links that it lacks.",
    attrs = {
        "tarball": attr.string(mandatory = True),
        "platform": attr.string(mandatory = True),
        "sha256": attr.string(mandatory = True),
        "extra_debs": attr.string_list(mandatory = True),
        "extra_pools": attr.string_list(mandatory = True),
        "extra_sha256s": attr.string_list(mandatory = True),
        "extra_sonames": attr.string_list(mandatory = True),
    },
)

def _embedded_mysql_hub_impl(rctx):
    platforms = sorted(rctx.attr.platforms)
    def select_of(target):
        return "select({\n" + "".join([
            '        "@@//tools/platforms:{p}": "@embedded_mysql_{p}//:{t}",\n'.format(p = p, t = target)
            for p in platforms
        ]) + '    }, no_match_error = "no embedded MySQL build is pinned for this platform (tools/mysql/mysql.bzl: ' + ", ".join(platforms) + ')")'
    compatible = "select({\n" + "".join(['        "@@//tools/platforms:{p}": [],\n'.format(p = p) for p in platforms]) + '        "//conditions:default": ["@platforms//:incompatible"],\n    })'
    rctx.file("BUILD.bazel", """\
# This platform's MySQL {v} (tools/mysql/mysql.bzl): the install and its root marker, selected through
# //tools/platforms; skipped as incompatible where no build is pinned.
package(default_visibility = ["//visibility:public"])

alias(
    name = "mysql",
    actual = {files},
    target_compatible_with = {compatible},
)

alias(
    name = "mysql/MYSQL_ROOT",
    actual = {root},
    target_compatible_with = {compatible},
)
""".format(v = VERSION, files = select_of("mysql"), root = select_of("mysql/MYSQL_ROOT"), compatible = compatible))

embedded_mysql_hub = repository_rule(
    implementation = _embedded_mysql_hub_impl,
    doc = "@embedded_mysql: aliases to the platform repositories, by //tools/platforms.",
    attrs = {"platforms": attr.string_list(mandatory = True)},
)
