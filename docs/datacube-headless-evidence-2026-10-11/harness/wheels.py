import json, sys, re
from pathlib import Path
# the five targets, as wheel platform tags match them
TARGETS = {
    'linux_x86_64': re.compile(r'manylinux.*x86_64|linux_x86_64'),
    'linux_aarch64': re.compile(r'manylinux.*aarch64|linux_aarch64'),
    'macos_arm64': re.compile(r'macosx.*(arm64|universal2)'),
    'macos_x86_64': re.compile(r'macosx.*(x86_64|universal2|intel)'),
    'windows_x86_64': re.compile(r'win_amd64'),
}
def py_ok(tag, abi):
    # a wheel usable on CPython 3.12 or later: abi3 (stable ABI), py3, or cp312+
    if 'abi3' in abi or tag.startswith('py3') or tag == 'py2.py3':
        return True
    m = re.match(r'cp3(\d+)', tag)
    return bool(m) and int(m.group(1)) >= 12
for f in sorted(Path(sys.argv[1]).glob('*.json')):
    d = json.loads(f.read_text())
    info, files = d['info'], d['urls']
    ver = info['version']
    up = min((u['upload_time'] for u in files), default='?')[:10]
    row = {}
    for t, rx in TARGETS.items():
        sizes = []
        for u in files:
            if u['packagetype'] != 'bdist_wheel':
                continue
            name = u['filename'][:-4]
            parts = name.split('-')
            pytag, abi, plat = parts[-3], parts[-2], parts[-1]
            if rx.search(plat) and py_ok(pytag, abi):
                sizes.append(u['size'])
        row[t] = f'{max(sizes)/1e6:.1f}MB' if sizes else '-'
    sdist = any(u['packagetype'] == 'sdist' for u in files)
    print(f"{f.stem:14} {ver:12} {up}  " + '  '.join(f'{t.split("_")[0][:3]}-{t.split("_",1)[1]}:{v}' for t, v in row.items()) + f"  sdist:{sdist}")
