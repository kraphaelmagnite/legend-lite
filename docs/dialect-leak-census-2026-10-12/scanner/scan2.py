"""THE DIALECT-LEAK CENSUS: every tracked source file, lexed into code / comments / string literals, every hit of
every leak pattern recorded (no sampling): the census's second pass (the README's pass 2).
Output: hits2.tsv (zone, module, file, line, category, match, source). usage: python3 -I scan2.py <repo root> <out dir>"""
import os
import re
import subprocess
import sys

ROOT, OUT = sys.argv[1], sys.argv[2]
files = subprocess.run(['git', '-C', ROOT, 'ls-files'], capture_output=True, text=True, check=True).stdout.split('\n')
SRC = re.compile(r'\.(java|ts|tsx|js|mjs|py|kt)$')

# ---- the lexer: (kind, text, line) segments; kind in code / comment / string ----
def lex_c(text, ts):
    """C-like (Java, TS/JS): // and /* */ comments; "..." '...' (TS/JS) `...` (TS/JS) strings; Java text blocks."""
    out, i, n, line = [], 0, len(text), 1
    buf, buf_line, kind = [], 1, 'code'
    def flush(k):
        nonlocal buf
        if buf:
            out.append((k, ''.join(buf), buf_line))
            buf = []
    while i < n:
        c = text[i]
        if text.startswith('//', i):
            flush('code'); buf_line = line
            j = text.find('\n', i); j = n if j < 0 else j
            out.append(('comment', text[i:j], line)); i = j; continue
        if text.startswith('/*', i):
            flush('code')
            j = text.find('*/', i + 2); j = n if j < 0 else j + 2
            out.append(('comment', text[i:j], line)); line += text.count('\n', i, j); i = j; buf_line = line; continue
        if text.startswith('"""', i) and not ts:
            flush('code')
            j = text.find('"""', i + 3); j = n if j < 0 else j + 3
            out.append(('string', text[i + 3:j - 3], line)); line += text.count('\n', i, j); i = j; buf_line = line
            continue
        if c == '"' or (ts and c in "'`") or (not ts and c == "'"):
            flush('code')
            q, j = c, i + 1
            while j < n and text[j] != q:
                if text[j] == '\\':
                    j += 1
                elif text[j] == '\n' and q != '`':
                    break
                j += 1
            s = text[i + 1:j]
            if not (not ts and q == "'"):   # a Java char literal is no string
                out.append(('string', s, line))
            line += text.count('\n', i, j + 1); i = j + 1; buf_line = line; continue
        if not buf:
            buf_line = line
        buf.append(c)
        if c == '\n':
            line += 1
        i += 1
    flush('code')
    return out

def lex_py(text):
    out, i, n, line = [], 0, len(text), 1
    buf, buf_line = [], 1
    def flush():
        nonlocal buf
        if buf:
            out.append(('code', ''.join(buf), buf_line)); buf = []
    while i < n:
        c = text[i]
        if c == '#':
            flush(); j = text.find('\n', i); j = n if j < 0 else j
            out.append(('comment', text[i:j], line)); i = j; buf_line = line; continue
        for q in ('"""', "'''", '"', "'"):
            if text.startswith(q, i):
                flush(); j = i + len(q)
                while j < n and not text.startswith(q, j):
                    if text[j] == '\\':
                        j += 1
                    elif text[j] == '\n' and len(q) == 1:
                        break
                    j += 1
                out.append(('string', text[i + len(q):j], line)); line += text.count('\n', i, j + len(q))
                i = j + len(q); buf_line = line; break
        else:
            if not buf:
                buf_line = line
            buf.append(c)
            if c == '\n':
                line += 1
            i += 1
    flush()
    return out

DIALECT_CLASSES = ('AnsiSqlRenderer', 'DuckDb', 'H2Modern', 'H2', 'Postgres', 'Snowflake', 'Databricks', 'BigQuery',
                   'SQLite', 'MariaDb', 'MySql', 'Trino', 'ClickHouse', 'EngineStyleH2', 'EngineStyleDB2',
                   'EngineStyleComposite')
