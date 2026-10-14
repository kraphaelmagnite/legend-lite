"""C5's MEASUREMENTS (docs/ONE_PLATFORM_PROGRAM_2026_10_12.md C5, owed until D5b): a project's own database -- made and
filled the first time a query over its model asks, then that query, the first time and repeated -- on Python's DuckDB
(legend_lite.projects, the engine's own keeper) and on legend-lite's server with several project versions open (its
pure/v1 execute, as Query's page asks it). Three models: the trading demo (query/demo/models: six tables, 39 rows), and
a party model whose one table holds 1,000 and 100,000 rows (a Data element as a person writes one, CSV text).

Run from the repository's root after `bazel build --remote_download_all //native:compiler //core:server` (Bazel 9 leaves
a cache hit's files out of bazel-bin without the flag), on a Python with duckdb and pyarrow:
  LEGEND_LITE_LIBRARY=<bazel-bin/native's libcompiler> PYTHONPATH=python python3 \\
    docs/one-platform/evidence/c5-timings.py bazel-bin/core/server
It prints one line per measure (milliseconds, the median of five runs where a run repeats).
Measured 2026-10-13 (Apple silicon laptop): c5-timings.out.txt; the runbook's C5 Built notes quote it.
"""

import json
import statistics
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

import duckdb

from legend_lite import compiler
from legend_lite.projects import ProjectDatabases

ROOT = Path.cwd()
TRADING = '\n'.join((ROOT / 'query/demo/models' / f).read_text(encoding='utf-8')
                    for f in ('trading.pure', 'runtime-duckdb.pure', 'trading-data.pure'))
TRADING_QUERY = ('|demo::trading::Trade.all()->project(~[tradeId: x|$x.tradeId, trader: x|$x.trader.lastName, '
                 'ticker: x|$x.product.ticker])->from(demo::trading::TradingMapping, demo::trading::Runtime)')


def party(rows: int, mark: str = '') -> str:
    """A party model whose table holds ``rows`` rows (``mark`` a row's name changed: another version's rows)."""
    csv = 'ID,NAME,COUNTRY\\n' + ''.join(f'{i},Party {i}{mark if i == 1 else ""},{"GB" if i % 2 else "US"}\\n'
                                         for i in range(1, rows + 1))
    return f"""###Relational
Database demo::party::PartyDB
(
  Schema PARTY
  (
    Table PARTY (ID INTEGER PRIMARY KEY, NAME VARCHAR(200), COUNTRY VARCHAR(2))
  )
)

###Pure
Class demo::party::Party
{{
  id: Integer[1];
  name: String[1];
  country: String[1];
}}

###Mapping
Mapping demo::party::PartyMapping
(
  demo::party::Party: Relational
  {{
    ~mainTable [demo::party::PartyDB] PARTY.PARTY
    id: [demo::party::PartyDB] PARTY.PARTY.ID,
    name: [demo::party::PartyDB] PARTY.PARTY.NAME,
    country: [demo::party::PartyDB] PARTY.PARTY.COUNTRY
  }}
)

###Connection
RelationalDatabaseConnection demo::party::Connection
{{
  store: demo::party::PartyDB;
  type: DuckDB;
  specification: DuckDB {{ }};
  auth: Test;
}}

###Runtime
Runtime demo::party::Runtime
{{
  mappings: [ demo::party::PartyMapping ];
  connections: [ demo::party::PartyDB: [ db: demo::party::Connection ] ];
}}

###Data
Data demo::party::PartyData
{{
  Relational
  #{{
    PARTY.PARTY:
      '{csv}';
  }}#
}}
"""


PARTY_QUERY = ("|demo::party::Party.all()->filter(p|$p.country == 'GB')->project(~[id: p|$p.id, name: p|$p.name])"
               '->from(demo::party::PartyMapping, demo::party::Runtime)')

MODELS = [('trading (6 tables, 39 rows)', TRADING, TRADING_QUERY, 'demo::trading::Runtime'),
          ('party, 1,000 rows', party(1_000), PARTY_QUERY, 'demo::party::Runtime'),
          ('party, 100,000 rows', party(100_000), PARTY_QUERY, 'demo::party::Runtime')]


def ms(seconds: float) -> str:
    return f'{seconds * 1000:,.1f} ms'


def on_python() -> None:
    print('== Python (legend_lite.projects on Python\'s DuckDB)')
    for label, model, query, runtime in MODELS:
        connection = duckdb.connect()
        projects = ProjectDatabases(connection)
        tree = compiler.parse(query)
        started = time.perf_counter()
        with projects.using(model) as project:
            filled = time.perf_counter() - started
            plan = project.held.plan(tree, runtime)
            begun = time.perf_counter()
            rows = project.cursor.execute(plan.sql).fetchall()
            first = time.perf_counter() - begun
        repeats, reopened = [], []
        for _ in range(5):
            begun = time.perf_counter()
            with projects.using(model) as project:
                opened = time.perf_counter()
                plan = project.held.plan(tree, runtime)
                project.cursor.execute(plan.sql).fetchall()
                repeats.append(time.perf_counter() - opened)
            reopened.append(opened - begun)
        print(f'{label}: made and filled {ms(filled)} (its name read from the model, its tables filled, the model held '
              f'for its queries); first query {ms(first)} ({len(rows):,} rows); '
              f'repeated (plan + run) {ms(statistics.median(repeats))}; opened again (its database kept) '
              f'{ms(statistics.median(reopened))}')
        connection.close()


def on_the_server(server: str) -> None:
    print("== legend-lite's server (pure/v1 execute, as Query's page asks it)")
    process = subprocess.Popen([server, '0'], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    try:
        port = None
        for line in process.stdout:
            if 'started on port' in line:
                port = int(line.rsplit(' ', 1)[1])
                break
        if port is None:
            raise SystemExit('the server did not say its port')
        url = f'http://127.0.0.1:{port}/api/pure/v1/execution/execute'

        def execute(model: str, query: str) -> float:
            body = json.dumps({'function': compiler.parse(query), 'model': {'_type': 'text', 'code': model}}).encode()
            request = urllib.request.Request(url, body, {'Content-Type': 'application/json'})
            begun = time.perf_counter()
            with urllib.request.urlopen(request) as answer:
                answer.read()
            return time.perf_counter() - begun

        for label, model, query, _ in MODELS:
            first = execute(model, query)
            repeated = statistics.median(execute(model, query) for _ in range(5))
            print(f'{label}: first execute (model compiled, its database made and filled, the query run) {ms(first)}; '
                  f'repeated {ms(repeated)}')
        # several versions of one project open at once, each its own rows (an edited row is another database)
        versions = [party(1_000, f' v{v}') for v in range(8)]
        firsts = [execute(model, PARTY_QUERY) for model in versions]
        again = [execute(model, PARTY_QUERY) for model in versions]
        print(f'8 versions of party (1,000 rows each) open at once: first execute, median {ms(statistics.median(firsts))}; '
              f'each again, median {ms(statistics.median(again))}')
    finally:
        process.terminate()
        process.wait()


if __name__ == '__main__':
    on_python()
    if len(sys.argv) > 1:
        on_the_server(sys.argv[1])
