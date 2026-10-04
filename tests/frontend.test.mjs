import test from 'node:test';
import * as ui from '../frontend/ui.js';
import assert from 'node:assert/strict';
import {escapeHtml, safeUrl, parseSteps, formatDate, statusLabel} from '../frontend/ui.js';

test('remote text is escaped rather than injected into HTML', () => {
  assert.equal(escapeHtml('<img onerror="bad()">'), '&lt;img onerror=&quot;bad()&quot;&gt;');
});
test('links reject executable protocols and permit HTTPS', () => {
  assert.equal(safeUrl('javascript:alert(1)'), '#');
  assert.equal(safeUrl('https://github.com/a/b/actions/runs/1'), 'https://github.com/a/b/actions/runs/1');
});
test('step validation includes both boundaries and rejects decimals', () => {
  assert.equal(parseSteps('0'), 0);
  assert.equal(parseSteps('30000'), 30000);
  for (const value of ['-1','30001','100000','1.2','1e3','',' ']) assert.throws(() => parseSteps(value));
});
test('dates use server timezone and absent timestamps do not fabricate values', () => {
  assert.match(formatDate('2026-10-01T08:21:00Z','Asia/Shanghai'), /16:21/);
  assert.equal(formatDate(null,'Asia/Shanghai'), '等待更新');
  assert.equal(statusLabel('unknown'), '未知');
});

test('cumulative byte counts use readable units and never turn missing data into zero', () => {
  assert.equal(typeof ui.formatByteCount, 'function');
  for (const [value, expected] of [[0,'0.00 B'],[1536,'1.50 KiB'],[1572864,'1.50 MiB'],[2415919104,'2.25 GiB']])
    assert.equal(ui.formatByteCount(value), expected);
  for (const value of [null,undefined,true,'100',-1,NaN,Infinity]) assert.equal(ui.formatByteCount(value),'未采集');
});
