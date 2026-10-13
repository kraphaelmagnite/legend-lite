"""The engine (legend_lite.engine): legend-engine's pure/v1 API over frames, as DataCube's remote client calls it --
parse, print and a query's types answered by legend-lite's server code, execute in upstream's Arrow format with
the rows DuckDB computes over the frame as it is then; the refusals in the engine's error shape; and only the
engine's own page answered (its token, a local Host, no cross-origin answer)."""

import http.client
import json
from decimal import Decimal
import os
import socket
import sys
import tempfile
import threading
import time
import unittest
from pathlib import Path
import urllib.error
import urllib.request

import pandas as pd
import pyarrow as pa
import pyarrow.ipc

import legend_lite as ll
from legend_lite.engine import Engine, NativePlanner, WebServer
from legend_lite._home import SavedPages

GROUP = "->filter(x|$x.qty > 5)->groupBy(~[desk], ~[q: x|$x.qty : y|$y->sum()])->sort(~desk->ascending())"


def trades():
    return pd.DataFrame({
        'id': [1, 2, 3, 4, 5],
        'desk': ['FX', 'EQ', 'FX', 'RATES', 'EQ'],
        'qty': [10.5, 20.0, 30.25, 1.0, 7.5],
    })


class Served(unittest.TestCase):
    """An engine over one Live frame, and requests as DataCube's client sends them."""

    def setUp(self):
        self.df = trades()
        self.frames = ll.Frames()
        self.table = self.frames.register('trades', self.df)
        self.server = WebServer(Engine(self.frames))

    def tearDown(self):
        self.server.close()

    def post(self, path, body, content_type='application/json', headers=None):
        """The engine's answer to a POST: status, headers, body bytes."""
        sent = {'Content-Type': content_type, 'Authorization': self.server.authorization} | (headers or {})
        request = urllib.request.Request(self.server.url + path, body.encode('utf-8'), sent, method='POST')
        try:
            with urllib.request.urlopen(request, timeout=30) as r:
                return r.status, r.headers, r.read()
        except urllib.error.HTTPError as e:
            return e.code, e.headers, e.read()

    def query(self, steps):
        """A cube query as the client sends it: over the table, then ->from(runtime) (fromRuntime)."""
        return ll.parse('|' + self.table.accessor + steps + '->from(' + self.table.runtime + ')')

    def execute_input(self, tree, model=None):
        return json.dumps({
            'clientVersion': 'vX_X_X',
            'function': tree,
            'model': {'_type': 'text', 'code': self.table.model if model is None else model},
            'context': {'_type': 'BaseExecutionContext', 'queryTimeOutInSeconds': 60, 'enableConstraints': True},
        })

    def arrow(self, steps):
        status, headers, body = self.post('/api/pure/v1/execution/execute?serializationFormat=ARROW_IPC',
                                          self.execute_input(self.query(steps)))
        self.assertEqual(status, 200, body[:300])
        return headers, read_arrow(body)


def read_arrow(body):
    """An ARROW_IPC answer read as upstream writes it: one zstd frame around an Arrow IPC stream."""
    with pa.input_stream(pa.py_buffer(body), compression='zstd') as stream:
        return pyarrow.ipc.open_stream(stream).read_all()


