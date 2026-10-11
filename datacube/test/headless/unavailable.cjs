// A Node-only module (jsdom, ...) in the headless lane, which has none: importing it works, USING it throws, by name --
// so a test file that imports one only for tests the lane does not run still loads, and a use says what it needed.
// (A CommonJS module, so a named import of anything from it resolves.)
const named = (path) => new Proxy(function unavailable() {}, {
  get(_t, k) {
    if (k === '__esModule') return false;
    if (k === 'then' || typeof k === 'symbol') return undefined;
    return named(`${path}.${String(k)}`);
  },
  apply() { throw new Error(`unavailable in the headless lane: ${path}()`); },
  construct() { throw new Error(`unavailable in the headless lane: new ${path}()`); },
});
module.exports = named('<a Node-only module>');
