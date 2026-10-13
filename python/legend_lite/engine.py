"""A small Legend engine over frames: legend-engine's ``pure/v1`` API, answered on this machine, so DataCube's
remote client (``engine-client/src/engine-remote.ts``) runs against a Python process exactly as it runs against
legend-engine. Design: ``docs/DATACUBE_PYTHON_SHOW_DESIGN_2026_10_08.md``.

    frames = Frames()
    frames.register('trades', df)
    engine = Engine(frames)              # the answers: engine.answer(method, path, query, body)
    server = WebServer(engine)           # to a browser tab: HTTP at server.url, in a background thread
    ...
    server.close()

The engine has no transport. A browser tab reaches it over HTTP (``WebServer``); a notebook's cube over the widget's
own channel (``legend_lite.notebook``). Both send it the same calls.

Every answer but execute's rows is legend-lite's own server code (``PureV1Api``, through the native library),
so this module holds no Legend protocol: it runs the SQL the compiler wrote, and carries HTTP. execute
answers in upstream's Arrow format (``?serializationFormat=ARROW_IPC``: a zstd-compressed Arrow IPC stream
whose schema metadata carries the builder, the SQL that ran and the columns), the one format this engine
declares; DuckDB produces Arrow, so JSON would be the extra conversion.

Over HTTP, only what the developer's own page sends is answered: the server listens on 127.0.0.1 alone, every call
for the engine's data carries its one-time token (``Authorization: Bearer <token>``), a request is refused unless its
Host names this machine (DNS rebinding), and no cross-origin header is ever sent. Only queries over the models the
frames' tables were written with are run, and only SQL the compiler wrote.
"""

from __future__ import annotations

import base64
import binascii
import contextlib
import functools
import hmac
import http.server
import json
import secrets
import socket
import socketserver
import sys
import threading
import traceback
import queue
from concurrent.futures import Future
from collections.abc import Callable, Mapping
from types import MappingProxyType
from typing import Any, NamedTuple, Protocol
from pathlib import Path
from urllib.parse import parse_qs, unquote, urlsplit

import duckdb
import pyarrow as pa
import pyarrow.ipc

from . import _json, _planner, compiler
from ._library import library
from .frames import Frames, UntypedColumn
from ._home import SavedPages

_API = '/api/pure/v1/'
_EXECUTE = _API + 'execution/execute'
# a request body larger than this is refused: a query and its model are kilobytes, and a page's file comes a part at a
# time (file-part.json: 4 MiB each, as base64 in JSON, datacube/src/upload.ts PART_BYTES), so no file is too big
_MAX_BODY = 16 * 1024 * 1024
# the calls that bring a page's data in and let it go (each changes the engine's data), and what Python's host offers
# the page (docs/DATACUBE_ONE_APP_DESIGN_2026_10_11.md §3.4, §4.3): each the engine's own page's, so each carries the token
_FILE_IN, _FILE_OUT, _REMOTE, _SERVICES = '/file.json', '/forget.json', '/remote.json', '/services.json'
# a page's file's bytes, a part at a time, before file.json reads them
_FILE_PART = '/file-part.json'
# the page's saved pages, as the browser keeps its records (step 2d): all, one, put, remove
_SAVED = '/saved-pages.json'
# PLANANDRUN (docs/DATACUBE_ONE_APP_DESIGN_2026_10_11.md §15): a page's grid planned against the model Python holds and
# run on its DuckDB, in one call; and the planner protocol's other requests, answered as the tab's worker answers them
_PLAN_AND_RUN, _PLANNER = '/plan-and-run.json', '/planner.json'
# the planner's answer to the plan a page's rows are of, in their Arrow schema's metadata
_PLAN_KEY = b'legend.plan'
# the only places a page's remote file may be read from (remote.json), each as DuckDB's httpfs names it: never this
# machine's own files, which Python's DuckDB can read and a page must not make it (R4's reason). Azure's schemes are not
# here: the wheel carries no azure extension. Nor does it carry iceberg: an Iceberg table's metadata names its data
# files by path, so carrying it must come with a rule that those paths are remote too, or a remote file could make
# DuckDB read this machine's (R4)
_REMOTE_SCHEMES = ('http', 'https', 's3', 's3a', 's3n', 'gs', 'gcs', 'r2', 'hf')
# a connection that sends nothing, or stops sending, is closed after this long (seconds)
_IDLE = 30
# the threads that call the compiler: ONE fixed pool for every engine in the process, as the native library asks of
# a host (each thread that calls it stays attached to its isolate), so opening engine after engine in a notebook
# attaches no new threads. A connection is read and written on a thread of its own, which never calls the compiler:
# an idle connection holds no compiler thread
_WORKERS = 4


class _Pool:
    """The compiler's threads: daemon threads taking calls from one queue. Not concurrent.futures' pool: Python shuts
    that down before its atexit handlers run, and a plain script's end waits in one (show(): the cubes stay open until
    Ctrl-C), so every call the engine made there would be refused (the audit of show(), 2026-10-08)."""

    def __init__(self, size: int) -> None:
        self._calls: queue.SimpleQueue[tuple[Future[Any], Any, tuple[Any, ...]]] = queue.SimpleQueue()
        for i in range(size):
            threading.Thread(target=self._serve, name=f'legend-lite-compiler-{i}', daemon=True).start()

    def submit(self, call: Any, *args: Any) -> Future[Any]:
        future: Future[Any] = Future()
        self._calls.put((future, call, args))
        return future

    def _serve(self) -> None:
        while True:
            future, call, args = self._calls.get()
            if not future.set_running_or_notify_cancel():
                continue
            try:
                future.set_result(call(*args))
            except BaseException as failure:  # noqa: BLE001 -- handed to the caller, which answers it
                future.set_exception(failure)


_pool: _Pool | None = None
_pool_lock = threading.Lock()


def _workers() -> _Pool:
    global _pool
    with _pool_lock:
        if _pool is None:
            _pool = _Pool(_WORKERS)
        return _pool


# the site's media types (the warehouse's, WarehouseServer.SITE_TYPES)
_SITE_TYPES = {
    '.html': 'text/html; charset=utf-8',
    '.js': 'text/javascript; charset=utf-8',
    '.mjs': 'text/javascript; charset=utf-8',
    '.css': 'text/css; charset=utf-8',
    '.json': 'application/json',
    '.wasm': 'application/wasm',
    '.pure': 'text/plain; charset=utf-8',
    '.svg': 'image/svg+xml',
    '.woff2': 'font/woff2',
}

