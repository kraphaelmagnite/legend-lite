// runner-suite.ts on Node's own node:test, in a child Node (headless-test-runner.test.ts starts it): the order the
// hooks and tests ran in, printed as the process ends; what each test came to, by runner-reporter.mjs.

import { after, afterEach, before, beforeEach, describe, it } from 'node:test';

import { register } from './runner-suite.ts';

const order: string[] = [];
register({ describe, it, before, after, beforeEach, afterEach } as never, order);
process.on('exit', () => { process.stdout.write(`ORDER ${JSON.stringify(order)}\n`); });
