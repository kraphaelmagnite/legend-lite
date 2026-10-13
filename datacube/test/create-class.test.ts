// Create a Class… (docs/DATACUBE_MODEL_DESIGN_2026_10_10.md §15): the names DataCube offers, the grid's rows as a
// class's query, a source's table, and the page's model in the model home -- its project made with the first class,
// a save over a newer revision made again once.

import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import { accessor, toJson } from '../../pure-protocol/src/index.ts';
import { SdlcError, type SdlcClient } from '../../sdlc-client/src/client.ts';
import type { PureChange } from '../../sdlc-client/src/wire.ts';
import { artifactIdOf, createClass, makeProject, projectState, projectText, saveFresh, type ModelWriter } from '../../model-changes/src/index.ts';
import { PageModel, pageProject } from '../src/page-model.ts';
import { writesModels } from '../src/model-writer.ts';
import { pageGrids } from '../src/model-actions.ts';
import { PlanAndRun } from '../src/runner.ts';
import { isPackage, isPureName, suggestedClassName, suggestedDataSpaceName, suggestedPackage } from '../src/model-names.ts';
import { classLambda, duplicateLambda, sampleLambda, storeTableOf } from '../src/query.ts';
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
    assert.equal(suggestedDataSpaceName('Trades by desk'), 'TradesByDesk');
    assert.ok(isPackage('trades::desk') && !isPackage('trades::') && !isPackage('1x'));
    assert.ok(isPureName('tradeId') && !isPureName('true') && !isPureName('trade id'));
  });

  it('a project from the page: the SDLC\'s artifact id rule', () => {
    assert.equal(artifactIdOf('Trades by desk'), 'trades-by-desk');
    assert.equal(artifactIdOf('2024 Trades'), 'trades');
    assert.equal(artifactIdOf('!!!'), 'project');
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

  it('a grid is read where its rows are planned and run, on whichever host: a Python grid too (B1)', async () => {
    // a grid whose rows the host plans and runs (as every grid on Python's page is): its source said by its planner,
    // its sample read through its PlanAndRun -- never the page's own planner or engine, which a Python host has not
    const asked: string[] = [];
    const host = {
      name: 'python',
      planner: { source: () => ({ model: 'M', runtime: 'local::R' }) } as never,
      planAndRun: async (query: unknown) => {
        asked.push(toJson(query as never));
        return { plan: { sql: 'SELECT 1', columns: [] }, rows: { columns: [], rowCount: 0, epoch: 0, elapsedMs: 0 } };
      },
      stream: async () => undefined,
    };
    const rows = new PlanAndRun(host);
    const [grid] = pageGrids(['g1'], () => ({ snapshot: snapshot(), label: 'trades', rows }), () => undefined,
      { classLambda, storeTableOf, sampleLambda });
    assert.ok(grid && !('refused' in grid.made), 'a class candidate');
    assert.deepEqual(grid.made.source, { model: 'M', runtime: 'local::R' });
    assert.equal(grid.rows, rows);
    // its rows read where they are: the host's PlanAndRun answers the grid's queries
    await grid.rows.host.planAndRun(grid.made.sample, 0);
    assert.equal(asked.length, 1);
    assert.match(asked[0]!, /"limit"/);
  });

  it('the model\'s writer is the one that answers the three routes', () => {
    assert.ok(writesModels({ createClass: () => undefined, createAssociation: () => undefined, createDataSpace: () => undefined,
      withModel: () => undefined }));
    assert.ok(!writesModels({ plan: () => undefined }));
  });

  it('a column\'s uniqueness asked as a query: its first value held twice', () => {
    const json = toJson(duplicateLambda(SOURCE, 'customer_id'));
    assert.match(json, /"groupBy"/);
    assert.match(json, /"greaterThan"/);
    assert.match(json, /"limit"/);
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

/** A writer that makes one class file per call, of the class asked for (the routes' answers are model-route.test.ts's). */
const WRITER = {
  createClass: async (_project: string, pkg: string, cls: { name: string }) => ({
    changes: [{ type: 'CREATE', path: `${pkg}::domain::${cls.name}`, pureCode: `Class ${pkg}::domain::${cls.name} {}` }],
    classes: [{ class: `${pkg}::domain::${cls.name}` }], associations: [], suggestions: [], joinable: [], notes: [],
  }),
} as unknown as ModelWriter;

describe("the page's model, in the model home (through the library: model-changes/)", () => {
  it('its project made with its first class, named after the page; a name taken numbered', async () => {
    const sdlc = new FakeSdlc();
    assert.equal(await makeProject(sdlc.client(), pageProject('Trades')), 'datacube:trades-2');
    const model = new PageModel();
    const done = await createClass({ sdlc: sdlc.client(), writer: WRITER, ...model.into('Trades', 'trades'),
      class: { name: 'Trade' } as never });
    assert.deepEqual(model.ref, { project: 'datacube:trades-3', workspace: 'datacube', package: 'trades', tables: {} });
    assert.equal(done.project, 'datacube:trades-3');
    assert.equal(model.into('Other', 'other').project, 'datacube:trades-3', 'made once');
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
    const model = new PageModel();
    sdlc.workspace = false;
    await assert.rejects(createClass({ sdlc: made, writer: WRITER, ...model.into('Trades', 'trades'),
      class: { name: 'Trade' } as never }), /busy/);
    assert.equal(model.ref?.project, 'datacube:trades-2', 'the project made is the page\'s');
    await createClass({ sdlc: made, writer: WRITER, ...model.into('Trades', 'trades'), class: { name: 'Trade' } as never });
    assert.equal(model.ref?.project, 'datacube:trades-2', 'not made again');
    assert.equal(sdlc.made, 1, 'its workspace made when next asked');
  });

  it('a project not in this model home is said by name', async () => {
    const sdlc = new FakeSdlc();
    sdlc.workspace = false;
    const client = { ...sdlc.client(), createWorkspace: async () => { throw new SdlcError('no project', 404); } } as unknown as SdlcClient;
    await assert.rejects(projectState(client, { project: 'datacube:elsewhere', workspace: 'datacube' }),
      /datacube:elsewhere is not in this model home/);
  });

  it('a class saved over the revision its files were read at; a stale save made again once, then said', async () => {
    const sdlc = new FakeSdlc();
    const at = { project: 'datacube:trades', workspace: 'datacube' };
    const change = (path: string): PureChange => ({ type: 'CREATE', path, pureCode: `Class ${path} {}` });
    const read: string[] = [];
    sdlc.stale = 1;
    await saveFresh(sdlc.client(), at, 'one', async (project) => {
      read.push(project);
      return { changes: [change('trades::domain::Trade')] };
    });
    assert.equal(read.length, 2, 'made again after the stale refusal');
    assert.equal(sdlc.saves.length, 1);
    await saveFresh(sdlc.client(), at, 'two', async (project) => {
      assert.match(project, /###Pure\nClass trades::domain::Trade/, 'the project as it is now, its files as one text');
      return { changes: [change('trades::domain::Customer')] };
    });
    // its review committed in Studio, the workspace is gone: made again from the project line, and the class saved
    sdlc.workspace = false;
    await saveFresh(sdlc.client(), at, 'after a commit', async () => ({ changes: [change('trades::domain::Book')] }));
    assert.equal(sdlc.made, 1);
    sdlc.stale = 2;
    await assert.rejects(saveFresh(sdlc.client(), at, 'three', async () => ({ changes: [change('trades::domain::Desk')] })),
      /Expected revision/);
    assert.equal(projectText([{ pureCode: '###Mapping\nMapping a::M ()' }, { pureCode: 'Class a::C {}' }]),
      '###Mapping\nMapping a::M ()\n###Pure\nClass a::C {}');
  });
});
