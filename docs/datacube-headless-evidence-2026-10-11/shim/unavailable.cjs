// A Node-only module (node:fs, jsdom, ...) where there is none: importing it works, USING it throws, by name -- so a
// test that only imports one still runs, and one that needs it says what it needed.
const named = (path) => new Proxy(function () {}, {
  get(_t, k) {
    if (k === '__esModule') return false;
    if (k === 'then' || typeof k === 'symbol') return undefined;
    return named(`${path}.${String(k)}`);
  },
  apply() { throw new Error(`unavailable here: ${path}()`); },
  construct() { throw new Error(`unavailable here: new ${path}()`); },
});
module.exports = named('<node-only module>');
