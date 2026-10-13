"""A server planning for Python's pages (docs/ONE_PLATFORM_PROGRAM_2026_10_12.md G1; legend_lite/upstream.py): the twin
of the tab's planner on a server, held to the same cases (datacube/test/fixtures/upstream-plans.json, which the tab's
datacube/test/upstream-plans.test.ts reads too), then against a legend-lite server this test starts: a page's grid
planned there and run on Python's DuckDB answers as Python's own planner does, and Python's own queries plan there too.
Where a session plans is chosen once."""

import json
import os
import subprocess
import threading
import unittest

import pyarrow as pa
import pyarrow.ipc

import legend_lite as ll
from legend_lite import _json, upstream
from legend_lite.compiler import LegendError
from legend_lite.datacube import _Session
from legend_lite.engine import Engine, NativePlanner
from python.runfiles import runfiles

CORPUS = _json.loads(open(os.environ['LEGEND_LITE_UPSTREAM_PLANS'], encoding='utf-8').read())


class Cases(unittest.TestCase):
    """The shared cases: each request written exactly so, each plan read so, as the tab's planner on a server does."""

    def test_each_request_is_the_cases_exactly(self):
        for case in CORPUS['requests']:
            with self.subTest(case=case['name']):
                built = upstream.generate_plan_request(case['query'], case['model'], case['runtime'])
                # the bytes the tab's client sends (the TypeScript test holds it to the same text)
                self.assertEqual(_json.protocol(built), case['text'])

    def test_a_query_that_is_not_one_relation_expression_is_refused(self):
        for case in CORPUS['refused']:
            with self.subTest(case=case['name']):
                with self.assertRaisesRegex(ValueError, 'one relation expression with no parameters'):
                    upstream.from_runtime(case['query'], 'x::R')

    def test_each_plan_is_read_as_its_sql_and_its_columns(self):
        for case in CORPUS['plans']:
            with self.subTest(case=case['name']):
                plan = upstream.read_plan(_json.dumps(case['answer']), case['database'])
                self.assertEqual(plan.sql, case['sql'])
                self.assertEqual([[c.name, c.type] for c in plan.columns], case['columns'])
                self.assertEqual([c.sql_type for c in plan.columns], case['sqlTypes'])

    def test_a_refusal_is_raised_in_the_servers_words(self):
        for case in CORPUS['refusals']:
            with self.subTest(case=case['name']):
                with self.assertRaises(LegendError) as raised:
                    upstream.read_plan(_json.dumps(case['answer']))
                self.assertEqual(raised.exception.message, case['message'])


class RunHere(unittest.TestCase):
    """A server's plan is run here as one query: DuckDB runs every statement of a text, so a server's SQL could
    otherwise write a file, attach a database or replace a frame's view (the audit of G1)."""

    def test_more_than_one_statement_or_another_kind_is_refused(self):
        planner = upstream.RemotePlanner('remote', 'http://127.0.0.1:1')
        plan = CORPUS['plans'][0]['answer']
        for sql, said in (("SELECT 1; COPY (SELECT 1) TO 'x.csv'", "['SELECT', 'COPY']"),
                          ("ATTACH 'x.db' AS x", "['ATTACH']"), ('CREATE OR REPLACE VIEW trades AS SELECT 1', "['CREATE']")):
            with self.subTest(sql=sql):
                answer = json.loads(json.dumps(plan))
                answer['rootExecutionNode']['executionNodes'][0]['sqlQuery'] = sql
                with self.assertRaisesRegex(LegendError, 'run here as one query') as raised:
                    planner.read(json.dumps(answer))
                self.assertIn(said, raised.exception.message)
        self.assertEqual(planner.read(json.dumps(plan)).sql, CORPUS['plans'][0]['sql'])

    def test_execute_is_not_sent_to_the_server(self):
        planner = upstream.RemotePlanner('remote', 'http://127.0.0.1:1')
        answer = planner.answer('/api/pure/v1/execution/execute', '', '{}')
        self.assertEqual(answer.status, 500)
        self.assertIn('plan-and-run', answer.body)