class Calls(Served):
    def test_parse_print_and_types_are_the_compilers(self):
        text = '|' + self.table.accessor + GROUP
        status, headers, body = self.post('/api/pure/v1/grammar/grammarToJson/lambda?returnSourceInformation=false',
                                          text, 'text/plain')
        self.assertEqual((status, headers['Content-Type']), (200, 'application/json'))
        tree = json.loads(body)
        self.assertEqual(tree, ll.parse(text))

        status, headers, body = self.post('/api/pure/v1/grammar/jsonToGrammar/lambda?renderStyle=STANDARD', json.dumps(tree))
        self.assertEqual((status, headers['Content-Type']), (200, 'text/plain'))
        self.assertEqual(body.decode('utf-8'), ll.print_tree(tree, 'STANDARD'))

        status, _, body = self.post('/api/pure/v1/compilation/lambdaRelationType',
                                    json.dumps({'lambda': tree, 'model': {'_type': 'text', 'code': self.table.model}}))
        self.assertEqual(status, 200, body)
        self.assertEqual([(c['name'], c['genericType']['rawType']['fullPath']) for c in json.loads(body)['columns']],
                         [('desk', 'String'), ('q', 'Float')])

    def test_execute_answers_upstreams_arrow_with_the_rows_duckdb_computes(self):
        headers, rows = self.arrow(GROUP)
        self.assertEqual((headers['Content-Type'], headers['x-legend-response-format']), ('application/json', 'FormatNotSet'))
        self.assertEqual(rows.to_pylist(), self.table.execute(GROUP).to_pylist())
        self.assertEqual(rows.to_pylist(), [{'desk': 'EQ', 'q': 27.5}, {'desk': 'FX', 'q': 40.75}])
        metadata = {k.decode(): v.decode() for k, v in rows.schema.metadata.items()}
        self.assertEqual(list(metadata), ['legend.builder', 'legend.activities', 'legend.columns'])
        self.assertEqual(json.loads(metadata['legend.columns']), ['desk', 'q'])
        self.assertEqual(json.loads(metadata['legend.builder']), {'_type': 'tdsBuilder', 'columns': [
            # the relational spellings as legend-engine writes them (legend-lite's server's builder)
            {'name': 'desk', 'type': 'String', 'relationalType': 'VARCHAR(1024)'},
            {'name': 'q', 'type': 'Float', 'relationalType': 'FLOAT'},
        ]})
        [activity] = json.loads(metadata['legend.activities'])
        self.assertEqual(list(activity), ['comment', 'sql'])
        self.assertEqual(activity['sql'], self.table.plan(GROUP).sql)

    def test_an_empty_answer_is_its_columns_and_no_batch_as_upstreams(self):
        status, _, body = self.post('/api/pure/v1/execution/execute?serializationFormat=ARROW_IPC',
                                    self.execute_input(self.query("->filter(x|$x.desk == 'NONE')")))
        self.assertEqual(status, 200, body[:300])
        with pa.input_stream(pa.py_buffer(body), compression='zstd') as stream:
            reader = pyarrow.ipc.open_stream(stream)
            self.assertEqual((reader.schema.names, list(reader)), (['id', 'desk', 'qty'], []))

    def test_live_the_next_query_reads_the_frame_as_it_is_then(self):
        _, before = self.arrow(GROUP)
        self.df.loc[1, 'qty'] = 100.0
        _, after = self.arrow(GROUP)
        self.assertNotEqual(after.to_pylist(), before.to_pylist())
        self.assertEqual(after.to_pylist(), [{'desk': 'EQ', 'q': 107.5}, {'desk': 'FX', 'q': 40.75}])

    def test_requests_at_once_are_each_answered(self):
        answers = [None] * 12

        def ask(i):
            # a request that fails says how in its place, rather than leaving it None
            try:
                answers[i] = self.arrow(GROUP)[1].to_pylist()
            except Exception as failure:
                answers[i] = repr(failure)

        threads = [threading.Thread(target=ask, args=(i,)) for i in range(len(answers))]
        for t in threads:
            t.start()
        for t in threads:
            t.join()
        self.assertEqual(answers, [[{'desk': 'EQ', 'q': 27.5}, {'desk': 'FX', 'q': 40.75}]] * len(answers))
        self.assertEqual(ll.compiler.library().unfreed(), 0)


    def test_a_burst_of_connections_waits_its_turn(self):
        # the listening queue holds a burst, never socketserver's five: macOS resets a connection the queue has no room
        # for (the engine's comment says when it was seen); the burst itself depends on the machine's load, so the
        # queue's length is what is held here
        self.assertGreaterEqual(self.server._server.request_queue_size, 128)


