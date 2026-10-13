"""INTO A PROJECT (docs/ONE_PLATFORM_PROGRAM_2026_10_12.md B4): a class, an association or a data space made in any
project -- a generic Legend capability, the twin of the TypeScript library model-changes/ (the same functions, the same
parameters, the same backends; model-changes/test/cases holds the two alike). Each function makes the project and its
workspace when they are not yet, reads the project's files as one model text, asks legend-lite's model writer for the
files, and saves them as one revision, made again once over a newer one.

The SDLC is one with lite's text routes: this user's model home in this process (``local_sdlc``: the native library's,
the rules the page's in-page SDLC and sdlc-server run), or sdlc-server (``server_sdlc``); a real legend-sdlc answers
neither text route, and is owed over its entities (B4). The writer is legend-lite's model writer in this process
(``local_writer``: the native library) or a legend-lite server's (``server_writer``). A server's sign-in is not here yet
(the program's G10)."""

from __future__ import annotations

import json
import re
import urllib.error
import urllib.parse
import urllib.request
from collections.abc import Callable, Sequence
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Protocol

from . import _json, compiler

# a server is given this long to answer one call (seconds)
_TIMEOUT = 60


class SdlcError(Exception):
    """An SDLC's refusal, as it said it: its status and its own words (sdlc-client's ``SdlcError``)."""

    def __init__(self, message: str, status: int) -> None:
        super().__init__(message)
        self.message = message
        self.status = status


# one call of an SDLC's API: its method, its path under the API's root, its JSON body (or none) -> status, answer text
Call = Callable[[str, str, 'str | None'], 'tuple[int, str]']


def _enc(part: str) -> str:
    """A path segment as encodeURIComponent writes it (sdlc-client's ``enc``)."""
    return urllib.parse.quote(part, safe="-_.!~*'()")


def _base(project: str, workspace: str | None = None, revision: str | None = None) -> str:
    """``/projects/{p}[/workspaces/{w}][/revisions/{r}]`` (sdlc-client's ``base``)."""
    path = f'/projects/{_enc(project)}'
    if workspace is not None:
        path += f'/workspaces/{_enc(workspace)}'
    if revision is not None:
        path += f'/revisions/{_enc(revision)}'
    return path


class Sdlc:
    """An SDLC, as this library calls it: upstream legend-sdlc's REST API, the calls a create makes (the twin of
    sdlc-client's ``SdlcClient``, these of it), through one ``call`` -- the model home in this process, or a server."""

    def __init__(self, call: Call) -> None:
        self._call = call

    def _json(self, method: str, path: str, body: Any = None) -> Any:
        status, text = self._call(method, path, None if body is None else json.dumps(body, separators=(',', ':'),
                                                                                    ensure_ascii=False))
        if not 200 <= status < 300:
            message = text or str(status)
            try:
                said = json.loads(text)
                if isinstance(said, dict) and isinstance(said.get('message'), str):
                    message = said['message']
            except ValueError:
                pass
            raise SdlcError(message, status)
        return json.loads(text) if text else None

    def projects(self) -> list[dict[str, Any]]:
        return self._json('GET', '/projects')

    def create_project(self, command: dict[str, Any]) -> dict[str, Any]:
        return self._json('POST', '/projects', command)

    def workspace(self, project: str, workspace: str) -> dict[str, Any]:
        return self._json('GET', _base(project, workspace))

    def create_workspace(self, project: str, workspace: str) -> dict[str, Any]:
        return self._json('POST', _base(project, workspace))

    def revision(self, project: str, workspace: str, revision: str = 'CURRENT') -> dict[str, Any]:
        return self._json('GET', _base(project, workspace, revision))

    def pure(self, project: str, workspace: str, revision: str) -> list[dict[str, Any]]:
        return self._json('GET', f'{_base(project, workspace, revision)}/pure')

    def perform_pure_changes(self, project: str, workspace: str, command: dict[str, Any]) -> dict[str, Any] | None:
        """The new revision, or None when the save changed nothing (204: every change the bytes already there)."""
        return self._json('POST', f'{_base(project, workspace)}/pureChanges', command)


