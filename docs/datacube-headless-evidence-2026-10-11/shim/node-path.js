// A stand-in for node:path (posix): string functions only, for the test helpers that join a directory and a file name.
const norm = (p) => {
  const abs = p.startsWith('/');
  const out = [];
  for (const s of p.split('/')) {
    if (s === '' || s === '.') continue;
    if (s === '..') { if (out.length && out[out.length - 1] !== '..') out.pop(); else if (!abs) out.push('..'); continue; }
    out.push(s);
  }
  return (abs ? '/' : '') + out.join('/') || (abs ? '/' : '.');
};
export const sep = '/';
export const delimiter = ':';
export const normalize = (p) => norm(String(p));
export const join = (...ps) => norm(ps.filter((x) => x !== '').join('/'));
export const isAbsolute = (p) => String(p).startsWith('/');
export const resolve = (...ps) => { let r = ''; for (const p of ps) r = String(p).startsWith('/') ? p : `${r}/${p}`; return norm(r.startsWith('/') ? r : `/${r}`); };
export const dirname = (p) => { const s = String(p).replace(/\/+$/, ''); const i = s.lastIndexOf('/'); return i < 0 ? '.' : i === 0 ? '/' : s.slice(0, i); };
export const basename = (p, ext) => { const b = String(p).replace(/\/+$/, '').split('/').pop() ?? ''; return ext && b.endsWith(ext) ? b.slice(0, -ext.length) : b; };
export const extname = (p) => { const b = basename(p); const i = b.lastIndexOf('.'); return i <= 0 ? '' : b.slice(i); };
export const relative = (from, to) => { const a = resolve(from).split('/').filter(Boolean); const b = resolve(to).split('/').filter(Boolean); let i = 0; while (i < a.length && a[i] === b[i]) i++; return [...a.slice(i).map(() => '..'), ...b.slice(i)].join('/'); };
const path = { sep, delimiter, normalize, join, isAbsolute, resolve, dirname, basename, extname, relative };
export const posix = path;
export default { ...path, posix: path };