class Refusals(Served):
    def error(self, path, body, content_type='application/json'):
        status, headers, raw = self.post(path, body, content_type)
        self.assertEqual(headers['Content-Type'], 'application/json')
        return status, json.loads(raw)

    def test_a_query_that_does_not_compile_is_the_engines_compilation_error(self):
        status, body = self.error('/api/pure/v1/execution/execute?serializationFormat=ARROW_IPC',
                                  self.execute_input(self.query('->select(~[nope])')))
        self.assertEqual((status, body['errorType'], body['status']), (500, 'COMPILATION', 'error'))

    def test_a_model_the_engine_does_not_serve_is_refused(self):
        other = self.table.model.replace('trades::DB', 'other::DB')
        status, body = self.error('/api/pure/v1/execution/execute?serializationFormat=ARROW_IPC',
                                  self.execute_input(self.query(''), model=other))
        self.assertEqual(status, 500)
        self.assertIn('the models it serves', body['message'])

    def test_execute_in_json_is_refused_naming_the_format_served(self):
        status, body = self.error('/api/pure/v1/execution/execute', self.execute_input(self.query('')))
        self.assertEqual(status, 500)
        self.assertIn('ARROW_IPC', body['message'])

    def test_the_database_refusing_is_answered_in_the_engines_shape(self):
        status, body = self.error('/api/pure/v1/execution/execute?serializationFormat=ARROW_IPC',
                                  self.execute_input(self.query('->extend(~[n: x|$x.desk->toOne()->parseInteger()])')))
        self.assertEqual((status, body['code'], body['status']), (500, -1, 'error'))
        self.assertNotIn('errorType', body)
        self.assertIn('Conversion', body['message'])

    def test_a_path_the_api_does_not_serve_is_404_in_the_engines_shape(self):
        status, body = self.error('/api/pure/v1/nope', '{}')
        self.assertEqual((status, body['message']), (404, 'no such legend-engine API in legend-lite: /api/pure/v1/nope'))


class Planes(Served):
    """Where the engine plans (R3): one planner, chosen once, which every call the compiler answers goes through, and
    which services.json names for the page."""

    def test_it_plans_on_pythons_own_planner_and_says_so(self):
        said = json.loads(self.server.engine.answer('GET', '/services.json', '', None).body)
        local = {'word': 'local', 'label': 'Plan local (in Python: the native library)'}
        self.assertEqual((said['plane'], said['planes']), (local, [local]))

    def test_every_call_goes_through_the_planner_it_was_made_with(self):
        asked = []

        class Counting(NativePlanner):
            def answer(self, path, query, body):
                asked.append(path)
                return super().answer(path, query, body)

            def execute_plan(self, body, models):
                asked.append('execute')
                return super().execute_plan(body, models)

        self.server.close()
        self.server = WebServer(Engine(self.frames, planner=Counting()))
        self.arrow(GROUP)
        self.post('/api/pure/v1/grammar/grammarToJson/lambda', '|1')
        self.assertEqual(asked, ['execute', '/api/pure/v1/grammar/grammarToJson/lambda'])