def home_call(folder: Path, kind: str, method: str, path: str, body: str | None) -> tuple[int, str]:
    """One call of the model home in this process (``kind``: ``'sdlc'`` or ``'depot'``): the native library's SDLC or
    Depot over the git repository in ``folder``, the library's call on one of the compiler's threads -- the one way
    Python's engine (its ``/sdlc/api``, ``/depot/api``) and this library reach it. The library makes each call under the
    repository's own lock, which every kind of process that opens it takes (the model home's server too;
    docs/ONE_PLATFORM_PROGRAM_2026_10_12.md G11); Python's own lock across its processes is taken first, on the
    caller's thread, so a call waiting on another Python process holds no compiler thread. A failure of the library's
    raised (``OSError``)."""
    from ._home import locked
    from ._library import library
    from .engine import _workers
    with locked(folder):
        said = _workers().submit(library().call, f'lite_{kind}_handle', str(folder), method, path, body or '').result()
    tag, _, rest = said.partition('\n')
    if tag != 'OK':
        raise OSError(rest.partition('\n')[2] or rest)
    status, _, answered = rest.partition('\n')
    return int(status), answered


def local_sdlc(folder: str | Path | None = None) -> Sdlc:
    """This user's model home, in this process: the native library's SDLC over the git repository in ``folder`` (this
    user's ``home()/projects`` when not given), under the lock every Python process takes on it."""
    from ._home import model_home_folder
    where = Path(folder) if folder is not None else model_home_folder()
    return Sdlc(lambda method, path, body: home_call(where, 'sdlc', method, path, body))


def _http(url: str, method: str, body: str | None) -> tuple[int, str]:
    request = urllib.request.Request(url, data=None if body is None else body.encode('utf-8'), method=method,
                                     headers={} if body is None else {'Content-Type': 'application/json'})
    try:
        with urllib.request.urlopen(request, timeout=_TIMEOUT) as answer:
            return answer.status, answer.read().decode('utf-8')
    except urllib.error.HTTPError as refused:
        return refused.code, refused.read().decode('utf-8', errors='replace')


def server_sdlc(api: str) -> Sdlc:
    """An SDLC server with lite's text routes at its API's root (sdlc-server's ``http://host:port/sdlc/api``)."""
    root = api.rstrip('/')
    return Sdlc(lambda method, path, body: _http(root + path, method, body))


class ModelWriter(Protocol):
    """legend-lite's model writer: its three routes, wherever they are answered (model-changes' ``ModelWriter``). Each
    is one call, atomic, and writes nothing itself: it answers the files. ``model``: the project's files as one model
    text (``project_text``), which the route reads beside what it makes."""

    def create_class(self, model: str, package: str, cls: dict[str, Any]) -> dict[str, Any]: ...

    def create_association(self, model: str, package: str, association: dict[str, Any] | None,
                           classes: Sequence[dict[str, Any]] = ()) -> dict[str, Any]: ...

    def create_data_space(self, model: str, package: str, data_space: dict[str, Any],
                          classes: Sequence[dict[str, Any]] = (),
                          associations: Sequence[dict[str, Any]] = ()) -> dict[str, Any]: ...


def model_request_body(model: str, package: str, parts: dict[str, Any]) -> str:
    """A route's body, each class's relation written by the protocol's own rule (its numbers keep their digits) and the
    rest as written, its runtime in its ``where`` (model-changes' ``modelRequestBody``, byte for byte)."""
    def cls(c: dict[str, Any]) -> str:
        rest = {k: v for k, v in c.items() if k not in ('query', 'source', 'where')}
        fields = _json.dumps({'source': {'_type': 'text', 'code': c['source']['model']}, **rest,
                              'where': {**c['where'], 'runtime': c['source']['runtime']}})
        return '{"lambda":' + _json.protocol(c['query']) + ',' + fields[1:]
    # the route's ``project`` field is the project's model text
    fields = [f'"project":{_json.dumps({"_type": "text", "code": model})}', f'"package":{_json.dumps(package)}']
    if 'class' in parts:
        fields.append(f'"class":{cls(parts["class"])}')
    if 'classes' in parts:
        fields.append('"classes":[' + ','.join(cls(c) for c in parts['classes']) + ']')
    if 'association' in parts:
        fields.append(f'"association":{_json.dumps(parts["association"])}')
    if 'associations' in parts:
        fields.append(f'"associations":{_json.dumps(list(parts["associations"]))}')
    if 'data_space' in parts:
        fields.append(f'"dataSpace":{_json.dumps(parts["data_space"])}')
    return '{' + ','.join(fields) + '}'


