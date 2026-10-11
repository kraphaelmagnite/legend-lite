// Create a Class… (docs/DATACUBE_MODEL_DESIGN_2026_10_10.md §15): the names DataCube offers, the grid's rows as a
// class's query, a source's table, and the page's model in the model home -- its project made with the first class,
// a save over a newer revision made again once.

import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import { accessor, toJson } from '../../pure-protocol/src/index.ts';
import { SdlcError, type SdlcClient } from '../../sdlc-client/src/client.ts';
import type { PureChange } from '../../sdlc-client/src/wire.ts';
import { PageModel, artifactIdOf, projectText, saveFresh } from '../src/page-model.ts';
import { isPackage, isPureName, suggestedClassName, suggestedPackage } from '../src/promote.ts';
import { classLambda, sampleLambda, storeTableOf, writesClasses } from '../src/query.ts';
import type { CubeSnapshot } from '../src/snapshot.ts';

describe('the names DataCube offers', () => {
  it('a class from its source: singular, upper camel case', () => {
    assert.equal(suggestedClassName('trades.csv'), 'Trade');
    assert.equal(suggestedClassName('order_lines.parquet'), 'OrderLine');
    assert.equal(suggestedClassName('TRADES'), 'Trade');
    assert.equal(suggestedClassName('companies.csv'), 'Company');
    assert.equal(suggestedClassName('addresses.csv'), 'Address');
    assert.equal(suggestedClassName('status.csv'), 'Status');
    assert.equal(suggestedClassName('2024 fills.csv'), '_2024Fill');
    assert.equal(suggestedClassName('...'), 'Item');
  });

  it('a package from the page: lower camel case, a Pure name', () => {
    assert.equal(suggestedPackage('Trades by desk'), 'tradesByDesk');
    assert.equal(suggestedPackage('trades'), 'trades');
    assert.equal(suggestedPackage(''), 'datacube');
    assert.ok(isPackage('trades::desk') && !isPackage('trades::') && !isPackage('1x'));
    assert.ok(isPureName('tradeId') && !isPureName('true') && !isPureName('trade id'));
  });

  it('a project from the page: the SDLC\'s artifact id rule', () => {
    assert.equal(artifactIdOf('Trades by desk'), 'trades-by-desk');
    assert.equal(artifactIdOf('2024 Trades'), 'trades');
    assert.equal(artifactIdOf('!!!'), 'page');
  });
});

const SOURCE = accessor('local::DB', 'trades_2');

function snapshot(patch: Partial<CubeSnapshot> = {}): CubeSnapshot {
  return {
    source: { query: SOURCE },
    columns: [{ name: 'region', type: 'String' }, { name: 'notional', type: 'Float' }],
    derived: [], rows: [], pivotOn: [], measures: [], sorts: [{ column: 'region', direction: 'asc' }],
    window: { offset: 0, limit: 1000 },
    epoch: 1,
    ...patch,
  } as CubeSnapshot;
}

describe("the grid's rows, as a class's", () => {
  it('a flat grid: its rows, in no order and uncapped', () => {
    const json = toJson(classLambda(snapshot()));
    assert.doesNotMatch(json, /"sort"|"limit"|"slice"/, json);
    assert.match(json, /trades_2/);
  });

  it('a grouped grid: its groups', () => {
    const json = toJson(classLambda(snapshot({ rows: ['region'], measures: [{ column: 'notional', fn: 'sum', name: 'notional' }] } as Partial<CubeSnapshot>)));
    assert.match(json, /"groupBy"/);
    assert.doesNotMatch(json, /"sort"|"limit"|"slice"/, json);
  });

  it('refused in words: a pivot, a total of no group', () => {
    assert.throws(() => classLambda(snapshot({ pivotOn: ['region'] })), /take the pivot off first/);
    assert.throws(() => classLambda(snapshot({ measures: [{ column: 'notional', fn: 'sum', name: 'notional' }] } as Partial<CubeSnapshot>)),
      /group by a column first/);
  });

  it('a source read from its store, and its sample', () => {
    assert.deepEqual(storeTableOf(SOURCE), { database: 'local::DB', schema: 'default', table: 'trades_2' });
    assert.deepEqual(storeTableOf(accessor('local::DB', 'main', 'trades')), { database: 'local::DB', schema: 'main', table: 'trades' });
    assert.equal(storeTableOf({ _type: 'var', name: 'x' } as never), undefined);
    assert.match(toJson(sampleLambda(SOURCE, 100)), /"limit"/);
  });

  it('a planner writes classes when it answers the route', () => {
    assert.ok(writesClasses({ promoteRelationToModel: () => undefined, runtime: 'local::RT' }));
    assert.ok(!writesClasses({ plan: () => undefined }));
  });
});

/** An SDLC holding one workspace's files, its revisions counted: what the page's model asks of one. */
class FakeSdlc {
  projects: { projectId: string; name: string }[] = [{ projectId: 'datacube:trades', name: 'trades' }];
  files = new Map<string, string>();
  revision = 1;
  /** Saves to refuse as stale, the next ones. */
  stale = 0;
  /** DataCube's workspace is there (a review committed closes it). */
  workspace = true;
  made = 0;
  saves: PureChange[][] = [];