class PlanAndRunRoutes(Served):
    """PLANANDRUN ON PYTHON (docs/DATACUBE_ONE_APP_DESIGN_2026_10_11.md §15): a page's grid planned against the model
    Python holds and run on its DuckDB in one call (plan-and-run.json), the planner's other answers from Python's native
    library (planner.json) -- only over the models this engine serves, no handle crossing, never a dropped call."""

    def plan_and_run(self, steps, model=None):
        return self.post('/plan-and-run.json', json.dumps({
            'lambda': json.dumps(ll.parse('|' + self.table.accessor + steps)),
            'model': self.table.model if model is None else model,
            'runtime': self.table.runtime,
        }))

    def test_a_grid_is_planned_over_the_model_held_run_here_and_typed_by_its_plan(self):
        status, headers, body = self.plan_and_run(GROUP)
        self.assertEqual((status, headers['Content-Type']), (200, 'application/vnd.apache.arrow.stream'), body[:300])
        rows = pyarrow.ipc.open_stream(pa.py_buffer(body)).read_all()
        plan = rows.schema.metadata[b'legend.plan'].decode()
        self.assertTrue(plan.startswith('OK\n'), plan[:200])
        self.assertEqual(json.loads(plan[3:])['sql'], self.table.plan(GROUP).sql)
        self.assertEqual({f.name: f.metadata[b'legend.type'] for f in rows.schema}, {'desk': b'String', 'q': b'Float'})
        kept = self.df[self.df.qty > 5]
        self.assertEqual(rows.to_pylist(), [{'desk': d, 'q': q} for d, q in kept.groupby('desk')['qty'].sum().sort_index().items()])

    def test_a_model_it_does_not_serve_is_refused_and_nothing_runs(self):
        status, headers, body = self.plan_and_run(GROUP, model=self.table.model + '\n')
        self.assertEqual((status, headers['Content-Type']), (200, 'application/json'))
        self.assertTrue(json.loads(body)['plan'].startswith('ERR\nMODEL\n'), body)

    def test_sql_the_database_refuses_is_answered_with_its_plan(self):
        status, headers, body = self.plan_and_run("->extend(~[n: x|$x.desk->toOne()->parseInteger()])")
        self.assertEqual((status, headers['Content-Type']), (422, 'application/json'), body[:300])
        said = json.loads(body)
        self.assertTrue(said['plan'].startswith('OK\n'), said)
        self.assertIn('Exception', said['error'])

    def test_a_request_that_is_not_one_is_refused(self):
        for body in ('not json', '[]', json.dumps({'lambda': 1, 'model': '', 'runtime': ''})):
            self.assertEqual(self.post('/plan-and-run.json', body)[0], 400, body)
        for body in ('not json', '[]', json.dumps({'kind': []}), json.dumps({'kind': 'nope'})):
            self.assertEqual(self.post('/planner.json', body)[0], 400, body)

    def test_the_planner_answers_over_the_model_held_and_keeps_no_handle_of_the_page(self):
        lam = json.dumps(ll.parse('|' + self.table.accessor + GROUP))
        status, _, body = self.post('/planner.json', json.dumps({
            'kind': 'planJson', 'model': self.table.model, 'lambda': lam, 'runtime': self.table.runtime,
            # a handle the page names is never used: the one Python holds for that model is
            'handle': 'not-a-handle'}))
        said = json.loads(body)
        self.assertEqual(status, 200)
        self.assertTrue(said['ok'] and said['answer'].startswith('OK\n'), said)
        self.assertEqual(json.loads(said['answer'][3:])['sql'], self.table.plan(GROUP).sql)
        refused = json.loads(self.post('/planner.json', json.dumps({
            'kind': 'planJson', 'model': 'unserved', 'lambda': lam, 'runtime': self.table.runtime}))[2])
        self.assertTrue(refused['answer'].startswith('ERR\nMODEL\n'), refused)
        # holding and letting go are the page's own: Python holds nothing for a page
        for kind in ('hold', 'release'):
            self.assertEqual(self.post('/planner.json', json.dumps({'kind': kind, 'model': self.table.model, 'handle': '1'}))[0], 400)

    def test_both_need_the_token_and_neither_is_a_get(self):
        for path in ('/plan-and-run.json', '/planner.json'):
            self.assertEqual(self.post(path, '{}', headers={'Authorization': 'Bearer nope'})[0], 401, path)
            request = urllib.request.Request(self.server.url + path, headers={'Authorization': self.server.authorization})
            try:
                with urllib.request.urlopen(request, timeout=30) as r:
                    status = r.status
            except urllib.error.HTTPError as e:
                status = e.code
            self.assertNotEqual(status, 200, path)