_NO_HEADERS: Mapping[str, str] = MappingProxyType({})


class Answer(NamedTuple):
    """An answer to one call, as HTTP would carry it: its status, its media type, its body and any other headers."""

    status: int
    content_type: str
    body: bytes
    headers: Mapping[str, str] = _NO_HEADERS


def _said(status: int, message: str) -> Answer:
    return Answer(status, 'text/plain; charset=utf-8', message.encode('utf-8'))


def _planned(said: dict[str, str], status: int = 200) -> Answer:
    """A plan-and-run that answered no rows: the planner's own answer, and why its SQL was refused when it was."""
    return Answer(status, 'application/json', json.dumps(said).encode('utf-8'))


def _answered(work: Callable[[str], Answer], body: str) -> Answer:
    """A page's planner call answered, never a dropped call: what the call itself does not answer is a bug, logged whole
    and said (500), as the API's are (``Engine._api``)."""
    try:
        return work(body)
    except Exception as failure:
        traceback.print_exc()
        return _said(500, f'{type(failure).__name__}: {failure}')


def duckdb_extensions() -> Path | None:
    """DuckDB's extensions as legend-lite carries them, laid out as DuckDB's own extension folder
    (``v1.5.5/<platform>/httpfs.duckdb_extension``): LEGEND_LITE_DUCKDB_EXTENSIONS, else the folder inside this package;
    None when it holds no httpfs for this DuckDB (a build without it: no remote file is read)."""
    folder = _extensions_folder()
    return folder if (folder / f'v{duckdb.__version__}' / _duckdb_platform() / 'httpfs.duckdb_extension').is_file() else None


def _extensions_folder() -> Path:
    import os

    explicit = os.environ.get('LEGEND_LITE_DUCKDB_EXTENSIONS')
    return Path(explicit) if explicit else Path(__file__).with_name('_duckdb')


def remote_url(url: str) -> bool:
    """Whether a page's URL is a remote one this engine reads: exactly ``<scheme>://<host>...`` for one of the schemes,
    as written -- DuckDB matches its remote file systems by that very prefix, case and all, and reads anything else
    from this machine, so nothing is normalised first (no case folded; no space or control character anywhere)."""
    if any(ord(c) <= 0x20 or ord(c) == 0x7F for c in url):
        return False
    scheme = next((s for s in _REMOTE_SCHEMES if url.startswith(f'{s}://')), None)
    return scheme is not None and urlsplit(url).netloc != ''


@functools.cache
def _duckdb_platform() -> str:
    """The platform DuckDB names its extensions by here (``osx_arm64``, ``linux_amd64``, ``windows_amd64``, ...)."""
    return duckdb.connect().execute('PRAGMA platform').fetchone()[0]


def private(path: str) -> bool:
    """Whether a call reads the engine's data -- the API, a frame's cube, its version -- and so, over HTTP, carries the
    token. The site's files are the page's own and carry no secret."""
    return path.startswith(_API) or path in ('/cube.json', '/version.json', '/page.json', _FILE_IN, _FILE_PART, _FILE_OUT, _REMOTE,
                                             _SAVED, _SERVICES, _PLAN_AND_RUN, _PLANNER)


def _arrow_requested(query: str) -> bool:
    return any(pair == 'serializationFormat=ARROW_IPC' for pair in query.split('&'))


def _arrow_ipc(rows: pa.Table, metadata: dict[str, str]) -> bytes:
    """Rows as upstream's ARROW_IPC answer: an Arrow IPC stream, its schema carrying Legend's metadata, the whole
    stream compressed as one zstd frame."""
    declared = json.loads(metadata['legend.columns'])
    if rows.column_names != declared:
        raise RuntimeError(f'the database answered columns {rows.column_names}, the plan declared {declared}')
    rows = rows.replace_schema_metadata(metadata)
    sink = pa.BufferOutputStream()
    with pa.CompressedOutputStream(sink, 'zstd') as compressed:
        with pyarrow.ipc.new_stream(compressed, rows.schema) as writer:
            writer.write_table(rows)
    return sink.getvalue().to_pybytes()


class Planner(Protocol):
    """WHERE THE ENGINE PLANS (docs/DATACUBE_ONE_APP_DESIGN_2026_10_11.md §4.1, R3): every call the PAGE makes that the
    compiler answers -- the engine's pure/v1 API: a query's types, a parse, a print, and an execute's plan, whose SQL
    Python then runs on its own DuckDB (R4) -- is answered by the one planner the engine was made with, chosen once and
    shown on the page. Its ``plane`` is the word the page's status bar says (``local``, ``remote``, ``engine``) and its
    ``label`` the menu's. Today the one planner is Python's own (``NativePlanner``); one on a server (legend-lite's,
    legend-engine's: step 2c) is another of these. Python's own calls in this process -- a headless grid's view
    (legend_lite._headless), ``Frames.plan`` -- still plan with the native library: routing them through the same
    planner is 2c's too."""

    plane: str
    label: str

    def answer(self, path: str, query: str, body: str) -> compiler.Answer:
        """A ``pure/v1`` call other than execute, answered as upstream answers it."""
        ...

    def execute_plan(self, body: str, models: list[str]) -> compiler.Answer:
        """An ``ExecuteInput`` over one of ``models`` (the only models this host runs) planned: ``{"sql", "metadata"}``,
        or a refusal to send as it is."""
        ...

    def refusal(self, message: str) -> compiler.Answer:
        """A failure said in this planner's own answer shape (legend-engine's)."""
        ...


class NativePlanner:
    """The ``local`` plane on a Python host: legend-lite's compiler in this process, the native library."""

    plane = 'local'
    label = 'Plan local (in Python: the native library)'

    def answer(self, path: str, query: str, body: str) -> compiler.Answer:
        return compiler.pure_v1(path, query, body)

    def execute_plan(self, body: str, models: list[str]) -> compiler.Answer:
        return compiler.execute_plan(body, models)

    def refusal(self, message: str) -> compiler.Answer:
        return compiler.refusal(message)