class OnAServer(unittest.TestCase):
    """legend-lite's server, started here on a free port: Python's pages and Python's own queries planned there."""

    @classmethod
    def setUpClass(cls):
        files = runfiles.Create()
        cls.server = subprocess.Popen([os.environ['LEGEND_LITE_SERVER'], '0'], env={**os.environ, **files.EnvVars()},
                                      stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        port, said = None, []
        for line in cls.server.stdout:
            said.append(line)
            if 'started on port' in line:
                port = int(line.rsplit('started on port', 1)[1].split()[0])
                break
        if port is None:
            raise RuntimeError('the server did not start:\n' + ''.join(said))
        # its output read on, so it never blocks writing it
        threading.Thread(target=lambda: [None for _ in cls.server.stdout], daemon=True).start()
        cls.url = f'http://127.0.0.1:{port}'

    @classmethod
    def tearDownClass(cls):
        cls.server.terminate()
        cls.server.wait(timeout=30)

    def setUp(self):
        self.frames = ll.Frames()
        self.addCleanup(self.frames.close)
        self.frames.register('trades', pa.table({'desk': ['FX', 'EQ', 'FX'], 'qty': [1, 2, 4]}), 'live')
        self.table = self.frames['trades']
        self.query = ll.parse('|' + self.table.accessor
                              + '->groupBy(~[desk], ~[total: x|$x.qty : y|$y->sum()])->sort(~desk->ascending())')

    def plan_and_run(self, planner):
        engine = Engine(self.frames, planner=planner)
        body = json.dumps({'lambda': _json.dumps(self.query), 'model': self.table.model, 'runtime': self.table.runtime})
        return engine.answer('POST', '/plan-and-run.json', '', body)

    def test_the_server_answers_as_the_tab_asks(self):
        self.assertIsNone(upstream.RemotePlanner('remote', self.url).answering())

    def test_a_pages_grid_planned_on_the_server_answers_as_pythons_own_planner_does(self):
        rows = {}
        for planner in (NativePlanner(), upstream.RemotePlanner('remote', self.url)):
            with self.subTest(plane=planner.plane):
                answer = self.plan_and_run(planner)
                self.assertEqual(answer.status, 200, answer.body[:300])
                read = pyarrow.ipc.open_stream(answer.body).read_all()
                rows[planner.plane] = read.to_pylist()
                # the plan the page reads, in the shape its planner on this plane reads
                said = planner.read(read.schema.metadata[b'legend.plan'].decode('utf-8'))
                self.assertIn('group by', said.sql.lower())
                self.assertEqual([c.name for c in said.columns], ['desk', 'total'])
        self.assertEqual(rows['remote'], rows['local'])
        self.assertEqual(rows['local'], [{'desk': 'EQ', 'total': 2}, {'desk': 'FX', 'total': 5}])

    def test_a_model_that_reads_a_table_python_does_not_serve_is_refused_in_the_servers_shape(self):
        engine = Engine(self.frames, planner=upstream.RemotePlanner('remote', self.url))
        model = self.table.model.replace('trades', 'elsewhere')
        answer = engine.answer('POST', '/plan-and-run.json', '', json.dumps(
            {'lambda': _json.dumps(self.query), 'model': model, 'runtime': self.table.runtime}))
        self.assertEqual(answer.status, 200)
        with self.assertRaisesRegex(LegendError, 'plans only over the tables it serves'):
            upstream.read_plan(json.loads(answer.body)['plan'])

    def test_a_pages_other_calls_are_sent_on_to_the_server(self):
        planner = upstream.RemotePlanner('remote', self.url)
        request = json.dumps({'lambda': self.query, 'model': {'_type': 'text', 'code': self.table.model}})
        there = planner.answer('/api/pure/v1/compilation/lambdaRelationType', '', request)
        here = NativePlanner().answer('/api/pure/v1/compilation/lambdaRelationType', '', request)
        self.assertEqual((there.status, json.loads(there.body)), (here.status, json.loads(here.body)))

    def test_pythons_own_queries_plan_on_the_planner_the_frames_were_given(self):
        local = self.table.execute(self.query).to_pylist()
        self.frames.planner = upstream.RemotePlanner('remote', self.url)
        self.assertEqual(self.table.execute(self.query).to_pylist(), local)
        self.assertIn('group by', self.table.plan(self.query).sql.lower())


class ChosenOnce(unittest.TestCase):
    """Where a session plans (R3): chosen once, before it plans; a server that does not answer said, never substituted."""

    def test_a_server_that_does_not_answer_is_said(self):
        session = _Session()
        # a port nothing listens on: this test's own, closed again
        import socket
        with socket.socket() as s:
            s.bind(('127.0.0.1', 0))
            dead = f'http://127.0.0.1:{s.getsockname()[1]}'
        with self.assertRaisesRegex(ConnectionError, 'legend-lite is not answering .*nothing is planned elsewhere'):
            session.choose_planner('remote', dead)
        self.assertIsNone(session.frames.planner)

    def test_before_the_first_cube_the_last_choice_holds(self):
        session = _Session()
        session.choose_planner('local', None)
        session.choose_planner('local', None)
        # a server not answering is refused; the choice before it holds
        with self.assertRaises(ConnectionError):
            session.choose_planner('engine', 'http://127.0.0.1:1')
        self.assertIsNone(session.frames.planner)

    def test_an_engine_made_has_chosen_its_planner(self):
        from unittest import mock
        session = _Session()
        # an engine with no site of DataCube's: this test carries none
        with mock.patch('legend_lite.datacube._site', return_value=None):
            self.assertIsInstance(session.engine().planner, NativePlanner)
        with self.assertRaisesRegex(ValueError, 'plans locally, chosen once'):
            session.choose_planner('remote', 'http://127.0.0.1:1')

    def test_the_words_and_addresses_are_checked(self):
        session = _Session()
        for word, server, said in (('native', None, "'local', 'remote' or 'engine'"),
                                   ('engine', None, 'give its address'),
                                   ('local', 'http://x', 'at no server'),
                                   ('remote', 'file:///etc', 'http:// or https://')):
            with self.subTest(word=word, server=server):
                with self.assertRaisesRegex(ValueError, said):
                    session.choose_planner(word, server)


if __name__ == '__main__':
    unittest.main()