class SavedPagesKept(unittest.TestCase):
    """The page's saved pages (step 2d): the browser's records, kept whole, one file each in this user's folder, the
    store's rules the page's own -- the engine answers only all, find, put and remove."""

    def setUp(self):
        self.place = tempfile.TemporaryDirectory()
        self.addCleanup(self.place.cleanup)
        self.folder = Path(self.place.name) / 'pages'
        self.engine = Engine(ll.Frames(), pages=SavedPages(self.folder))

    def call(self, **asked):
        return self.engine.answer('POST', '/saved-pages.json', '', json.dumps(asked))

    def test_all_find_put_remove_as_the_browser_keeps_them(self):
        record = {'id': 'p-1', 'name': 'Q3', 'content': '{"kind":"datacube.page","dec":12.30}', 'owner': 'this user',
                  'createdAt': 1, 'lastUpdatedAt': 2, 'lastOpenAt': 2}
        self.assertEqual(self.call(call='put', record=record).status, 204)
        self.assertEqual(json.loads(self.call(call='all').body), [record])
        # kept exactly as sent: its content the page's JSON text, never read here
        self.assertEqual(json.loads(self.call(call='find', id='p-1').body)['content'], record['content'])
        none = self.call(call='find', id='p-2')
        self.assertEqual((none.status, json.loads(none.body)), (200, None))
        self.assertEqual(self.call(call='remove', id='p-1').status, 204)
        self.assertEqual(json.loads(self.call(call='all').body), [])
        # every write whole: nothing half-written left beside the records
        self.assertEqual([p.name for p in self.folder.iterdir() if p.suffix == '.part'], [])

    def test_an_id_that_could_name_another_place_is_refused(self):
        for bad in ('../escape', '.lock', '', 'a/b', 'a\\b', 'C:x', 7, 'CON', 'nul', 'COM1', 'lpt9'):
            with self.subTest(id=bad):
                self.assertEqual(self.call(call='find', id=bad).status, 400)
                self.assertEqual(self.call(call='put', record={'id': bad, 'name': 'x', 'content': '{}'}).status, 400)
        self.assertEqual(self.call(call='put', record={'id': 'ok', 'name': 'x', 'content': {'not': 'text'}}).status, 400)
        self.assertEqual(self.call(call='rename').status, 400)

    def test_a_file_that_is_not_a_record_is_left_out_and_the_others_open(self):
        self.assertEqual(self.call(call='put', record={'id': 'good', 'name': 'G', 'content': '{}'}).status, 204)
        (self.folder / 'cut-short.json').write_text('{"id": "cut-sho', encoding='utf-8')
        (self.folder / 'a-list.json').write_text('[1, 2]', encoding='utf-8')
        self.assertEqual([r['id'] for r in json.loads(self.call(call='all').body)], ['good'])

    @unittest.skipIf(sys.platform == 'win32', 'a folder private to its owner is a POSIX mode')
    def test_the_folder_is_private_to_this_user(self):
        self.assertEqual(self.call(call='put', record={'id': 'p', 'name': 'P', 'content': '{}'}).status, 204)
        self.assertEqual(self.folder.stat().st_mode & 0o777, 0o700)

    def test_one_writer_at_a_time_across_its_holders(self):
        # another holder of the folder's lock (another notebook's process takes the same file lock) holds it: a put waits
        store = SavedPages(self.folder)
        order = []
        with store._locked():
            writer = threading.Thread(target=lambda: (store.put({'id': 'w', 'name': 'W', 'content': '{}'}), order.append('put')))
            writer.start()
            time.sleep(0.3)
            order.append('released')
        writer.join(timeout=10)
        self.assertEqual(order, ['released', 'put'])

    def test_the_folder_is_this_users_unless_pointed_elsewhere(self):
        from unittest import mock
        import importlib
        home_module = importlib.import_module('legend_lite._home')
        self.addCleanup(setattr, home_module, '_chosen', home_module._chosen)
        home_module._chosen = None
        with mock.patch.dict(os.environ, {'LEGEND_LITE_HOME': self.place.name}):
            self.assertEqual(ll.home(), Path(self.place.name).resolve())
            # a folder the process points itself at wins over the one it was started with
            chosen = Path(self.place.name) / 'chosen'
            self.assertEqual(ll.home(chosen), chosen.resolve())
            self.assertEqual(ll.home(), chosen.resolve())
        home_module._chosen = None
        with mock.patch.dict(os.environ, {}, clear=False):
            os.environ.pop('LEGEND_LITE_HOME', None)
            if sys.platform == 'darwin':
                self.assertEqual(ll.home(), Path.home() / 'Library' / 'Application Support' / 'legend-lite')
            elif sys.platform.startswith('linux'):
                os.environ['XDG_DATA_HOME'] = self.place.name
                self.assertEqual(ll.home(), Path(self.place.name) / 'legend-lite')
        said = json.loads(self.engine.answer('GET', '/services.json', '', None).body)
        self.assertEqual(said['pages'], {'where': str(self.folder), 'user': 'this user'})


class NoFiles(Served):
    """An engine given no DataCube code to read a page's file with (no headless): it says so, and takes none."""

    def test_it_says_it_takes_no_file_and_refuses_one(self):
        answer = self.server.engine.answer('GET', '/services.json', '', None)
        self.assertFalse(json.loads(answer.body)['files'])
        for path, asked in (('/file-part.json', {'at': 0, 'bytes': 'YQoxCg=='}), ('/file.json', {'upload': 'u', 'name': 'a.csv', 'table': 'a'})):
            status, _, body = self.post(path, json.dumps(asked))
            self.assertEqual(status, 404, path)
            self.assertIn(b'datacube extra', body)