class Engine:
    """legend-engine's ``pure/v1`` API over the frames' tables, with no transport: ``answer(method, path, query,
    body)`` is one call, answered as HTTP would carry it. ``site``: a directory whose files are answered too
    (DataCube's built pages). ``WebServer`` serves it to a browser tab; a notebook's cube sends it its widget's
    messages."""

    def __init__(self, frames: Frames, site: str | Path | None = None,
                 headless: Callable[[], Any] | None = None, planner: Planner | None = None,
                 pages: SavedPages | None = None) -> None:
        """``headless``: DataCube's own non-screen code over the frames' database (legend_lite._headless), made when
        first asked for -- what reads a page's file into it (file.json). Absent, the engine takes no file, and says so
        (services.json). ``planner``: where every query is planned, chosen once (R3): Python's own when not given.
        ``pages``: where the page's Save and Open… keep pages (step 2d): this user's folder (``home()``) when not given."""
        self.frames = frames
        self._headless = headless
        # where the frames' DuckDB was pointed for its extensions (a remote file's httpfs), once it was
        self._extensions: Path | None = None
        # the page files coming in a part at a time (file-part.json), each by the name Python gave it: where its bytes
        # are written so far; one part is written at a time
        self._uploads: dict[str, Path] = {}
        self._uploads_lock = threading.Lock()
        self.planner = planner if planner is not None else NativePlanner()
        self._saved_pages = pages
        self.site = Path(site).resolve() if site is not None else None
        if self.site is not None and not self.site.is_dir():
            raise ValueError(f'the site {site} is not a directory')
        # each frame's version: bumped when it changes (changed), so a page asking sees it and reads it again; and the
        # model each was last served with, so a Live frame whose columns changed is noticed (_noted)
        self._versions: dict[str, int] = {}
        self._models: dict[str, str] = {}
        self._watchers: list[Callable[[str, int], None]] = []
        self._changes = threading.Lock()
        # the pages it serves (Python's ll.Page; docs/DATACUBE_PYTHON_PAGES_DESIGN_2026_10_09.md): each its document --
        # DataCube's page document, its cubes over these frames -- and its version, moved each time it is served again
        self._pages: dict[str, tuple[int, dict[str, Any]]] = {}
        # each page's last version, kept when it closes: a page shown again goes on from it, so what a tab still open
        # on its earlier showing says is of a version replaced
        self._page_versions: dict[str, int] = {}
        # each page's document as the open page says it is now (its changes made in DataCube included: page.read()).
        # Documents are kept as the compiler's exact JSON reads them (_json): a calculated column's numbers exact
        self._read: dict[str, dict[str, Any]] = {}

    def answer(self, method: str, path: str, query: str, body: str | None) -> Answer:
        """One call: ``POST`` to the API, or ``GET`` of a frame's cube (``cube.json``), its version (``version.json``) or
        a file of the site. It waits for its answer, which the compiler's threads compute when it needs the compiler,
        so it is called from a thread of the caller's own (a connection's, a message's), never a compiler thread."""
        if method == 'POST':
            if path == '/page.json':
                return self._page_said(query, body or '')
            if path == _FILE_PART:
                return self._file_part(body or '')
            if path == _FILE_IN:
                return self._file_in(body or '')
            if path == _FILE_OUT:
                return self._file_out(body or '')
            if path == _REMOTE:
                return self._remote(body or '')
            if path == _SAVED:
                return self._saved(body or '')
            if path == _PLAN_AND_RUN:
                return _workers().submit(_answered, self._plan_and_run, body or '').result()
            if path == _PLANNER:
                return _workers().submit(_answered, self._planner, body or '').result()
            if not path.startswith(_API):
                return _said(404, f'no such path: {path}')
            return _workers().submit(self._api, path, query, body or '').result()
        if method != 'GET':
            return _said(405, 'this engine answers GET and POST')
        if path == '/cube.json':
            return _workers().submit(self._cube, query).result()
        if path == '/version.json':
            # no compiler call: on the caller's thread
            return self._version(query)
        if path == '/page.json':
            return self._page(query)
        if path == _SERVICES:
            return Answer(200, 'application/json', json.dumps(self.services()).encode('utf-8'))
        return self._file(path)

    def services(self) -> dict[str, Any]:
        """What this engine offers the page beside its frames (services.json, asked once as the page starts): whether
        it reads a page's file, and where it plans -- the plane it plans on, and every plane it offers (today only its
        own: choosing another is step 2c's)."""
        plane = {'word': self.planner.plane, 'label': self.planner.label}
        # where the page's saved pages are, and whose: the folder's one owner, this user (as the browser's store is
        # 'this browser''s)
        pages = {'where': str(self.saved_pages().folder), 'user': 'this user'}
        return {'files': self.takes_files(), 'remote': self.takes_remote(), 'pages': pages, 'plane': plane,
                'planes': [plane]}

    def saved_pages(self) -> SavedPages:
        """Where the page's saved pages are kept: the folder given, else this user's (``home()``), as it is now."""
        return self._saved_pages if self._saved_pages is not None else SavedPages()

    def _saved(self, body: str) -> Answer:
        """The page's saved pages, as the browser keeps its records (``{"call": "all" | "find" | "put" | "remove", "id"?,
        "record"?}``; step 2d): the records, kept whole, one file each in this user's folder. The store's rules are the
        page's own (cube-store.ts's RuleStore), as they are over the browser's IndexedDB."""
        try:
            asked = json.loads(body)
        except ValueError:
            return _said(400, 'a call on the saved pages is {"call", "id"?, "record"?}, as JSON')
        if not isinstance(asked, dict):
            return _said(400, 'a call on the saved pages is {"call", "id"?, "record"?}')
        store, call = self.saved_pages(), asked.get('call')
        try:
            if call == 'all':
                return Answer(200, 'application/json', json.dumps(store.all(), ensure_ascii=False).encode('utf-8'))
            if call == 'find':
                # none found is an answer, not a refusal: the store asks before it saves a new page that its id is free
                return Answer(200, 'application/json', json.dumps(store.find(asked.get('id')), ensure_ascii=False).encode('utf-8'))
            if call == 'put':
                record = asked.get('record')
                if not isinstance(record, dict):
                    return _said(400, 'a saved page is put as its record')
                store.put(record)
                return Answer(204, 'text/plain; charset=utf-8', b'')
            if call == 'remove':
                store.remove(asked.get('id'))
                return Answer(204, 'text/plain; charset=utf-8', b'')
        except ValueError as refused:
            return _said(400, str(refused))
        except OSError as failure:
            return _said(500, f'the saved pages could not be read or written in {store.folder}: {failure}')
        return _said(400, f'a call on the saved pages is all, find, put or remove, not {call!r}')

    def takes_files(self) -> bool:
        """Whether a page's file can be read into the frames' database here: DataCube's own code runs headless (its
        ``datacube`` extra installed), so the page offers its files and examples."""
        if self._headless is None:
            return False
        try:
            from ._headless import available
        except ImportError:
            # legend-lite without its datacube extra's module (a build of the compiler alone)
            return False
        return available()

    def takes_remote(self) -> bool:
        """Whether a page's remote file can be read here (``remote_refusal`` says why not)."""
        return self.remote_refusal() is None

    def remote_refusal(self) -> str | None:
        """Why a page's remote file cannot be read here, or None: it needs DataCube's own code (headless), DuckDB's
        httpfs extension for this very DuckDB where the package carries it (nothing is downloaded), and a database
        legend-lite made -- its extension settings are changed for good, which a database of the person's own is not."""
        if not self.takes_files():
            return ('legend-lite reads one with DataCube\'s own code, which its datacube extra installs '
                    '(pip install "legend-lite[datacube]")')
        if not self.frames.owns_connection:
            return 'it is read on a DuckDB legend-lite made, and these frames are on a database of your own'
        if duckdb_extensions() is None:
            folder = _extensions_folder()
            carried = sorted(p.name[1:] for p in folder.glob('v*')) if folder.is_dir() else []
            return (f'legend-lite carries DuckDB\'s httpfs for duckdb {", ".join(carried)}, and this Python has duckdb '
                    f'{duckdb.__version__}' if carried else 'this legend-lite carries no httpfs extension for DuckDB')
        return None

    def _remote(self, body: str) -> Answer:
        """A page's remote file read through a view of its own in the frames' database (``{"url", "name", "s3"?}``;
        docs/DATACUBE_ONE_APP_DESIGN_2026_10_11.md, step 2b): its URL one of the remote schemes, never this machine's
        files, checked before anything runs; read by DataCube's own code (upload.ts's mountRemoteView, the tab's, run
        headless) with the httpfs extension the package carries. The view's model is served beside the frames' from
        then on, and the answer is its model, as the tab's mount says it. The page sends no SQL."""
        try:
            asked = json.loads(body)
        except ValueError:
            return _said(400, 'a remote file is {"url", "name", "s3"?}, as JSON')
        if not (isinstance(asked, dict) and isinstance(asked.get('url'), str) and isinstance(asked.get('name'), str)
                and (asked.get('s3') is None or isinstance(asked['s3'], dict))):
            return _said(400, 'a remote file is {"url", "name", "s3"?}: a URL, a name, and its keys when it needs them')
        url = asked['url']
        if not remote_url(url):
            return _said(400, f'a remote file is read from {", ".join(s + "://" for s in _REMOTE_SCHEMES)}, never from '
                              f'this machine: {url!r} is not such a URL (a file of yours: choose it under Files)')
        refused = self.remote_refusal()
        if refused is not None:
            return _said(404, f'this engine reads no remote file: {refused}')
        extensions = duckdb_extensions()
        assert extensions is not None
        assert self._headless is not None
        try:
            # asked for before the frames are held (the session's lock first, then the frames')
            headless = self._headless()
            with self.frames.reading_file(asked['name'], view=True) as name:
                self._extensions_here(extensions)
                mount = {'url': url, 'name': name, **({'s3': asked['s3']} if asked.get('s3') else {})}
                said = headless.call('mount', json.dumps(mount))
                self.frames.keep_file(name, json.loads(said)['model'], view=True)
        except ValueError as refused:
            return _said(400, str(refused))
        except Exception as failure:
            # the file not read (not there, its keys refused, a format DuckDB cannot read): said, as the tab says it
            return _said(422, f'{type(failure).__name__}: {failure}')
        return Answer(200, 'application/json', said.encode('utf-8'))

    def _extensions_here(self, folder: Path) -> None:
        """The frames' DuckDB pointed at the extensions the package carries, once (the frames held): DataCube's INSTALL
        finds httpfs installed there and LOAD loads it, and an extension not there is never downloaded -- the
        repository it would be fetched from is the same folder, and installing on its own is off."""
        if self._extensions is not None:
            return
        folder_text = str(folder).replace("'", "''")
        for statement in (f"SET extension_directory = '{folder_text}'",
                          f"SET custom_extension_repository = '{folder_text}'",
                          'SET autoinstall_known_extensions = false'):
            self.frames.connection.execute(statement)
        self._extensions = folder

    def _file_part(self, body: str) -> Answer:
        """A part of a page's file, in order (``{"upload"?, "at", "bytes"}``, its bytes base64): the bytes the tab's DuckDB
        would be given (upload.ts ``fileParts``), sent as the page reads them, so no file is too big to send. The first
        part, with no ``upload``, begins one, named by Python (``{"upload"}`` answers it); each next names it and starts
        where the bytes so far end (``at``), so a part sent twice or out of order is refused, never written twice. The
        bytes are written where Python's DuckDB reads them (the headless engine's folder, under a name of Python's, gone
        with that folder if no file.json ever reads them); file.json reads them as one file."""
        try:
            asked = json.loads(body)
        except ValueError:
            return _said(400, 'a part of a file is {"upload"?, "at", "bytes"}, as JSON')
        upload, at = (asked.get('upload'), asked.get('at')) if isinstance(asked, dict) else (None, None)
        if not (isinstance(asked, dict) and isinstance(asked.get('bytes'), str) and (upload is None or isinstance(upload, str))
                and isinstance(at, int) and not isinstance(at, bool) and at >= 0):
            return _said(400, 'a part of a file is {"upload"?, "at", "bytes"}: its upload a string, at a count of bytes, '
                              'its bytes base64')
        if not self.takes_files():
            return _said(404, 'this engine reads no file: legend-lite reads one with DataCube\'s own code, which its '
                              'datacube extra installs (pip install "legend-lite[datacube]")')
        assert self._headless is not None
        try:
            data = base64.b64decode(asked['bytes'], validate=True)
        except binascii.Error:
            return _said(400, "a file's bytes are base64")
        folder = self._headless().folder
        with self._uploads_lock:
            if upload is None:
                if at != 0:
                    return _said(400, 'an upload begins at its first byte (at 0)')
                upload = secrets.token_hex(16)
                path = folder / f'page_upload_{upload}'
                path.write_bytes(b'')
                self._uploads[upload] = path
            else:
                path = self._uploads.get(upload)
                if path is None:
                    return _said(404, f'no file is coming in as {upload!r}: begin it with its first part')
            size = path.stat().st_size
            if at != size:
                return _said(409, f'the part starts at byte {at}, and the file so far ends at byte {size}')
            with path.open('ab') as file:
                file.write(data)
        return Answer(200, 'application/json', json.dumps({'upload': upload, 'size': size + len(data)}).encode('utf-8'))

    def _file_in(self, body: str) -> Answer:
        """A page's file read into a table of its own in the frames' database (docs/DATACUBE_ONE_APP_DESIGN_2026_10_11.md
        §4.3): ``{"upload", "name", "table"}``, its bytes the parts that upload brought (file-part.json), written where
        Python's DuckDB reads them. DataCube's own code reads that file into the table (upload.ts's readFileInto, run
        headless): only the path crosses into the engine, and Python and the tab read a file the same way. The table is
        a copy, so the file goes at once, read or not. Its model is served beside the frames' from then on (the only
        models a query may be over), and the answer is what arrived, as upload.ts says it. The page sends no SQL."""
        try:
            asked = json.loads(body)
        except ValueError:
            return _said(400, 'a file is {"upload", "name", "table"}, as JSON')
        if not (isinstance(asked, dict) and all(isinstance(asked.get(k), str) for k in ('upload', 'name', 'table'))):
            return _said(400, 'a file is {"upload", "name", "table"}, each a string')
        if not self.takes_files():
            return _said(404, 'this engine reads no file: legend-lite reads one with DataCube\'s own code, which its '
                              'datacube extra installs (pip install "legend-lite[datacube]")')
        assert self._headless is not None
        with self._uploads_lock:
            path = self._uploads.pop(asked['upload'], None)
        if path is None:
            return _said(404, f'no file came in as {asked["upload"]!r}: send its parts first (file-part.json)')
        try:
            # asked for before the frames are held: the session takes its own lock first, then the frames' (register)
            headless = self._headless()
            # the table the page asked for when it is free, else the next free name: the answer says which
            with self.frames.reading_file(asked['table']) as table:
                said = headless.call('ingest', json.dumps({'path': str(path), 'name': asked['name'], 'table': table}))
                read = json.loads(said)
                self.frames.keep_file(table, read['model'])
        except ValueError as refused:
            return _said(400, str(refused))
        except Exception as failure:
            # the file not read (DuckDB could not, a column no model holds): said, as the tab says it
            return _said(422, f'{type(failure).__name__}: {failure}')
        finally:
            # the table is a copy: the file is not needed. One Windows still holds open is left for the headless
            # engine's folder, which goes with it -- never a read that worked turned into a refusal
            with contextlib.suppress(OSError):
                path.unlink(missing_ok=True)
        return Answer(200, 'application/json', said.encode('utf-8'))

    def _file_out(self, body: str) -> Answer:
        """A page's file let go of (``{"name", "table"}``): its table dropped by DataCube's own code, its model served no
        more. A table that is no file's is refused: a page drops nothing else."""
        try:
            asked = json.loads(body)
        except ValueError:
            return _said(400, 'a file let go of is {"name", "table"}, as JSON')
        if not (isinstance(asked, dict) and all(isinstance(asked.get(k), str) for k in ('name', 'table'))):
            return _said(400, 'a file let go of is {"name", "table"}, each a string')
        table = self.frames.file_table(asked['table'])
        if table is None or not self.takes_files():
            return _said(404, f'{asked["table"]!r} is no table a page\'s file was read into')
        assert self._headless is not None
        try:
            # asked for before the frames are held (the session's lock first, then the frames')
            headless = self._headless()
            with self.frames.holding():
                # dropped first, by the name Frames gave it, and only then served no more: a drop that fails leaves the
                # table served, and the page may let go of it again
                headless.call('forget', json.dumps({'table': table}))
                self.frames.forget_file(table)
        except Exception as failure:
            return _said(422, f'{type(failure).__name__}: {failure}')
        return Answer(204, 'text/plain; charset=utf-8', b'')

    def _api(self, path: str, query: str, body: str) -> Answer:
        """An API call's answer, computed on the compiler's pool."""
        try:
            if path == _EXECUTE and _arrow_requested(query):
                return self._execute(body)
            answer = self.planner.answer(path, query, body)
        except Exception as failure:
            # never a dropped call: the database refusing is an answer; anything else is a bug, logged whole, and
            # answered the same way (as legend-lite's server answers both)
            if not isinstance(failure, duckdb.Error):
                traceback.print_exc()
            message = f'{type(failure).__name__}: {failure}'
            try:
                answer = self.planner.refusal(message)
            except Exception:
                # the compiler itself failing: still an answer, its own words
                traceback.print_exc()
                return _said(500, message)
        return Answer(answer.status, answer.content_type, answer.body.encode('utf-8'))

    def _plan_and_run(self, body: str) -> Answer:
        """PLANANDRUN on Python (§15.2): ``{"lambda", "model", "runtime"}`` -- the plan request the page's planner makes
        (lite-planner.ts ``planRequest``), its model as text -- planned against the model Python holds of that text
        (``Frames.held_for``: only the models this engine serves, R4) by the planner function the tab's worker calls
        (``planJson``), its SQL run on the frames' DuckDB, all in this one call: the page sends Pure, never SQL. The
        answer is the database's rows as Arrow, the planner's answer in their schema's metadata (``legend.plan``),
        which the page reads as its planner reads its own; or, no rows, JSON: the planner's refusal (``plan``), or its
        plan and why its SQL was refused (``error``)."""
        try:
            asked = json.loads(body)
        except ValueError:
            return _said(400, 'a plan-and-run is {"lambda", "model", "runtime"}, as JSON')
        if not (isinstance(asked, dict) and all(isinstance(asked.get(k), str) for k in ('lambda', 'model', 'runtime'))):
            return _said(400, 'a plan-and-run is {"lambda", "model", "runtime"}, each a string')
        with self.frames.serving() as models:
            self._noted(models)
            held = self.frames.held_for(asked['model'])
            if held is None:
                return _planned({'plan': 'ERR\nMODEL\nthis engine serves no source of that model: a page plans only over '
                                           'the frames and files Python holds'})
            planned = library().call('lite_plan_json_held', held.handle, asked['lambda'], asked['runtime'])
            if not planned.startswith('OK\n'):
                return _planned({'plan': planned})
            try:
                # Python's executor, as Python's own queries are run: the rows typed by the plan (frames.typed_by_plan)
                rows = self.frames.run(compiler.read_plan(planned[3:]))
            except (duckdb.Error, UntypedColumn) as refused:
                return _planned({'plan': planned, 'error': f'{type(refused).__name__}: {refused}'}, 422)
        rows = rows.replace_schema_metadata({**(rows.schema.metadata or {}), _PLAN_KEY: planned.encode('utf-8')})
        sink = pa.BufferOutputStream()
        with pyarrow.ipc.new_stream(sink, rows.schema) as writer:
            writer.write_table(rows)
        return Answer(200, 'application/vnd.apache.arrow.stream', sink.getvalue().to_pybytes())

    def _planner(self, body: str) -> Answer:
        """The planner protocol's requests (datacube/src/planner-protocol.ts), answered by the native library as the
        tab's worker answers them (legend_lite._planner): a held kind names its model by its text, never a handle (no
        handle crosses hosts, §15.3), and is answered against the model Python holds of that text. ``hold`` and
        ``release`` are the page's own; none of these runs SQL."""
        try:
            asked = json.loads(body)
        except ValueError:
            return _said(400, 'a planner request is its JSON')
        kind = asked.get('kind') if isinstance(asked, dict) else None
        if not isinstance(kind, str) or kind not in _planner.KINDS or kind in ('hold', 'release'):
            return _said(400, f'this engine answers the planner requests {sorted(set(_planner.KINDS) - {"hold", "release"})}')
        if kind in _planner.HELD:
            model = asked.pop('model', None)
            with self.frames.serving() as models:
                self._noted(models)
                held = self.frames.held_for(model) if isinstance(model, str) else None
                if held is None:
                    said = {'ok': True, 'answer': 'ERR\nMODEL\nthis engine serves no source of that model'}
                else:
                    said = _planner.answer(library(), {**asked, 'handle': held.handle})
        else:
            said = _planner.answer(library(), asked)
        said.pop('id', None)
        return Answer(200, 'application/json', json.dumps(said).encode('utf-8'))

    def _execute(self, body: str) -> Answer:
        """execute in upstream's Arrow format: the compiler plans over the frames' models, DuckDB runs its SQL,
        and the rows go back as upstream writes them. One query at a time (the frames are held still while it
        runs); the answer is compressed outside."""
        with self.frames.serving() as models:
            self._noted(models)
            answer = self.planner.execute_plan(body, list(models.values()))
            if answer.status != 200:
                return Answer(answer.status, answer.content_type, answer.body.encode('utf-8'))
            planned = json.loads(answer.body)
            rows = self.frames.connection.execute(planned['sql']).to_arrow_table()
        # upstream's headers for this answer (measured, legend-engine 4.145.0)
        return Answer(200, 'application/json', _arrow_ipc(rows, planned['metadata']),
                      {'x-legend-response-format': 'FormatNotSet'})

    def _cube(self, query: str) -> Answer:
        """The cube a page shows (DataCube's demo/engine.html, or a notebook's widget): ``table`` (a frame's name), its
        model, runtime and source as they are now (a Live frame read again), and its title. Computed on the compiler's
        pool."""
        name = parse_qs(query).get('table', [''])[0]
        try:
            with self.frames.serving() as models:
                self._noted(models)
                if name not in self.frames:
                    return _said(404, f'this engine serves no frame named {name!r}')
                table = self.frames[name]
                cube = {'title': table.name, 'model': table.model, 'runtime': table.runtime, 'source': table.source,
                        'version': self.version(name)}
            body = json.dumps(cube).encode('utf-8')
        except Exception as failure:
            # a frame that could not be read: said, never a dropped call
            traceback.print_exc()
            return _said(500, f'{type(failure).__name__}: {failure}')
        return Answer(200, 'application/json', body)

    def _version(self, query: str) -> Answer:
        """``table``'s version (``changed``): ``{"version": n}`` at once, or 404 when the engine serves no such frame
        (it was closed). A tab asks about once a second; nothing is held open while it waits, so cubes in many tabs
        never use up the browser's six connections to one origin. Asked with ``page``, a page's: ``{"version": n,
        "frames": {name: n}}``, the page's own and each of its frames' (``page_versions``)."""
        asked = parse_qs(query)
        if 'page' in asked:
            versions = self.page_versions(asked['page'][0])
            if versions is None:
                return _said(404, f'this engine serves no page named {asked["page"][0]!r}')
            return Answer(200, 'application/json', json.dumps(versions).encode('utf-8'))
        name = asked.get('table', [''])[0]
        if name not in self.frames:
            return _said(404, f'this engine serves no frame named {name!r}')
        return Answer(200, 'application/json', json.dumps({'version': self.version(name)}).encode('utf-8'))

    def _page(self, query: str) -> Answer:
        """A page it serves (``serve_page``) as it is open now (the open page's last word on it, at its version):
        ``{"version": n, "page": <its document>}``, or 404."""
        name = parse_qs(query).get('page', [''])[0]
        with self._changes:
            served = self._pages.get(name)
        if served is None:
            return _said(404, f'this engine serves no page named {name!r}')
        return Answer(200, 'application/json', _json.dumps({'version': served[0], 'page': served[1]}).encode('utf-8'))

    def _page_said(self, query: str, body: str) -> Answer:
        """The open page's document as it is now (posted by the page as it changes, with the version it opened):
        kept for ``read_page`` -- unless the page has been served again since, when what it says is of a document
        Python has replaced (409), and the page opens the new one."""
        asked = parse_qs(query)
        name = asked.get('page', [''])[0]
        version = asked.get('version', [''])[0]
        if not version.isdigit():
            return _said(400, 'a page says what it is with the version it opened (version=n)')
        try:
            document = _json.loads(body)
        except ValueError:
            return _said(400, 'a page is its document, as JSON')
        if not isinstance(document, dict) or document.get('kind') != 'datacube.page':
            return _said(400, 'a page is its document (kind datacube.page)')
        with self._changes:
            served = self._pages.get(name)
            if served is None:
                return _said(404, f'this engine serves no page named {name!r}')
            if served[0] != int(version):
                return _said(409, f'the page {name!r} is at version {served[0]}: what version {version} says is replaced')
            self._read[name] = document
            # and it is the page as it is open now: what a page opening on it (another tab, a reload) opens
            self._pages[name] = (served[0], document)
        return Answer(204, 'text/plain; charset=utf-8', b'')

    def read_page(self, name: str) -> dict[str, Any] | None:
        """A page's document as the open page last said it is (its changes made in DataCube included), a copy -- or
        None when it has said nothing since it was served."""
        with self._changes:
            said = self._read.get(name)
        return None if said is None else _json.loads(_json.dumps(said))

    def serve_page(self, name: str, document: dict[str, Any]) -> int:
        """Serves a copy of ``document`` -- DataCube's page document (version 3), each cube over one of this engine's
        frames (``{"_type": "frame", "name": ...}``) -- as the page ``name``; served again, its version moves, and a page
        open on it opens it again. Returns its version."""
        kept = _json.loads(_json.dumps(document))
        with self._changes:
            version = self._page_versions[name] = self._page_versions.get(name, 0) + 1
            self._pages[name] = (version, kept)
            # what the open page said is of the document before: it opens this one
            self._read.pop(name, None)
        return version

    def close_page(self, name: str) -> None:
        """Stops serving a page: one open on it stops following it."""
        with self._changes:
            self._pages.pop(name, None)
            self._read.pop(name, None)

    def page_versions(self, name: str) -> dict[str, Any] | None:
        """A page's version, and each of its frames' (the frames its cubes read), or None when it serves no such page."""
        with self._changes:
            served = self._pages.get(name)
        if served is None:
            return None
        frames = sorted({cube['cube']['source']['name'] for cube in served[1].get('cubes', [])
                         if cube.get('cube', {}).get('source', {}).get('_type') == 'frame'})
        return {'version': served[0], 'frames': {frame: self.version(frame) for frame in frames}}

    def _file(self, path: str) -> Answer:
        """A file of the site, by its path under it."""
        relative = 'index.html' if path == '/' else unquote(path).lstrip('/')
        parts = relative.split('/')
        file = self.site / relative if self.site is not None else None
        # the path as written, never resolved: a built site's files are links into the build's output (Bazel's), as
        # the warehouse serves them (WarehouseServer.site)
        # a part holding ':' would be a drive on Windows ('C:/x' replaces the root), one holding NUL no file name
        if (file is None or '\\' in relative or '..' in parts or '' in parts
                or any(':' in part or '\0' in part for part in parts) or not file.is_file()):
            return _said(404, f'no such path: {path}')
        return Answer(200, _SITE_TYPES.get(file.suffix, 'application/octet-stream'), file.read_bytes())

    def changed(self, name: str) -> None:
        """Says a frame changed -- its rows, its columns (registered again), or that it was closed -- so a page
        showing it reads it again: one with the same columns re-runs its view, one with new columns opens again over
        the new model, one whose frame is gone stops following it."""
        with self._changes:
            key = name.lower()
            version = self._versions[key] = self._versions.get(key, 0) + 1
            watchers = list(self._watchers)
        for watcher in watchers:
            try:
                watcher(name, version)
            except Exception:
                # one watcher failing is its own bug: the frame's other pages are still told
                traceback.print_exc()

    def watch(self, watcher: Callable[[str, int], None]) -> Callable[[], None]:
        """Calls ``watcher(name, version)`` after each change (``changed``), from the thread that made it; returns what
        stops it. A notebook's cube is told this way; a tab asks (``version.json``)."""
        with self._changes:
            self._watchers.append(watcher)

        def unwatch() -> None:
            with self._changes:
                if watcher in self._watchers:
                    self._watchers.remove(watcher)
        return unwatch

    def seen(self, name: str) -> bool:
        """Whether this engine has served a frame of this name (now or before it was closed)."""
        with self._changes:
            return name.lower() in self._versions or name.lower() in self._models

    def _noted(self, models: dict[str, str]) -> None:
        """The frames' models as they are now (each Live one read again): one that differs from what it was last
        served with -- a Live frame's columns changed -- is a change its page is told of."""
        moved = []
        with self._changes:
            for name, model in models.items():
                key = name.lower()
                if key in self._models and self._models[key] != model:
                    moved.append(name)
                self._models[key] = model
        for name in moved:
            self.changed(name)

    def version(self, name: str) -> int:
        """A frame's version: how many times it has changed (``changed``)."""
        with self._changes:
            return self._versions.get(name.lower(), 0)


