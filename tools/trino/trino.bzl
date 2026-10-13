"""THE TRINO THE TRINO LANES RUN ON (docs/CLOUD_BACKENDS_PCT_2026_10_11.md §18).

A real Trino server -- Trino's own server tarball, pinned by version and sha256, downloaded from its GitHub release
(Maven Central's trino-server artifact redirects there) and unpacked by Bazel itself: no Docker, no host tool. A test
starts the coordinator from its jars (//testing EmbeddedTrino) on the test's own Java (Trino 483 is built for Java 25,
the toolchain's), one node scheduling itself, with the memory connector as its one catalog.

Only lib/ (the server's jars) and plugin/memory/ (the memory connector) are kept; the launcher binaries and every other
plugin are deleted at fetch (the tarball is 852 MB, what is kept 113 MB). The jars are Java's, so one repository
serves every platform.
"""

VERSION = "483"

SHA256 = "4f3978428f26f36398c94b85a3e03b5301394919c8a4271b497b0fcd1698d0cb"

def _embedded_trino_impl(rctx):
    name = "trino-server-" + VERSION
    rctx.download_and_extract(
        url = ["https://github.com/trinodb/trino/releases/download/{v}/{n}.tar.gz".format(v = VERSION, n = name)],
        sha256 = SHA256,
        output = "trino",
        stripPrefix = name,
    )
    rctx.delete("trino/bin")
    for f in rctx.path("trino/plugin").readdir():
        if f.basename != "memory":
            rctx.delete(f)

    # TRINO_ROOT marks the install's root for the launcher (its lib/ and plugin/ are beside it)
    rctx.file("trino/TRINO_ROOT", "trino " + VERSION + "\n")
    rctx.file("BUILD.bazel", """\
# Trino {v}: the server's jars, the memory connector, and the marker a test finds its root by.
filegroup(
    name = "trino",
    srcs = glob(["trino/lib/**", "trino/plugin/**", "trino/TRINO_ROOT"]),
    visibility = ["//visibility:public"],
)

exports_files(["trino/TRINO_ROOT"])
""".format(v = VERSION))

embedded_trino_repository = repository_rule(
    implementation = _embedded_trino_impl,
    doc = "The pinned Trino server, unpacked and trimmed to its jars and the memory connector.",
)