class OnlyItsOwnPage(Served):
    def raw(self, method, path, headers, body=b''):
        port = int(self.server.url.rsplit(':', 1)[1])
        connection = http.client.HTTPConnection('127.0.0.1', port, timeout=30)
        try:
            connection.putrequest(method, path, skip_host=True, skip_accept_encoding=True)
            for name, value in headers.items():
                connection.putheader(name, value)
            connection.endheaders(body)
            r = connection.getresponse()
            return r.status, dict(r.getheaders()), r.read()
        finally:
            connection.close()

    def host(self):
        return self.server.url.removeprefix('http://')

    def test_a_request_without_the_token_is_refused(self):
        tree = json.dumps(ll.parse('|1'))
        for given in ({}, {'Authorization': 'Bearer nope'}, {'Authorization': self.server.token}):
            status, _, _ = self.raw('POST', '/api/pure/v1/grammar/jsonToGrammar/lambda',
                                    {'Host': self.host(), 'Content-Length': str(len(tree))} | given, tree.encode())
            self.assertEqual(status, 401, given)

    def test_a_host_that_is_not_this_machine_is_refused(self):
        port = self.server.url.rsplit(':', 1)[1]
        for host in ('evil.example:' + port, '127.0.0.1:1', 'localhost'):
            status, _, _ = self.raw('POST', '/api/pure/v1/grammar/jsonToGrammar/lambda',
                                    {'Host': host, 'Authorization': self.server.authorization, 'Content-Length': '2'}, b'{}')
            self.assertEqual(status, 403, host)
        status, _, _ = self.raw('POST', '/api/pure/v1/grammar/grammarToJson/lambda?returnSourceInformation=false',
                                {'Host': 'localhost:' + port, 'Authorization': self.server.authorization,
                                 'Content-Length': '2'}, b'|1')
        self.assertEqual(status, 200)

    def test_no_cross_origin_request_is_answered(self):
        status, headers, _ = self.raw('OPTIONS', '/api/pure/v1/execution/execute', {
            'Host': self.host(), 'Origin': 'http://evil.example', 'Access-Control-Request-Method': 'POST'})
        self.assertEqual(status, 405)
        self.assertFalse([h for h in headers if h.lower().startswith('access-control-')], headers)

    def test_a_body_past_the_limit_is_refused_unread(self):
        status, _, _ = self.raw('POST', '/api/pure/v1/grammar/grammarToJson/lambda',
                                {'Host': self.host(), 'Authorization': self.server.authorization,
                                 'Content-Length': str(64 * 1024 * 1024)})
        self.assertEqual(status, 413)

    def test_a_length_that_is_not_ascii_digits_or_a_body_not_utf8_is_answered(self):
        for length, body, status in (('²', b'', 411), ('2', b'\xff\xfe', 400)):
            got, _, _ = self.raw('POST', '/api/pure/v1/grammar/grammarToJson/lambda',
                                 {'Host': self.host(), 'Authorization': self.server.authorization,
                                  'Content-Length': length}, body)
            self.assertEqual(got, status, length)

    def test_a_refused_request_s_body_is_read_so_its_answer_arrives(self):
        body = b'x' * 200_000
        status, _, answer = self.raw('POST', '/api/pure/v1/grammar/grammarToJson/lambda',
                                     {'Host': self.host(), 'Content-Length': str(len(body))}, body)
        self.assertEqual(status, 401)
        self.assertIn(b"the engine's token", answer)

    def test_closed_it_answers_nothing(self):
        url = self.server.url
        self.server.close()
        self.server = WebServer(Engine(self.frames))  # tearDown closes this one
        request = urllib.request.Request(url + '/api/pure/v1/nope', b'{}', {'Authorization': 'Bearer x'})
        with self.assertRaises(urllib.error.URLError) as refused:
            urllib.request.urlopen(request, timeout=5)
        self.assertNotIsInstance(refused.exception, urllib.error.HTTPError)
        self.assertIsInstance(refused.exception.reason, ConnectionRefusedError)

    def test_a_connection_that_sends_nothing_neither_blocks_others_nor_close(self):
        port = int(self.server.url.rsplit(':', 1)[1])
        idle = [socket.create_connection(('127.0.0.1', port)) for _ in range(6)]
        try:
            # more idle connections than the compiler's pool has threads: a request is still answered
            tree = json.dumps(ll.parse('|1'))
            status, _, _ = self.raw('POST', '/api/pure/v1/grammar/jsonToGrammar/lambda',
                                    {'Host': self.host(), 'Authorization': self.server.authorization,
                                     'Content-Length': str(len(tree))}, tree.encode())
            self.assertEqual(status, 200)
            started = time.monotonic()
            self.server.close()
            self.assertLess(time.monotonic() - started, 5)
        finally:
            for connection in idle:
                connection.close()
            self.server = WebServer(Engine(self.frames))  # tearDown closes this one