class _Server(socketserver.ThreadingMixIn, socketserver.TCPServer):
    """An HTTP server, a thread per connection, its answers computed on the shared pool. Closing it closes its open
    connections and waits for the requests being answered."""

    allow_reuse_address = False
    daemon_threads = True
    # the connections waiting to be accepted, at most (listen's backlog): socketserver's default is 5, and macOS resets a
    # connection the queue has no room for -- a burst (a page's tabs and widgets opening together, a browser opening six
    # to an origin each) on a busy machine lost one (CI run 38018795800, macOS). The systems' usual ceiling instead
    request_queue_size = 128

    def __init__(self, web: WebServer) -> None:
        self.web = web
        self._open: set[socket.socket] = set()
        # the connections whose request was read whole and is being answered: a close lets them finish
        self._answering: set[socket.socket] = set()
        self._closing = False
        self._settled = threading.Condition()
        super().__init__(('127.0.0.1', 0), _Handler)

    def answering(self, connection: socket.socket) -> None:
        with self._settled:
            self._answering.add(connection)

    def process_request(self, request: Any, client_address: Any) -> None:
        with self._settled:
            self._open.add(request)
        super().process_request(request, client_address)

    def process_request_thread(self, request: Any, client_address: Any) -> None:
        try:
            super().process_request_thread(request, client_address)
        finally:
            with self._settled:
                self._open.discard(request)
                self._answering.discard(request)
                self._settled.notify_all()

    def handle_error(self, request: Any, client_address: Any) -> None:
        # a connection the close cut (below: one still being read) fails its read: that is the close, not a fault. One
        # being answered is reported as ever (_answering is cleared only after this, in process_request_thread)
        if not (self._closing and request not in self._answering):
            super().handle_error(request, client_address)

    def server_close(self) -> None:
        super().server_close()
        with self._settled:
            self._closing = True
            for connection in self._open:
                # a request still being read ends now; one being answered finishes its answer first. A read blocked
                # in another thread ends, on Linux and macOS, when its socket is shut for reading; on Windows a
                # shutdown leaves it blocked (until the idle timeout: 30 s, found by the first Windows run) and only
                # closing the system socket cancels it. connection.close() would not: it puts the real close off until
                # the request's reader (its makefile) is closed, and that reader is the blocked thread -- so the system
                # socket is closed itself (detach, then socket.close of what it held)
                with contextlib.suppress(OSError):
                    if sys.platform != 'win32':
                        connection.shutdown(socket.SHUT_RD)
                    elif connection not in self._answering:
                        socket.close(connection.detach())
            self._settled.wait_for(lambda: not self._open)


