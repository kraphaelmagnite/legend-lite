"""The embedded Trino repository: one for every platform (the server is Java), lazily fetched."""

load(":trino.bzl", "embedded_trino_repository")

def _embedded_trino_impl(module_ctx):
    embedded_trino_repository(name = "embedded_trino")
    return module_ctx.extension_metadata(reproducible = True)

embedded_trino = module_extension(implementation = _embedded_trino_impl)
