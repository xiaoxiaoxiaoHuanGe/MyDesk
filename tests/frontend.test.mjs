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

test('countdown creates an absolute time including seconds and rejects an empty timer', () => {
  assert.equal(typeof ui.countdownTime, 'function');
  assert.equal(ui.countdownTime(0, 15, 30, new Date('2026-10-04T08:00:00Z')), '2026-10-04T08:15:30.000Z');
  for (const args of [[0,0,0],[-1,0,0],[1,60,0],[0,0,60],[0,1.5,0]]) assert.throws(() => ui.countdownTime(...args));
});

test('picker defaults follow the server timezone, even when the browser has a different date', () => {
  assert.equal(typeof ui.pickerDate, 'function');
  assert.deepEqual(ui.pickerDate('Asia/Shanghai', new Date('2026-10-04T20:03:00Z')), {date:'2026-10-05',hour:'04',minute:'03'});
});

test('mail filter sorts without mutating the snapshot and keeps a selected account separate', () => {
  assert.equal(typeof ui.selectMail, 'function');
  const items=[1,4,2,3].map(n=>({subject:String(n),received_at:`2026-10-04T0${n}:00:00Z`}));
  const feed={items,accounts:[{id:'work',items:[items[0]]}]};
  assert.deepEqual(ui.selectMail(feed).map(m=>m.subject), ['4','3','2']);
  assert.deepEqual(ui.selectMail(feed,'work').map(m=>m.subject), ['1']);
  assert.deepEqual(items.map(m=>m.subject), ['1','4','2','3']);
  assert.deepEqual(ui.selectMail(feed,'missing'), []);
});

test('three appearance choices resolve system preference without fabricating numeric metrics', () => {
  assert.equal(typeof ui.resolveAppearance, 'function');
  assert.equal(ui.resolveAppearance('system',true),'dark');
  assert.equal(ui.resolveAppearance('light',true),'light');
  assert.equal(ui.resolveAppearance('dark',false),'dark');
  assert.equal(ui.formatMetric(17.2468), '17.25');
  assert.equal(ui.formatMetric(null), '—');
});
