// A stand-in for node:module: createRequire works, and the require it makes refuses only when it is called -- so a
// helper that makes one at load still loads, and one that needs Node's require says so.
export function createRequire(from) {
  const refuse = (id) => { throw new Error(`unavailable here: require(${JSON.stringify(id)}) from ${from}`); };
  const req = (id) => refuse(id);
  req.resolve = (id) => refuse(id);
  return req;
}
export default { createRequire };