PRODUCTS = r'duck\s?db|postgres(?:ql)?|snowflake|databricks|big\s?query|sqlite|maria\s?db|mysql|trino|presto|' \
           r'click\s?house|\bh2\b|\bdb2\b|sybase|oracle|sql\s?server|redshift|athena|spanner|hive\b|memsql|' \
           r'singlestore|mongo|elastic'
SQL_HEAD = r'(?i)^\s*(select|insert\s+into|update\s+\w|delete\s+from|create\s|drop\s|alter\s|attach\s|detach\s|use\s|' \
           r'set\s|pragma\s|begin\b|commit\b|rollback\b|truncate\s|copy\s|call\s|with\s|values\s*\(|explain\s|' \
           r'describe\s|show\s|grant\s|merge\s|install\s|load\s)'
SQL_BODY = r'(?i)\b(select\s.+\sfrom\s|insert\s+into\s|create\s+(or\s+replace\s+)?(temp(orary)?\s+)?(table|view|schema|' \
           r'database|function|macro)\b|drop\s+(table|view|schema|database)\b|alter\s+table\b|cast\s*\(.+\sas\s|' \
           r'group\s+by\s|order\s+by\s|\bjoin\s.+\son\s)'
TYPES = r'\b(VARCHAR|BIGINT|HUGEINT|INTEGER|SMALLINT|TINYINT|DOUBLE(\s+PRECISION)?|DECIMAL|NUMERIC|TIMESTAMP(TZ)?|' \
        r'TIMESTAMP_NTZ|DATETIME|BOOLEAN|JSONB?|VARIANT|STRUCT<|ARRAY<|Nullable\(|LowCardinality|INT64|FLOAT64|' \
        r'STRING|TEXT|CLOB|BLOB|BYTEA|UUID)\b'
CODE_PATTERNS = [
    ('dialect-class', re.compile(r'\b(new\s+|instanceof\s+|\(\s*)(?:com\.legend\.sql\.dialect\.)?(' + '|'.join(DIALECT_CLASSES)
                                 + r')\b(\s*\(|\s+\w|\s*\)|\.class)')),
    ('dialect-class-ref', re.compile(r'\b(?:com\.legend\.sql\.dialect\.)(' + '|'.join(DIALECT_CLASSES) + r')\b')),
    ('database-type', re.compile(r'\bDatabaseType\.(\w+)')),
    ('driver-import', re.compile(r'\b(org\.duckdb|org\.h2\.|org\.postgresql|net\.snowflake|com\.databricks|com\.simba|'
                                 r'com\.google\.cloud\.bigquery|org\.sqlite|org\.mariadb|com\.mysql|io\.trino|'
                                 r'com\.clickhouse)')),
    ('jdbc-call', re.compile(r'\b(DriverManager\.getConnection|getDatabaseProductName|getDatabaseProductVersion|'
                             r'\.unwrap\(|\.isWrapperFor\(|setAutoCommit\(|\.duplicate\(\))')),
    ('raw-sql-rewrite', re.compile(r'\b(RawSqlBoundary|RawSqlAdapt|h2ToDuckDb|rawTarget|rawH2IsNative|adaptRaw|'
                                   r'splitStatements|RawSql\b)')),
    ('session-setup', re.compile(r'\b(sessionSetup|initSession|scriptAbort|\.script\(|failingStatement|'
                                 r'withSystemConnections|openSystem|sessionTable)\b')),
    ('product-name-code', re.compile(r'(?i)\b(' + PRODUCTS + r')\w*', re.I)),
]
STRING_PATTERNS = [
    ('sql-text-head', re.compile(SQL_HEAD)),
    ('sql-text-body', re.compile(SQL_BODY)),
    ('product-name-string', re.compile(r'(?i)(' + PRODUCTS + r')')),
    ('jdbc-url', re.compile(r'jdbc:\w+')),
    ('type-spelling', re.compile(TYPES)),
]