def writer_body(route: str, model: str, package: str, *args: Any) -> str:
    """Each route's body as every writer sends it (model-changes' ``writerBodies``): a route's lists always there, empty
    when there is nothing in them."""
    if route == 'createClass':
        return model_request_body(model, package, {'class': args[0]})
    if route == 'createAssociation':
        association, classes = args[0], list(args[1]) if len(args) > 1 else []
        return model_request_body(model, package, {'classes': classes} if association is None
                                  else {'classes': classes, 'association': association})
    if route == 'createDataSpace':
        classes = list(args[1]) if len(args) > 1 else []
        associations = list(args[2]) if len(args) > 2 else []
        return model_request_body(model, package, {'classes': classes, 'associations': associations,
                                                     'data_space': args[0]})
    raise ValueError(f'the model writer has no route {route!r}')


class _Writer:
    """A writer over one ``post(route, body) -> answer text``."""

    def __init__(self, post: Callable[[str, str], str]) -> None:
        self._post = post

    def _write(self, route: str, body: str) -> dict[str, Any]:
        return _json.loads(self._post(route, body))

    def create_class(self, model: str, package: str, cls: dict[str, Any]) -> dict[str, Any]:
        return self._write('createClass', writer_body('createClass', model, package, cls))

    def create_association(self, model: str, package: str, association: dict[str, Any] | None,
                           classes: Sequence[dict[str, Any]] = ()) -> dict[str, Any]:
        return self._write('createAssociation', writer_body('createAssociation', model, package, association, classes))

    def create_data_space(self, model: str, package: str, data_space: dict[str, Any],
                          classes: Sequence[dict[str, Any]] = (),
                          associations: Sequence[dict[str, Any]] = ()) -> dict[str, Any]:
        return self._write('createDataSpace',
                           writer_body('createDataSpace', model, package, data_space, classes, associations))


def local_writer() -> ModelWriter:
    """legend-lite's model writer in this process: the native library answers its routes as the tab's module does."""
    def post(route: str, body: str) -> str:
        answer = compiler.pure_v1(f'/api/lite/v1/model/{route}', '', body)
        if answer.status != 200:
            raise compiler.LegendError(_message(answer.body), str(answer.status))
        return answer.body
    return _Writer(post)


def server_writer(url: str) -> ModelWriter:
    """legend-lite's model writer on a server: its routes over HTTP."""
    root = url.rstrip('/')

    def post(route: str, body: str) -> str:
        status, text = _http(f'{root}/api/lite/v1/model/{route}', 'POST', body)
        if not 200 <= status < 300:
            raise compiler.LegendError(f"legend-lite's {route} at {root}: {_message(text)}", str(status))
        return text
    return _Writer(post)


def _message(text: str) -> str:
    try:
        said = json.loads(text)
    except ValueError:
        return text
    return said['message'] if isinstance(said, dict) and isinstance(said.get('message'), str) else text


def project_text(files: Sequence[dict[str, Any]]) -> str:
    """A project's files as one model text: what the writer reads beside what it makes (``projectText``)."""
    return '\n'.join(f['pureCode'] if f['pureCode'].startswith('###') else f'###Pure\n{f["pureCode"]}' for f in files)


def artifact_id_of(name: str) -> str:
    """A name as an artifact id: lowercase words joined by hyphens, led by a letter (the SDLC's own rule)."""
    words = [w for w in re.split(r'[^a-z0-9]+', name.lower()) if w]
    made = re.sub(r'^[^a-z]+', '', '-'.join(words))
    return made or 'project'


@dataclass(frozen=True)
class NewProject:
    """A project to make: its name (its artifact id by the SDLC's rule, a taken one numbered), its group, its
    description."""

    name: str
    group: str
    description: str = ''


@dataclass(frozen=True)
class Created:
    """What was made: where, the revision it was saved as, and the writer's answer (the files, the classes, its notes)."""

    project: str
    workspace: str
    revision: str
    answer: dict[str, Any]


class ProjectGone(Exception):
    """A project the SDLC no longer has: said by name."""

    def __init__(self, project: str) -> None:
        super().__init__(f'the project {project} is not in this model home: it was made elsewhere, or its store was '
                         'cleared')
        self.project = project


def make_project(sdlc: Sdlc, made: NewProject) -> str:
    """A project made: in its group, named after ``made.name`` -- a name another project of the group has already
    numbered (``trades-2``)."""
    taken = {p['projectId'] for p in sdlc.projects()}
    base = artifact_id_of(made.name)
    artifact_id, n = base, 2
    while f'{made.group}:{artifact_id}' in taken:
        artifact_id, n = f'{base}-{n}', n + 1
    project = sdlc.create_project({'name': made.name.strip() or artifact_id, 'description': made.description,
                                   'groupId': made.group, 'artifactId': artifact_id})
    return project['projectId']