class Site(unittest.TestCase):
    """The site's files (DataCube's built pages), at the engine's origin: to a local Host, under the site only."""

    def setUp(self):
        self.root = Path(tempfile.mkdtemp(dir=os.environ.get('TEST_TMPDIR')))
        (self.root / 'index.html').write_text('<!doctype html>cube')
        (self.root / 'vendor').mkdir()
        (self.root / 'vendor' / 'planner.wasm').write_bytes(b'\0asm')
        (self.root.parent / 'outside.txt').write_text('not the site')
        self.frames = ll.Frames()
        self.server = WebServer(Engine(self.frames, site=self.root))

    def tearDown(self):
        self.server.close()

    def get(self, path, host=None):
        port = int(self.server.url.rsplit(':', 1)[1])
        connection = http.client.HTTPConnection('127.0.0.1', port, timeout=30)
        try:
            connection.putrequest('GET', path, skip_host=True)
            connection.putheader('Host', host or f'127.0.0.1:{port}')
            connection.endheaders()
            r = connection.getresponse()
            return r.status, r.getheader('Content-Type'), r.read()
        finally:
            connection.close()

    def test_its_files_with_their_types(self):
        self.assertEqual(self.get('/'), (200, 'text/html; charset=utf-8', b'<!doctype html>cube'))
        self.assertEqual(self.get('/vendor/planner.wasm'), (200, 'application/wasm', b'\0asm'))

    def test_nothing_outside_it_nor_a_folder(self):
        for path in ('/../outside.txt', '/%2e%2e/outside.txt', '/vendor/', '/vendor', '/nope.js', '/vendor//planner.wasm',
                     '/C:/Windows/win.ini', '/C%3A/x', '/index.html%00.js'):
            self.assertEqual(self.get(path)[0], 404, path)

    def test_to_a_local_host_only(self):
        port = self.server.url.rsplit(':', 1)[1]
        self.assertEqual(self.get('/', host=f'evil.example:{port}')[0], 403)

    def test_an_engine_without_a_site_serves_no_file(self):
        bare = WebServer(Engine(self.frames))
        try:
            port = int(bare.url.rsplit(':', 1)[1])
            connection = http.client.HTTPConnection('127.0.0.1', port, timeout=30)
            connection.request('GET', '/')
            self.assertEqual(connection.getresponse().status, 404)
            connection.close()
        finally:
            bare.close()


class Cube(Served):
    """cube.json: what DataCube's engine page shows -- a frame's model, runtime and source, asked with the token."""

    def get(self, path, headers=None):
        request = urllib.request.Request(self.server.url + path, headers=headers or {})
        try:
            with urllib.request.urlopen(request, timeout=30) as r:
                return r.status, r.headers['Content-Type'], r.read()
        except urllib.error.HTTPError as e:
            return e.code, e.headers['Content-Type'], e.read()

    def test_a_frame_s_cube_as_it_is_now(self):
        status, content_type, body = self.get('/cube.json?table=trades', {'Authorization': self.server.authorization})
        self.assertEqual((status, content_type), (200, 'application/json'))
        self.assertEqual(json.loads(body), {'title': 'trades', 'model': self.table.model, 'runtime': self.table.runtime,
                                            'source': self.table.source, 'version': 0})
        # a Live frame read again: a new column, a new model
        self.df['extra'] = 1
        _, _, body = self.get('/cube.json?table=TRADES', {'Authorization': self.server.authorization})
        self.assertIn('extra', json.loads(body)['model'])

    def test_asked_with_the_token_only_and_for_a_frame_it_serves(self):
        self.assertEqual(self.get('/cube.json?table=trades')[0], 401)
        self.assertEqual(self.get('/cube.json?table=nope', {'Authorization': self.server.authorization})[0], 404)