CODE_PATTERNS += [
    ('jdbc-types', re.compile(r'\b(java\.sql\.Types\.\w+|Types\.(?:BIGINT|INTEGER|VARCHAR|DECIMAL|NUMERIC|DOUBLE|FLOAT|REAL|TIMESTAMP\w*|DATE|TIME\w*|BOOLEAN|BIT|ARRAY|STRUCT|OTHER|JAVA_OBJECT|CLOB|BLOB|BINARY|VARBINARY|SMALLINT|TINYINT|CHAR|NCHAR|NVARCHAR|LONGVARCHAR)\b|getColumnType(?:Name)?\(|getMetaData\(\)|getSQLState\(\)|getErrorCode\(\))')),
    ('driver-object', re.compile(r'\b(PGobject|DuckDB\w+|JdbcConnection|SnowflakeConnection\w*|ClickHouse\w+|BigQuery\w+|DatabricksConnection)\b')),
    ('dialect-query', re.compile(r'\bdialect\s*(?:\(\s*\))?\s*\.\s*(?:getClass|name|toString|equals)\b|\bgetClass\(\)\.getSimpleName\(\)\s*\.equals')),
]
STRING_PATTERNS += [
    ('sql-fragment', re.compile(r'(?:\b(?:COALESCE|CASE\s+WHEN|CAST\(|TRY_CAST|NULLIF|ROW_NUMBER|OVER\s*\(|PARTITION\s+BY|UNION\s+ALL|LIMIT\s+\d|OFFSET\s+\d|IS\s+NOT\s+NULL|IS\s+NULL|INFORMATION_SCHEMA|information_schema|sqlite_master|pg_catalog|pg_\w+|duckdb_\w+|system\.\w+|ATTACH|DETACH|PRAGMA|INSTALL\s|CURRENT_DATE|CURRENT_TIMESTAMP|NOW\(\)|STRING_AGG|LISTAGG|GROUP_CONCAT|ARRAY_AGG|LIST_AGG|UNNEST|LATERAL|QUALIFY|ILIKE|REGEXP_\w+)\b|\s(?:FROM|WHERE|AND|OR|AS|ON|IN|NOT|NULL)\s)')),
    ('sqlstate-or-vendor-msg', re.compile(r'(?i)^(?:[0-9]{2}[0-9A-Z]{3}|HY\d{3})$|(catalog error|binder error|parser error|conversion error|syntax error|does not exist|already exists|relation .* does not|undefined_table|table .* not found|unknown column|no such table|no such column)')),
]

def zone(path):
    if path.startswith('core/src/main/java/com/legend/sql/dialect/'):
        return 'dialect'
    if path.startswith('core/src/main/java/com/legend/sql/'):
        return 'sql-mir'
    top = path.split('/')[0]
    if top in ('docs', 'experiments', 'scripts', 'parser-equivalence', 'repro', 'third_party', 'fixtures', 'tools',
               'progress', '.github', 'site'):
        return 'tooling'
    if '/src/test/' in path or top in ('spec', 'pct', 'testing') or '/test/' in path or path.endswith('Test.java'):
        return 'harness'
    return 'product'

NEW={'jdbc-types','driver-object','dialect-query','sql-fragment','sqlstate-or-vendor-msg'}
rows = []
for f in files:
    if not SRC.search(f):
        continue
    p = os.path.join(ROOT, f)
    try:
        text = open(p, encoding='utf-8', errors='replace').read()
    except OSError:
        continue
    ext = f.rsplit('.', 1)[1]
    segs = lex_py(text) if ext == 'py' else lex_c(text, ext in ('ts', 'tsx', 'js', 'mjs'))
    z = zone(f)
    module = f.split('/')[0] if not f.startswith('core/') else '/'.join(f.split('/')[:7])
    for kind, s, line in segs:
        if kind == 'comment':
            continue
        pats = CODE_PATTERNS if kind == 'code' else STRING_PATTERNS
        for name, rx in pats:
            if name not in NEW: continue
            for m in rx.finditer(s):
                ln = line + s.count('\n', 0, m.start())
                # the snippet: that source line, trimmed
                src_line = text.split('\n')[ln - 1].strip() if 0 < ln <= text.count('\n') + 1 else ''
                rows.append((z, module, f, ln, name, m.group(0).strip()[:60].replace('\t', ' ').replace('\n', ' ').replace('\r', ' '),
                             src_line[:200].replace('\t', ' ')))
os.makedirs(OUT, exist_ok=True)
with open(os.path.join(OUT, 'hits2.tsv'), 'w') as o:
    o.write('zone\tmodule\tfile\tline\tcategory\tmatch\tsource\n')
    for r in rows:
        o.write('\t'.join(map(str, r)) + '\n')
print(len(rows), 'hits')
