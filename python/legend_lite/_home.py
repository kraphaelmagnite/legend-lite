"""Where legend-lite keeps this user's things on this machine (docs/DATACUBE_ONE_APP_DESIGN_2026_10_11.md §6, Q1): the
Python analogue of the browser's per-site storage -- a folder of this user's, shared by every notebook and script --
and, in it, the saved pages DataCube's Save and Open… keep (§5, step 2d).

``home()`` says where it is: the folder ``home(path)`` pointed this process at, else ``LEGEND_LITE_HOME`` (a folder for
every process started with it: a test's own), else the operating system's per-user data folder (macOS
``~/Library/Application Support/legend-lite``, Linux ``$XDG_DATA_HOME/legend-lite`` or ``~/.local/share/legend-lite``,
Windows ``%LOCALAPPDATA%\\legend-lite``). Its folders are made private to this user (on POSIX: only its owner reads
them), as the browser's storage is the person's own.

The saved pages are the browser's records, kept as files: the page's rules (who owns a page, its times, search) are
DataCube's own, run in the page, as they are over the browser's IndexedDB (cube-store.ts's RuleStore). Python keeps
the records -- all, one, put, remove -- one file each, written whole or not at all, under a lock several Python
processes share (two notebooks at once)."""

from __future__ import annotations

import contextlib
import json
import os
import re
import sys
import tempfile
from collections.abc import Iterator
from pathlib import Path
from typing import Any

_chosen: Path | None = None


def home(path: str | Path | None = None) -> Path:
    """This user's legend-lite folder (made when first used). Given a path, this process keeps its things there from
    now on (a folder beside a notebook, say) and says it."""
    global _chosen
    if path is not None:
        _chosen = Path(path).expanduser().resolve()
    if _chosen is not None:
        return _chosen
    explicit = os.environ.get('LEGEND_LITE_HOME')
    if explicit:
        return Path(explicit).expanduser().resolve()
    return _system_home()


def _system_home() -> Path:
    if sys.platform == 'darwin':
        return Path.home() / 'Library' / 'Application Support' / 'legend-lite'
    if sys.platform == 'win32':
        local = os.environ.get('LOCALAPPDATA')
        return (Path(local) if local else Path.home() / 'AppData' / 'Local') / 'legend-lite'
    data = os.environ.get('XDG_DATA_HOME')
    return (Path(data) if data else Path.home() / '.local' / 'share') / 'legend-lite'


# a record's id names its file: what a page makes (crypto.randomUUID) and nothing that could name another place -- nor
# a device Windows keeps whatever folder it is in (CON, NUL, COM1, ...)
_ID = re.compile(r'[A-Za-z0-9][A-Za-z0-9_-]{0,127}\Z')
_DEVICE = re.compile(r'(CON|PRN|AUX|NUL|COM[0-9]|LPT[0-9])\Z', re.IGNORECASE)


class SavedPages:
    """DataCube's saved pages in a folder (``home()/pages``): the browser's records, one ``<id>.json`` file each, each as
    the page wrote it (its content the protocol's exact JSON text, never read here)."""

    def __init__(self, folder: Path | None = None) -> None:
        self.folder = folder if folder is not None else home() / 'pages'

    def all(self) -> list[dict[str, Any]]:
        """Every record; one that is not a record (a file written by something else, cut short) is left out, and said
        on standard error, so the others still open."""
        found = []
        with self._locked():
            for f in sorted(self.folder.glob('*.json')):
                try:
                    found.append(self._read(f))
                except (ValueError, OSError) as unread:
                    print(f'legend-lite: {f} is not a saved page ({unread}); it is left out', file=sys.stderr)
        return found

    def find(self, id: str) -> dict[str, Any] | None:
        path = self._path(id)
        with self._locked():
            return self._read(path) if path.is_file() else None

    def put(self, record: dict[str, Any]) -> None:
        """A record written whole: to a file beside it, then moved over it, so a reader never finds half of one."""
        if not isinstance(record.get('content'), str) or not isinstance(record.get('name'), str):
            raise ValueError('a saved page is a record with its name and its content (the page as JSON text)')
        path = self._path(record.get('id'))
        with self._locked():
            written = tempfile.NamedTemporaryFile('w', encoding='utf-8', dir=self.folder, suffix='.part', delete=False)
            try:
                with written:
                    json.dump(record, written, ensure_ascii=False)
                os.replace(written.name, path)
            except BaseException:
                Path(written.name).unlink(missing_ok=True)
                raise

    def remove(self, id: str) -> None:
        path = self._path(id)
        with self._locked():
            path.unlink(missing_ok=True)

    def _path(self, id: Any) -> Path:
        if not isinstance(id, str) or not _ID.match(id) or _DEVICE.match(id):
            raise ValueError(f'a saved page\'s id is letters, digits, "-" and "_" (as a page makes one), not {id!r}')
        return self.folder / f'{id}.json'

    @staticmethod
    def _read(path: Path) -> dict[str, Any]:
        record = json.loads(path.read_text(encoding='utf-8'))
        if not isinstance(record, dict):
            raise ValueError('not a record')
        return record

    @contextlib.contextmanager
    def _locked(self) -> Iterator[None]:
        """The folder held for one call, across every Python process of this user (a lock file beside the records). The
        folder and the one it is in are made private to this user when this makes them."""
        self.folder.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        self.folder.mkdir(mode=0o700, exist_ok=True)
        with open(self.folder / '.lock', 'a+b') as lock:
            if sys.platform == 'win32':
                import msvcrt
                lock.seek(0)
                # blocks until the other process lets go (retried by msvcrt every second, for ten tries, then raises)
                msvcrt.locking(lock.fileno(), msvcrt.LK_LOCK, 1)
                try:
                    yield
                finally:
                    lock.seek(0)
                    msvcrt.locking(lock.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                import fcntl
                fcntl.flock(lock.fileno(), fcntl.LOCK_EX)
                try:
                    yield
                finally:
                    fcntl.flock(lock.fileno(), fcntl.LOCK_UN)
