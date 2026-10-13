"""A PROJECT'S OWN DATABASE on Python's DuckDB (docs/ONE_PLATFORM_PROGRAM_2026_10_12.md C5), the twin of the tab's
(engine-client/src/project-data.ts): where a project version's Data rows live when Query or Studio, served by Python,
runs one of its queries -- a database of their own inside Python's DuckDB, its schemas as the model declares them, never
beside a person's frames. What it is, and every statement that makes, points at, fills and drops it, is the native
library's (``compiler.project_data``, the platform's ProjectData in Java, by this database's type); Python spells none.
Made the first time a query over the model asks, on a cursor of its own; kept while in use; dropped when it has sat
idle ``idle_seconds``, or when more than ``most`` are open and it is the least recently used idle one -- it is rebuilt
from the model the next time, so dropping early is never wrong. A model with no Data rows has none."""

from __future__ import annotations

import threading
import time
from collections.abc import Callable, Iterator
from contextlib import AbstractContextManager, contextmanager, nullcontext
from dataclasses import dataclass, field
from typing import Any

from . import compiler


@dataclass
class _Open:
    statements: dict[str, Any]
    cursor: Any
    # the project's model, held: what its queries are planned against (a page's planner requests, plan-and-run.json)
    held: compiler.HeldModel
    busy: int = 0
    used: float = field(default_factory=time.monotonic)
    idle: threading.Timer | None = None


@dataclass(frozen=True)
class Project:
    """A project's database in use: a cursor pointed at it, and its model held."""

    cursor: Any
    held: compiler.HeldModel


class ProjectDatabases:
    """The project databases on one DuckDB connection (an engine's Frames'): ``using`` a model's, made the first time."""

    def __init__(self, connection: Any, database_type: str = 'DuckDB', idle_seconds: float = 15 * 60,
                 most: int = 8, guard: Callable[[], AbstractContextManager[Any]] = nullcontext,
                 on_library_thread: Callable[[Callable[[], Any]], Any] = lambda call: call()) -> None:
        """``guard``: what every use of ``connection`` from another thread holds (an engine's Frames' lock): a
        database dropped when idle is dropped under it, never beside a query running. ``on_library_thread``: where a
        call of the native library is made (an engine's compiler threads): an idle drop lets its model go there."""
        self._connection = connection
        self._guard = guard
        self._on_library_thread = on_library_thread
        self._type = database_type
        self._idle = idle_seconds
        self._most = most
        self._open: dict[str, _Open] = {}
        self._lock = threading.RLock()

    @property
    def names(self) -> list[str]:
        """The project databases open now."""
        with self._lock:
            return list(self._open)

    @contextmanager
    def using(self, model: str, identity: str = '') -> Iterator[Any]:
        """``model``'s project database (kept by ``identity``; ``''`` for the model's text) -- a cursor on it and the
        model held -- made and filled the first time, in use until the block ends; None when the model has no Data
        rows. Made and dropped under ``guard``: the statements on the host's own connection never beside its queries."""
        try:
            statements = compiler.project_data(model, identity, self._type)
        except compiler.LegendError:
            # no model at all (it does not compile): no project's either, refused where a model is refused
            statements = None
        if statements is None or not statements['fill']:
            yield None
            return
        with self._guard(), self._lock:
            opened = self._open.get(statements['name'])
            if opened is None:
                opened = self._make(model, statements)
                self._open[statements['name']] = opened
            opened.busy += 1
            opened.used = time.monotonic()
            if opened.idle is not None:
                opened.idle.cancel()
                opened.idle = None
            self._trim(statements['name'])
        try:
            yield Project(opened.cursor, opened.held)
        finally:
            with self._lock:
                opened.busy -= 1
                if opened.busy == 0 and self._open.get(statements['name']) is opened:
                    opened.idle = threading.Timer(self._idle, self._idled, (statements['name'], opened))
                    # an engine's thread: no process waits on it
                    opened.idle.daemon = True
                    opened.idle.start()

    def drop(self, name: str) -> None:
        """``name`` dropped now, if it is open and none uses it."""
        with self._guard(), self._lock:
            opened = self._open.get(name)
            if opened is None or opened.busy > 0:
                return
            del self._open[name]
            self._close(opened)

    def drop_all(self) -> None:
        """Every project database dropped (the engine closes)."""
        with self._guard(), self._lock:
            for name in list(self._open):
                self.drop(name)

    def _make(self, model: str, statements: dict[str, Any]) -> _Open:
        held = compiler.HeldModel(model)
        for sql in statements['open']:
            self._connection.execute(sql)
        cursor = self._connection.cursor()
        try:
            # a cursor's session is its own: set up as every connection Python plans for is (frames.py)
            for sql in [*compiler.session_setup(self._type), *statements['use'], *statements['fill']]:
                cursor.execute(sql)
        except Exception:
            # a half-made database left behind would be found again by its name: drop it
            cursor.close()
            for sql in statements['close']:
                self._connection.execute(sql)
            held.close()
            raise
        return _Open(statements, cursor, held)

    def _close(self, opened: _Open) -> None:
        if opened.idle is not None:
            opened.idle.cancel()
        opened.cursor.close()
        for sql in opened.statements['close']:
            self._connection.execute(sql)
        opened.held.close()

    def _idled(self, name: str, opened: _Open) -> None:
        def idle_drop() -> None:
            with self._guard(), self._lock:
                if self._open.get(name) is opened and opened.busy == 0:
                    self.drop(name)
        self._on_library_thread(idle_drop)

    def _trim(self, keep: str) -> None:
        idle = sorted((o.used, n) for n, o in self._open.items() if n != keep and o.busy == 0)
        while len(self._open) > self._most and idle:
            self.drop(idle.pop(0)[1])
