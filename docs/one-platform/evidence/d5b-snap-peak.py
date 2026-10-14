"""D5b'S SNAP PEAK (docs/ONE_PLATFORM_PROGRAM_2026_10_12.md D5b; Studio's re-review): how much memory a re-snap holds at
its peak, at MAX_SNAP_ROWS (engine-client/src/snap.ts: 10,000,000 rows), in the two ways the landed rows can become the
snap -- COPIED (the landing copied into the snap's table by the `relation` operation, the old snap replaced once the
copy is made, the landing then dropped) or SWAPPED (in one transaction: the old snap dropped, the landing renamed to the
snap's name). Each runs in a process of its own on an in-memory DuckDB with spilling off, so what it holds is in memory;
the old snap and the landing are made first (the landing as an Arrow load leaves it), then the step is measured: the
process's peak resident memory (ru_maxrss, a high-water mark) before and after it, and DuckDB's own count of what it
holds (duckdb_memory()) after it. The shape is the worst case the limit was sized on: a UUID text beside numbers, a
timestamp and a short text. It also checks the swap keeps the old snap when the swap itself is refused after its
drop (a rollback).

Run from the repository's root, on a Python with duckdb:
  python3 docs/one-platform/evidence/d5b-snap-peak.py
Measured 2026-10-13 (Apple silicon laptop, duckdb 1.5.5): d5b-snap-peak.out.txt.
"""

import json
import resource
import subprocess
import sys

ROWS = 10_000_000
SHAPE = ("SELECT i::BIGINT AS id, uuid()::VARCHAR AS ref, 'T' || (i % 500)::VARCHAR AS ticker, "
         "(i % 1000)::INTEGER AS qty, random() * 100 AS price, TIMESTAMP '2026-01-01' + to_seconds(i) AS at "
         "FROM range({rows}) t(i)")


def peak_mb() -> float:
    # macOS reports bytes, Linux kilobytes
    raw = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
    return raw / (1024 * 1024) if sys.platform == 'darwin' else raw / 1024


def held_mb(con) -> float:
    return con.execute('SELECT sum(memory_usage_bytes) FROM duckdb_memory()').fetchone()[0] / (1024 * 1024)


def one(mode: str) -> dict:
    import duckdb
    con = duckdb.connect()
    con.execute("SET temp_directory = ''")
    con.execute(f'CREATE TABLE snap AS {SHAPE.format(rows=ROWS)}')
    con.execute(f'CREATE TABLE snap__pulled AS {SHAPE.format(rows=ROWS)}')
    one_table = held_mb(con) / 2
    before = peak_mb()
    if mode == 'copied':
        con.execute('CREATE OR REPLACE TABLE snap AS SELECT * FROM snap__pulled')
        con.execute('DROP TABLE snap__pulled')
    else:
        con.execute('BEGIN')
        con.execute('DROP TABLE IF EXISTS snap')
        con.execute('ALTER TABLE snap__pulled RENAME TO snap')
        con.execute('COMMIT')
    after = peak_mb()
    rows = con.execute('SELECT count(*) FROM snap').fetchone()[0]
    left = con.execute("SELECT count(*) FROM duckdb_tables() WHERE table_name = 'snap__pulled'").fetchone()[0]
    return {'mode': mode, 'one table (MB)': round(one_table), 'peak before the step (MB)': round(before),
            'peak after the step (MB)': round(after), 'held after (MB)': round(held_mb(con)), 'rows': rows,
            'landing left': left}


def refused_swap_keeps_the_old_snap() -> dict:
    import duckdb
    con = duckdb.connect()
    con.execute('CREATE TABLE snap AS SELECT 1 AS old')
    # no landing: a pull cut short left none (snap.ts drops it), so the rename is refused after the drop
    refused = None
    try:
        con.execute('BEGIN')
        con.execute('DROP TABLE IF EXISTS snap')
        con.execute('ALTER TABLE snap__pulled RENAME TO snap')
        con.execute('COMMIT')
    except duckdb.Error as e:
        refused = type(e).__name__
        con.execute('ROLLBACK')
    return {'refused with': refused, 'the old snap after': con.execute('SELECT * FROM snap').fetchall()}


if __name__ == '__main__':
    if len(sys.argv) > 1:
        print(json.dumps(one(sys.argv[1]) if sys.argv[1] != 'refused' else refused_swap_keeps_the_old_snap()))
    else:
        for mode in ('copied', 'swapped', 'refused'):
            out = subprocess.run([sys.executable, __file__, mode], capture_output=True, text=True, check=True).stdout
            print(out.strip())
