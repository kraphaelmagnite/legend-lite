// export-rich.test.ts's tests that read a workbook's XML through jsdom. The others are in export-rich.test.ts, which
// runs in the headless lane too (docs/DATACUBE_HEADLESS_PYTHON_DESIGN_2026_10_11.md §6).

import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import { strFromU8, unzipSync } from 'fflate';
import { JSDOM } from 'jsdom';
import { exportTable, type ExportTable } from '../src/export-model.ts';
import { sheetName, toXlsx } from '../src/export-xlsx.ts';
import { buildColumnModel } from '../src/grid/columns.ts';
import type { ResultTable } from '../../engine-client/src/result.ts';

const rt: ResultTable = {
  columns: [
    { name: 'region', type: 'String', values: ['EMEA', 'A&B <x>'] },
    { name: 'notional', type: 'Float', values: [1234.5, -20] },
    { name: 'qty', type: 'Integer', values: [3, null] },
    { name: 'day', type: 'StrictDate', values: ['2024-02-29', null] },
    { name: 'at', type: 'DateTime', values: ['2024-02-29T13:14:15', null] },
    { name: 'ok', type: 'Boolean', values: [true, false] },
    { name: 'big', type: 'Integer', values: [9007199254740993n, 1n] },
    { name: 'secret', type: 'Float', values: [42, 7] },
  ],
  rowCount: 2,
  epoch: 1,
  elapsedMs: 0,
};

const table = (title = 'Profit and loss by region, Q1 & Q2 [draft]'): ExportTable => exportTable({
  title, rows: rt, model: buildColumnModel(rt, [], ['notional'], { blurred: ['secret'] }),
  treeRows: [], groupLabels: [], truncated: false,
});

const xml = (text: string): Document => {
  const doc = new new JSDOM('').window.DOMParser().parseFromString(text, 'application/xml');
  assert.equal(doc.getElementsByTagName('parsererror').length, 0, `not well-formed:\n${text.slice(0, 300)}`);
  return doc;
};

describe('Excel: a real .xlsx', () => {
  const files = unzipSync(toXlsx(table(), { formats: { notional: { kind: 'currency', currency: 'USD', decimals: 0, negativeParens: true } } }));

  const part = (name: string): string => {
    const f = files[name];
    assert.ok(f, `the workbook has ${name}`);
    return strFromU8(f!);
  };

  it('has the OOXML parts, each well-formed XML', () => {
    for (const name of ['[Content_Types].xml', '_rels/.rels', 'xl/workbook.xml', 'xl/_rels/workbook.xml.rels',
      'xl/styles.xml', 'xl/worksheets/sheet1.xml', 'xl/worksheets/sheet2.xml']) xml(part(name));
  });

  it('names the sheet as Excel allows: no [ ] : * ? / \\, at most 31 characters, cut before escaping', () => {
    const sheet = xml(part('xl/workbook.xml')).getElementsByTagName('sheet')[0]!.getAttribute('name')!;
    assert.ok(sheet.length <= 31 && !/[[\]:*?/\\]/.test(sheet), sheet);
    assert.equal(sheetName('a'.repeat(28) + ' & b'), `${'a'.repeat(28)} &`);
    assert.equal(sheetName('[]'), 'Sheet1');
  });
});

describe('the whole page: charts where the board puts them, and the whole grid', () => {
  const picture = { width: 400, height: 200, pixelWidth: 800, pixelHeight: 400,
    png: Uint8Array.from([0x89, 0x50, 0x4e, 0x47, 1, 2, 3]), jpeg: Uint8Array.from([0xff, 0xd8, 0xff, 4, 5, 6]) };

  const page = { cols: 12, tiles: [
    { id: 'grid', kind: 'grid' as const, title: 'Grid', x: 0, y: 0, w: 12, h: 14 },
    { id: 'chart-1', kind: 'chart' as const, title: 'By region', x: 0, y: 14, w: 6, h: 10, picture },
    { id: 'chart-2', kind: 'chart' as const, title: 'Not drawn', x: 6, y: 14, w: 6, h: 10 },
  ] };

  it('the workbook: a Dashboard sheet first, the chart as a picture, the table on its own sheet', () => {
    const files = unzipSync(toXlsx(table('Trades'), { page }));
    assert.match(strFromU8(files['xl/workbook.xml']!), /<sheet name="Dashboard" sheetId="1" r:id="rId4"\/><sheet name="Trades"/);
    assert.deepEqual([...files['xl/media/image1.png']!], [...picture.png]);
    const drawing = strFromU8(files['xl/drawings/drawing1.xml']!);
    xml(drawing);
    assert.match(drawing, /<xdr:from><xdr:col>0<\/xdr:col><xdr:colOff>0<\/xdr:colOff><xdr:row>29<\/xdr:row>/, 'below its title, at the board\'s place');
    const dash = strFromU8(files['xl/worksheets/sheet3.xml']!);
    const sheet1 = strFromU8(files['xl/worksheets/sheet1.xml']!);
    // THE TABLE ITSELF in the grid's tile, one row down (under its title), styled as on its own sheet
    assert.match(dash, /<c r="A2" s="(\d+)" t="inlineStr"><is><t xml:space="preserve">region<\/t>/);
    const cell = (sheet: string, ref: string): string | undefined => new RegExp(`<c r="${ref}"[^>]*>(?:<v>[^<]*</v>|<is>.*?</is>)?`).exec(sheet)?.[0];
    assert.equal(cell(dash, 'H3')?.replace('H3', 'X'), cell(sheet1, 'H2')?.replace('H2', 'X'), 'the same cell, the same style');
    for (const part of ['xl/worksheets/sheet3.xml', 'xl/drawings/_rels/drawing1.xml.rels', '[Content_Types].xml']) xml(strFromU8(files[part]!));
  });
});
