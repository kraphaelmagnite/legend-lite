"""D5b's PROBES (docs/ONE_PLATFORM_PROGRAM_2026_10_12.md D5b): what a snap's two queries are on both planners, and
whether a snap's copy must convert a zoned timestamp.

1. The count of a pull, as Pure, planned by legend-lite (the native library, over the trading demo's own rows) and by
   legend-engine (a running 4.145 at 127.0.0.1:6300, over the demo's H2): `pull->size()`, and the window counts over
   the whole result that were weighed against it (a column's skips its NULLs; a constant's counts every row).
2. A zoned timestamp pulled from a warehouse's DuckDB as Arrow and copied into a UTC session's DuckDB, as is and
   converted as before D5b: the cube's kinds of query planned by legend-lite and run on the warehouse and on both
   copies, their answers by wall time and their result types compared.

Run from the repository's root after `bazel build --remote_download_all //native:compiler`, legend-engine listening:
  LEGEND_LITE_LIBRARY=<bazel-bin/native's libcompiler> PYTHONPATH=python python3 docs/one-platform/evidence/d5b-snap-queries.py
Measured 2026-10-13: d5b-snap-queries.out.txt.
"""

import datetime
import json
import urllib.request
from pathlib import Path

import duckdb

from legend_lite import compiler
from legend_lite.projects import ProjectDatabases

MODELS = Path.cwd() / 'query/demo/models'
DOMAIN = (MODELS / 'trading.pure').read_text(encoding='utf-8')
ON_DUCKDB = DOMAIN + '\n' + (MODELS / 'runtime-duckdb.pure').read_text(encoding='utf-8') + '\n' + (
    MODELS / 'trading-data.pure').read_text(encoding='utf-8')
ON_H2 = DOMAIN + '\n' + (MODELS / 'runtime-h2.pure').read_text(encoding='utf-8')
PULL = '|#>{demo::trading::DB.TRADING.TRADE}#->select(~[TRADE_ID, TRADER_ID])'
COUNTS = {
    "the pull's size": PULL + '->size()->from(demo::trading::Runtime)',
    'a window count of a column (TRADER_ID has a NULL)': PULL + '->extend(over([]), ~n:{p,w,r|$r.TRADER_ID}:y|$y->size())'
                                                         '->from(demo::trading::Runtime)',
    'a window count of a constant': PULL + '->extend(over([]), ~n:{p,w,r|1}:y|$y->size())->from(demo::trading::Runtime)',
}


def counts() -> None:
    print("== 1. a pull's count, on both planners (12 trades, one TRADER_ID NULL)")
    projects = ProjectDatabases(duckdb.connect())
    for label, query in COUNTS.items():
        with projects.using(ON_DUCKDB) as project:
            plan = project.held.plan(compiler.parse(query), 'demo::trading::Runtime')
            rows = project.cursor.execute(plan.sql).fetchall()
        lite = sorted({r[-1] for r in rows})
        body = json.dumps({'clientVersion': 'vX_X_X', 'function': compiler.parse(query),
                           'model': {'_type': 'text', 'code': ON_H2}, 'context': {'_type': 'BaseExecutionContext'}}).encode()
        request = urllib.request.Request('http://127.0.0.1:6300/api/pure/v1/execution/execute', body,
                                         {'Content-Type': 'application/json'})
        with urllib.request.urlopen(request, timeout=120) as answer:
            said = json.loads(answer.read())
        engine_sql = next(a['sql'] for a in said['activities'] if 'sql' in a)
        engine = sorted({row['values'][-1] for row in said['result']['rows']})
        print(f'{label}:\n  legend-lite   {plan.sql.replace(chr(10), " ")}  -> {lite}\n'
              f'  legend-engine {engine_sql}  -> {engine}')


ZONED_MODEL = """###Relational
Database z::DB ( Table T (region VARCHAR(8) NOT NULL, at TIMESTAMP NOT NULL) )

###Connection
RelationalDatabaseConnection z::Conn { type: DuckDB; specification: DuckDB { }; auth: Test; }

###Runtime
Runtime z::RT { mappings: []; connections: [ z::DB: [ c: z::Conn ] ]; }
"""
ZONED_QUERIES = {
    'every row': '|#>{z::DB.T}#->select(~[region, at])->sort(~at->ascending())',
    'filter by time': "|#>{z::DB.T}#->filter(r|$r.at > %2024-03-10T08:00:00)->select(~[region, at])->sort(~at->ascending())",
    'date parts': '|#>{z::DB.T}#->extend(~[y:r|$r.at->year(), m:r|$r.at->monthNumber(), d:r|$r.at->dayOfMonth(), '
                  'h:r|$r.at->hour()])->select(~[region, y, m, d, h])->sort(~region->ascending())',
    'group by day': '|#>{z::DB.T}#->extend(~day:r|$r.at->datePart())->groupBy(~[day], ~[n:r|$r.region:y|$y->count()])'
                    '->sort(~day->ascending())',
    'max and min': '|#>{z::DB.T}#->groupBy(~[region], ~[hi:r|$r.at:y|$y->max(), lo:r|$r.at:y|$y->min()])'
                   '->sort(~region->ascending())',
}


def zoned() -> None:
    print('== 2. a zoned timestamp, copied as is and converted, against the warehouse it came from')

    def utc() -> duckdb.DuckDBPyConnection:
        c = duckdb.connect()
        c.execute("SET TimeZone='UTC'")
        return c
    warehouse, as_is, converted = utc(), utc(), utc()
    warehouse.execute("""CREATE TABLE T AS SELECT * FROM (VALUES
      ('EMEA', TIMESTAMPTZ '2024-01-02 03:04:05.123456+02'), ('AMER', TIMESTAMPTZ '2024-03-10 01:30:00-05'),
      ('AMER', TIMESTAMPTZ '2024-03-10 03:30:00-04'), ('APAC', TIMESTAMPTZ '2024-12-31 23:59:59.999999+09'),
      ('EMEA', TIMESTAMPTZ '2024-10-27 01:30:00+00')) t(region, "at")""")
    pulled = warehouse.execute('SELECT region, "at" FROM T').to_arrow_table()
    print('the pull, as Arrow:', pulled.schema.field('at').type)
    for tab, select in ((as_is, 'SELECT * FROM pulled'),
                        (converted, 'SELECT * REPLACE (CAST(timezone(\'UTC\', "at") AS TIMESTAMP) AS "at") FROM pulled')):
        tab.register('pulled', pulled)
        tab.execute(f'CREATE TABLE T AS {select}')
        tab.unregister('pulled')

    def wall(v: object) -> object:
        return v.astimezone(datetime.timezone.utc).replace(tzinfo=None) if isinstance(v, datetime.datetime) and v.tzinfo else v

    def rows(table) -> list:
        return [tuple(wall(v) for v in r.values()) for r in table.to_pylist()]
    for label, query in ZONED_QUERIES.items():
        sql = compiler.plan(ZONED_MODEL, compiler.parse(query), 'z::RT').sql
        live, a, b = (c.execute(sql).to_arrow_table() for c in (warehouse, as_is, converted))
        types = lambda t: {f.name: str(f.type) for f in t.schema}
        print(f'{label}: as is {"=" if rows(a) == rows(live) else "!="} live, converted '
              f'{"=" if rows(b) == rows(live) else "!="} live; types live {types(live)}, as is {types(a)}, '
              f'converted {types(b)}')


if __name__ == '__main__':
    counts()
    zoned()
