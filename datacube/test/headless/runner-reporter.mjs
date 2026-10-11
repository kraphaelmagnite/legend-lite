// A node:test reporter for runner-entry.ts: one line per test that ran (suites aside), its path and what it came to,
// as headless-test-runner.test.ts compares them with the stand-in's results.
export default async function* report(source) {
  const path = [];
  for await (const event of source) {
    const data = event.data;
    if (event.type === 'test:start') {
      path.length = data.nesting;
      path[data.nesting] = data.name;
    } else if ((event.type === 'test:pass' || event.type === 'test:fail') && data.details?.type !== 'suite') {
      const name = [...path.slice(0, data.nesting), data.name].join(' > ');
      const status = data.skip !== undefined || data.todo !== undefined ? 'skip' : event.type === 'test:pass' ? 'pass' : 'fail';
      yield `RESULT ${JSON.stringify([name, status])}\n`;
    }
  }
}