class _Handler(http.server.BaseHTTPRequestHandler):
    # HTTP/1.0 (the stdlib's default): each answer closes its connection; one that never sends is closed after _IDLE
    # seconds
    server: _Server
    timeout = _IDLE

    def log_message(self, format: str, *args: Any) -> None:  # noqa: A002 -- the stdlib's name
        pass

    def _send(self, answer: Answer) -> None:
        self.send_response(answer.status)
        self.send_header('Content-Type', answer.content_type)
        self.send_header('Content-Length', str(len(answer.body)))
        self.send_header('Cache-Control', 'no-store')
        for name, value in answer.headers.items():
            self.send_header(name, value)
        self.end_headers()
        self.wfile.write(answer.body)

    def _length(self) -> int | None:
        """The request's Content-Length, or None when it has none this engine reads (absent, not ASCII digits)."""
        length = self.headers.get('Content-Length')
        if length is None or not (length.isascii() and length.isdigit()):
            return None
        return int(length)

    def _refuse_unread(self, status: int, message: str) -> None:
        """A refusal before the body is wanted: the body is read first (when it is within the limit), so the
        client reads the answer rather than a reset connection."""
        length = self._length()
        if length is not None and length <= _MAX_BODY:
            # in pieces, each dropped: a refused request holds no more than one piece
            with contextlib.suppress(OSError):
                while length > 0:
                    piece = self.rfile.read(min(length, 64 * 1024))
                    if not piece:
                        break
                    length -= len(piece)
        self._send(_said(status, message))

    def _admitted(self, path: str) -> bool:
        """The request names this machine, and carries the token when it reads the engine's data (``private``);
        otherwise it is refused, and False."""
        web = self.server.web
        if self.headers.get('Host') not in web._hosts:
            self._refuse_unread(403, 'this engine answers requests to 127.0.0.1 and localhost only')
            return False
        if private(path):
            given = self.headers.get('Authorization', '')
            if not hmac.compare_digest(given.encode('utf-8'), web._authorization):
                self._refuse_unread(401, "this engine answers its own page only: the request did not carry the engine's token")
                return False
        return True

    def do_POST(self) -> None:  # noqa: N802 -- the stdlib's name
        url = urlsplit(self.path)
        # the API, an open page saying what it is now (page.json: Python's page.read()), a page's file in or out, the
        # saved pages, and PlanAndRun's calls
        if not (url.path.startswith(_API) or url.path in ('/page.json', _FILE_IN, _FILE_PART, _FILE_OUT, _REMOTE, _SAVED,
                                                          _PLAN_AND_RUN, _PLANNER)):
            self._refuse_unread(404, f'no such path: {url.path}')
            return
        if not self._admitted(url.path):
            return
        length = self._length()
        if length is None:
            self._send(_said(411, 'a request body needs its Content-Length, in digits'))
            return
        if length > _MAX_BODY:
            self._send(_said(413, f'a request body is at most {_MAX_BODY} bytes'))
            return
        try:
            raw = self.rfile.read(length)
        except OSError:
            # the client stopped sending, or went: there is no one to answer
            return
        if len(raw) < length:
            # the connection ended before its body did (the client went, or the server is closing)
            return
        self.server.answering(self.connection)
        try:
            body = raw.decode('utf-8')
        except UnicodeDecodeError:
            self._send(_said(400, 'a request body is UTF-8'))
            return
        self._send(self.server.web.engine.answer('POST', url.path, url.query, body))

    def do_GET(self) -> None:  # noqa: N802
        """A frame's cube or version (with the token), or a file of the engine's site (DataCube's built pages), to a
        Host naming this machine."""
        url = urlsplit(self.path)
        if self._admitted(url.path):
            self.server.answering(self.connection)
            self._send(self.server.web.engine.answer('GET', url.path, url.query, None))

    def do_OPTIONS(self) -> None:  # noqa: N802
        # no cross-origin request is answered: only the engine's own page calls it
        self._refuse_unread(405, 'this engine answers no cross-origin request')


class WebServer:
    """An engine served over HTTP to a browser tab, on 127.0.0.1 at ``url``, from a background thread until
    ``close()``. Calls for the engine's data carry ``authorization`` (the one-time token); the site's files are served
    at the same origin, so the page needs no cross-origin access."""

    def __init__(self, engine: Engine) -> None:
        self.engine = engine
        self.token = secrets.token_urlsafe(32)
        self._authorization = f'Bearer {self.token}'.encode('utf-8')
        self._server = _Server(self)
        port = self._server.server_address[1]
        self.url = f'http://127.0.0.1:{port}'
        self._hosts = frozenset({f'127.0.0.1:{port}', f'localhost:{port}'})
        self._thread = threading.Thread(target=self._server.serve_forever, name='legend-lite-engine', daemon=True)
        self._thread.start()

    @property
    def authorization(self) -> str:
        """The header value every call for the engine's data carries: ``Bearer <token>``."""
        return self._authorization.decode('utf-8')

    def close(self) -> None:
        """Stops serving; requests being answered finish first."""
        self._server.shutdown()
        self._server.server_close()
        self._thread.join()

    def __enter__(self) -> WebServer:
        return self

    def __exit__(self, *exc: object) -> None:
        self.close()
