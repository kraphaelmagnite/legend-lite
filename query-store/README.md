# query-store

Upstream's saved queries (`/api/pure/v1/query`, legend-engine's `Query` records), for every app
here that saves or opens them: Query writes, DataCube reads.

- `src/wire.ts` -- the `Query` record and the search specification: the contract, legend-engine's.
- `src/client.ts` -- **the one client**, `QueryStoreClient(api, fetch)`, typed, refusals as
  `QueryStoreError(message, status)`. `QueryReader` (search, get) is what a reader depends on.
- `src/wasm-server.ts` -- **the store in the page**: the same API answered from the page's own
  records by legend-lite's own rules (`core/.../queries/SavedQueries.java`, the server's), compiled to
  WebAssembly (`//query-store:page`, from `java/.../QueriesPage.java`; the apps serve it at
  `vendor/queries/`, fetched at the store's first call). Each call hands the module every record and writes
  back what it wrote. Its `fetch` is handed to the client in place of the network:
  `new QueryStoreClient(LOCAL_API, wasmQueryServer({load, records, user}).fetch)`. (Until 2026-10-13 a
  TypeScript port of the rules, `local-server.ts`; one implementation since: the program's B5.)
- `src/records.ts` -- where the page's store keeps them: IndexedDB (`legend-query`, shared by every
  app on the origin), or memory in a test; each query's versions as the JSON text the rules wrote (so a
  number keeps its spelling, as in the server's file).

Where the store is -- legend-engine, legend-lite started with `--query-store DIR`, or this page --
is only which `fetch` the client gets; no app branches on it.

**One suite, every store** (`test/conformance.ts`): raw HTTP calls, the JSON field order, the
statuses and the refusals word for word, and the client over them. `//query-store:wasm_test` runs
it on the page's store, `//query-store:lite_test` on legend-lite's server (started with a fresh
store), and `//datacube:python_saved_queries_test` on Python's (the native library's, over
`ll.home()/queries`). A difference between them fails one of them.

The page's store is a browser's, not a server's: its queries stay in that browser profile, and its
user is the name the page is configured with. Two tabs share it as two clients share a server: each
call is one IndexedDB transaction (the read, the rules, their writes), so no tab writes between
another's read and its writes, and of two saves of one query the later is its current version and
both are in its history, as on the server.