  client(): SdlcClient {
    return {
      projects: async () => this.projects,
      createProject: async (c: { groupId: string; artifactId: string; name: string }) => {
        const p = { projectId: `${c.groupId}:${c.artifactId}`, name: c.name };
        this.projects.push(p);
        return p;
      },
      createWorkspace: async () => {
        this.workspace = true;
        this.made += 1;
        return {};
      },
      workspace: async () => {
        if (!this.workspace) throw new SdlcError('no such workspace', 404);
        return {};
      },
      revision: async () => ({ id: String(this.revision) }),
      pure: async () => [...this.files].map(([path, pureCode]) => ({ path, pureCode })),
      performPureChanges: async (_p: string, _w: string, command: { changes: PureChange[]; revisionId: string }) => {
        if (this.stale > 0 || command.revisionId !== String(this.revision)) {
          this.stale -= 1;
          this.revision += 1;
          throw new SdlcError('Expected revision', 409);
        }
        for (const c of command.changes) this.files.set(c.path, c.pureCode ?? '');
        this.saves.push(command.changes);
        this.revision += 1;
        return { id: String(this.revision) };
      },
    } as unknown as SdlcClient;
  }
}

describe("the page's model, in the model home", () => {
  it('its project made with its first class, named after the page; a name taken numbered', async () => {
    const sdlc = new FakeSdlc();
    const model = new PageModel(sdlc.client());
    const ref = await model.ensure('Trades', 'trades');
    assert.deepEqual(ref, { project: 'datacube:trades-2', workspace: 'datacube', package: 'trades', tables: {} });
    assert.equal(await model.ensure('Other', 'other'), ref, 'made once');
    model.keepTable('g1', 'trades_2');
    assert.deepEqual(model.ref?.tables, { g1: 'trades_2' });
  });

  it('its project kept from the moment it is made: a workspace refused after leaves no second project (S2)', async () => {
    const sdlc = new FakeSdlc();
    const client = sdlc.client();
    let refuse = true;
    const made = { ...client, createWorkspace: async (p: string, w: string) => {
      if (refuse) { refuse = false; throw new SdlcError('busy', 500); }
      return client.createWorkspace(p, w);
    } } as unknown as SdlcClient;
    const model = new PageModel(made);
    sdlc.workspace = false;
    await assert.rejects(model.ensure('Trades', 'trades'), /busy/);
    assert.equal(model.ref?.project, 'datacube:trades-2', 'the project made is the page\'s');
    assert.equal((await model.ensure('Trades', 'trades')).project, 'datacube:trades-2', 'not made again');
    await model.state();
    assert.equal(sdlc.made, 1, 'its workspace made when next asked');
  });

  it('a project not in this browser\'s model home is said by name', async () => {
    const sdlc = new FakeSdlc();
    sdlc.workspace = false;
    const client = { ...sdlc.client(), createWorkspace: async () => { throw new SdlcError('no project', 404); } } as unknown as SdlcClient;
    const model = new PageModel(client, { project: 'datacube:elsewhere', workspace: 'datacube', package: 'x', tables: {} });
    await assert.rejects(model.state(), /datacube:elsewhere is not in this browser's model home/);
  });

  it('a class saved over the revision its files were read at; a stale save made again once, then said', async () => {
    const sdlc = new FakeSdlc();
    const model = new PageModel(sdlc.client(), { project: 'datacube:trades', workspace: 'datacube', package: 'trades', tables: {} });
    const change = (path: string): PureChange => ({ type: 'CREATE', path, pureCode: `Class ${path} {}` });
    const read: string[] = [];
    sdlc.stale = 1;
    await saveFresh(model, 'one', async (project) => {
      read.push(project);
      return { changes: [change('trades::domain::Trade')] };
    });
    assert.equal(read.length, 2, 'made again after the stale refusal');
    assert.equal(sdlc.saves.length, 1);
    await saveFresh(model, 'two', async (project) => {
      assert.match(project, /###Pure\nClass trades::domain::Trade/, 'the project as it is now, its files as one text');
      return { changes: [change('trades::domain::Customer')] };
    });
    // its review committed in Studio, the workspace is gone: made again from the project line, and the class saved
    sdlc.workspace = false;
    await saveFresh(model, 'after a commit', async () => ({ changes: [change('trades::domain::Book')] }));
    assert.equal(sdlc.made, 1);
    sdlc.stale = 2;
    await assert.rejects(saveFresh(model, 'three', async () => ({ changes: [change('trades::domain::Desk')] })), /Expected revision/);
    assert.equal(projectText([{ pureCode: '###Mapping\nMapping a::M ()' }, { pureCode: 'Class a::C {}' }]),
      '###Mapping\nMapping a::M ()\n###Pure\nClass a::C {}');
  });
});