def ensure_workspace(sdlc: Sdlc, project: str, workspace: str) -> None:
    """The workspace, there: made from the project line when it is not. Its making refused, the workspace is read
    again -- another ask may have made it meanwhile, which lite's SDLC answers with a 500 ("Branch already exists") once
    the workspace has commits -- and the refusal said only when it is still not there (``ensureWorkspace``). A project
    that is not there is said, by name."""
    try:
        sdlc.workspace(project, workspace)
        return
    except SdlcError as e:
        if e.status != 404:
            raise
    try:
        sdlc.create_workspace(project, workspace)
    except SdlcError as e:
        if e.status == 404:
            raise ProjectGone(project) from None
        try:
            sdlc.workspace(project, workspace)
        except Exception:
            # not there, however the read failed: the making's own refusal is said (ensureWorkspace's rule)
            raise e from None


def project_state(sdlc: Sdlc, project: str, workspace: str) -> tuple[str, str]:
    """The workspace's files now as one model text, and the revision they are (the workspace made when it is not)."""
    ensure_workspace(sdlc, project, workspace)
    revision = sdlc.revision(project, workspace)['id']
    return project_text(sdlc.pure(project, workspace, revision)), revision


def save_fresh(sdlc: Sdlc, project: str, workspace: str, message: str,
               make: Callable[[str], dict[str, Any]]) -> tuple[dict[str, Any], str]:
    """MAKE AND SAVE, ONCE AGAIN WHEN STALE (``saveFresh``): ``make`` the changes against the project's model text as it
    is, saved over that revision as one; refused as stale (409), read again and made again, once -- a second refusal
    is said. Answers what was made and the revision it was saved as: the one read, when the save changed nothing (no
    changes, or every one the bytes already there: the SDLC's 204)."""
    for attempt in range(2):
        text, revision = project_state(sdlc, project, workspace)
        made = make(text)
        if not made['changes']:
            return made, revision
        try:
            saved = sdlc.perform_pure_changes(project, workspace,
                                              {'message': message, 'changes': made['changes'], 'revisionId': revision})
            return made, saved['id'] if saved is not None else revision
        except SdlcError as e:
            if e.status != 409 or attempt > 0:
                raise
    raise AssertionError('unreachable')


def _into(sdlc: Sdlc, project: str | NewProject, workspace: str, message: str,
          make: Callable[[str], dict[str, Any]], made: Callable[[str], None] | None) -> Created:
    if isinstance(project, NewProject):
        project = make_project(sdlc, project)
        if made is not None:
            made(project)
    answer, revision = save_fresh(sdlc, project, workspace, message, make)
    return Created(project, workspace, revision, answer)


def create_class(sdlc: Sdlc, writer: ModelWriter, project: str | NewProject, workspace: str, package: str,
                 cls: dict[str, Any], made: Callable[[str], None] | None = None) -> Created:
    """A class made from a relation's rows (``createClass``): ``cls`` is the writer's class action -- its ``query`` (the
    relation, a lambda as protocol JSON), ``source`` (``{"model", "runtime"}``), ``name``, ``documentation``,
    ``columns`` and ``where``. ``made``: told the project's id as soon as it is made."""
    return _into(sdlc, project, workspace, f'Create a Class: {cls["name"]}',
                 lambda text: writer.create_class(text, package, cls), made)


def create_association(sdlc: Sdlc, writer: ModelWriter, project: str | NewProject, workspace: str, package: str,
                       association: dict[str, Any], classes: Sequence[dict[str, Any]] = (),
                       made: Callable[[str], None] | None = None) -> Created:
    """An association between two classes of the project (or of ``classes``, made with it)."""
    return _into(sdlc, project, workspace, 'Create an Association',
                 lambda text: writer.create_association(text, package, association, classes), made)


def create_data_space(sdlc: Sdlc, writer: ModelWriter, project: str | NewProject, workspace: str, package: str,
                      data_space: dict[str, Any], classes: Sequence[dict[str, Any]] = (),
                      associations: Sequence[dict[str, Any]] = (),
                      made: Callable[[str], None] | None = None) -> Created:
    """A data space over the project's classes, the classes and associations given made with it."""
    return _into(sdlc, project, workspace, f'Create a Data Space: {data_space["name"]}',
                 lambda text: writer.create_data_space(text, package, data_space, classes, associations), made)