class Page(Cube):
    """page.json: a page it serves (Python's ll.Page; docs/DATACUBE_PYTHON_PAGES_DESIGN_2026_10_09.md) -- its document
    and its version -- and version.json for a page: its own version and each of its frames'."""

    DOC = {'kind': 'datacube.page', 'version': 4, 'name': 'Q3',
           'cubes': [{'id': 'grid-1', 'cube': {'source': {'_type': 'frame', 'name': 'trades', 'columns': []}}}],
           'views': [], 'sheets': []}

    def test_a_page_served_its_document_and_version_moving_when_served_again(self):
        auth = {'Authorization': self.server.authorization}
        self.assertEqual(self.get('/page.json?page=q3', auth)[0], 404)
        self.assertEqual(self.server.engine.serve_page('q3', self.DOC), 1)
        status, content_type, body = self.get('/page.json?page=q3', auth)
        self.assertEqual((status, content_type), (200, 'application/json'))
        self.assertEqual(json.loads(body), {'version': 1, 'page': self.DOC})
        self.assertEqual(json.loads(self.get('/version.json?page=q3', auth)[2]), {'version': 1, 'frames': {'trades': 0}})
        # its frame changed: the frame's version moves, the page's not
        self.server.engine.changed('trades')
        self.assertEqual(json.loads(self.get('/version.json?page=q3', auth)[2]), {'version': 1, 'frames': {'trades': 1}})
        # served again: its version moves
        self.assertEqual(self.server.engine.serve_page('q3', {**self.DOC, 'name': 'Q4'}), 2)
        self.assertEqual(json.loads(self.get('/page.json?page=q3', auth)[2])['page']['name'], 'Q4')
        self.server.engine.close_page('q3')
        self.assertEqual(self.get('/version.json?page=q3', auth)[0], 404)

    def test_a_page_asked_with_the_token_only(self):
        self.server.engine.serve_page('q3', self.DOC)
        self.assertEqual(self.get('/page.json?page=q3')[0], 401)
        self.assertEqual(self.get('/version.json?page=q3')[0], 401)

    def test_what_the_open_page_says_is_kept_only_for_the_version_it_opened(self):
        engine = self.server.engine
        engine.serve_page('q3', self.DOC)
        said = json.dumps({**self.DOC, 'name': 'Renamed there'})
        self.assertEqual(engine.answer('POST', '/page.json', 'page=q3', said).status, 400, 'no version named')
        self.assertEqual(engine.answer('POST', '/page.json', 'page=q3&version=1', said).status, 204)
        self.assertEqual(engine.read_page('q3')['name'], 'Renamed there')
        # Python served it again: what the page says of version 1 is of a document replaced, and refused
        engine.serve_page('q3', {**self.DOC, 'name': 'Q4'})
        self.assertIsNone(engine.read_page('q3'))
        self.assertEqual(engine.answer('POST', '/page.json', 'page=q3&version=1', said).status, 409)
        self.assertIsNone(engine.read_page('q3'))
        self.assertEqual(engine.answer('POST', '/page.json', 'page=nope&version=1', said).status, 404)

    def test_a_pages_numbers_exact_both_ways(self):
        # a calculated column's 12.30 is 12.30, an integer past 2**53 every digit (the protocol's exact JSON)
        engine = self.server.engine
        lambda_ = {'_type': 'lambda', 'body': [{'_type': 'decimal', 'value': Decimal('12.30')},
                                               {'_type': 'integer', 'value': 2 ** 70}], 'parameters': []}
        engine.serve_page('q3', {**self.DOC, 'lambda': lambda_})
        body = self.get('/page.json?page=q3', {'Authorization': self.server.authorization})[2].decode('utf-8')
        self.assertIn('12.30', body)
        self.assertIn(str(2 ** 70), body)
        said = ('{"kind": "datacube.page", "version": 4, "name": "Q3", "cubes": [], "views": [], "sheets": [],'
                ' "lambda": {"_type": "lambda", "body": [{"_type": "decimal", "value": 12.30}]}}')
        self.assertEqual(engine.answer('POST', '/page.json', 'page=q3&version=1', said).status, 204)
        self.assertEqual(str(engine.read_page('q3')['lambda']['body'][0]['value']), '12.30')
