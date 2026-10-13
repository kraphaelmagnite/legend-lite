"""Platform policy, in one place (Bazel workplan P1-19): the platforms the native and DuckDB targets support,
and the selects over them.

Each name below is a config_setting in //tools/platforms. platform_select(mapping, what) turns a mapping from
those names to values into a select, which fails with a clear message on a platform it does not list, and
the matching target_compatible_with, so a target using it is SKIPPED on every other platform instead of
failing analysis.
"""

PLATFORMS = [
    "linux_aarch64",
    "linux_x86_64",
    "macos_arm64",
    "macos_x86_64",
    "windows_aarch64",
    "windows_x86_64",
]

def _setting(name):
    if name not in PLATFORMS:
        fail("%s is not a platform of //tools/platforms (%s)" % (name, ", ".join(PLATFORMS)))
    return "//tools/platforms:" + name

def platform_select(mapping, what):
    """A select over the platforms named in `mapping`, and the target_compatible_with that allows exactly those.

    Args:
      mapping: platform name (PLATFORMS) to the value for it.
      what: what is selected, for the error on an unlisted platform.

    Returns:
      (the select, the target_compatible_with select)
    """
    values = select(
        {_setting(name): value for name, value in mapping.items()},
        no_match_error = "no %s for this platform (//tools/platforms: %s)" % (what, ", ".join(sorted(mapping))),
    )
    compatible = {_setting(name): [] for name in mapping}
    compatible["//conditions:default"] = ["@platforms//:incompatible"]
    return values, select(compatible)

def compatible_with(names):
    """The target_compatible_with that allows exactly the named platforms (PLATFORMS) and skips every other."""
    compatible = {_setting(name): [] for name in names}
    compatible["//conditions:default"] = ["@platforms//:incompatible"]
    return select(compatible)

# Where legend-lite's compiler builds as a native library (//native:compiler: GraalVM's native-image with the host's C
# toolchain), and so where everything that loads it runs: //python's tests, and DataCube's test against Python's engine
# with the engine it starts (2026-10-08: one list, its three users name it). Windows on x86-64 as the warehouse's image
# is; not Windows on Arm (no GraalVM there).
COMPILER_LIBRARY_PLATFORMS = ["linux_aarch64", "linux_x86_64", "macos_arm64", "macos_x86_64", "windows_x86_64"]

# DuckDB's spelling of the platforms its extensions are published for (extensions.duckdb.org/v1.5.5/<name>/), by ours:
# the one mapping behind every extension MODULE.bazel pins as duckdb_*_extension_<name> -- the warehouse's postgres
# extension (warehouse/defs.bzl) and Python's httpfs, iceberg and avro (//python:wheel_duckdb). 2026-10-12: the two
# hand-spelled copies made one. MODULE.bazel cannot load this file, so its pin tables spell the same five DuckDB names
# by hand and must keep listing these. Not the JDBC jar's library names (//warehouse: both macOS builds are
# osx_universal there).
DUCKDB_PLATFORMS = {
    "linux_aarch64": "linux_arm64",
    "linux_x86_64": "linux_amd64",
    "macos_arm64": "osx_arm64",
    "macos_x86_64": "osx_amd64",
    "windows_x86_64": "windows_amd64",
}

INCOMPATIBLE_WINDOWS = select({
    "@platforms//os:windows": ["@platforms//:incompatible"],
    "//conditions:default": [],
})
