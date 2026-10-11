import test, {before, after} from 'node:test';
import assert from 'node:assert/strict';
import {createServer} from 'node:http';
import {readFile} from 'node:fs/promises';
import {createRequire} from 'node:module';

const {chromium} = createRequire(new URL('../frontend/package.json', import.meta.url))('playwright-core');
const messageId = 'a'.repeat(32);
const fixture = `
import {openInbox} from '/frontend/inbox.js';
const row = {id: '${messageId}', seq: 12, source_name: 'CPE', title: '测试短信', summary: '验证码 123456', body: '验证码 123456\\n第二行', received_at: '2026-10-07T12:00:00Z', read_at: null};
window.inboxTest = {posts: [], listLoads: 0, pending: null, lastClick: null};
document.addEventListener('click', event => {
  if (event.target.closest('[data-read], [data-read-all]')) window.inboxTest.lastClick = event;
}, true);
const delay = value => new Promise(resolve => setTimeout(() => resolve(value), 0));
const request = async (path, options) => {
  if (path === '/api/inbox/read') {
    window.inboxTest.posts.push(options);
    return new Promise((resolve, reject) => { window.inboxTest.pending = {resolve, reject}; });
  }
  if (path.startsWith('/api/inbox?')) {
    window.inboxTest.listLoads++;
    return delay({items: [{...row}], next_cursor: null});
  }
  if (path === '/api/inbox/' + row.id) return delay({...row});
  throw new Error('Unexpected fixture request: ' + path);
};
window.finishRead = success => {
  const pending = window.inboxTest.pending;
  window.inboxTest.pending = null;
  if (success) { row.read_at = '2026-10-07T12:01:00Z'; pending.resolve({updated: 1}); }
  else pending.reject(new Error('模拟请求失败，请重试'));
};
openInbox(request, {timezone: 'Asia/Shanghai'});
`;

let server, browser, base;
before(async () => {
  const modules = new Map(await Promise.all(['inbox.js', 'dialog.js', 'ui.js', 'icons.js'].map(async name =>
    ['/frontend/' + name, await readFile(new URL('../frontend/' + name, import.meta.url))])));
  server = createServer((request, response) => {
    if (request.url === '/') {
      response.writeHead(200, {'Content-Type': 'text/html; charset=utf-8'});
      response.end('<!doctype html><html><body><script type="module" src="/fixture.js"></script></body></html>');
    } else if (request.url === '/fixture.js' || modules.has(request.url)) {
      response.writeHead(200, {'Content-Type': 'text/javascript; charset=utf-8'});
      response.end(request.url === '/fixture.js' ? fixture : modules.get(request.url));
    } else if (request.url === '/favicon.ico') { response.writeHead(204); response.end(); }
    else { response.writeHead(404); response.end(); }
  });
  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', resolve);
  });
  base = 'http://127.0.0.1:' + server.address().port;
  const executablePath = process.env.MYDESK_BROWSER_EXECUTABLE;
  browser = await chromium.launch({headless: true, ...(executablePath ? {executablePath} :
    {channel: process.env.MYDESK_BROWSER_CHANNEL || (process.platform === 'win32' ? 'msedge' : undefined)})});
});
after(async () => {
  await browser?.close();
  if (server?.listening) {
    server.closeAllConnections();
    await new Promise(resolve => server.close(resolve));
  }
});

async function withInbox(run) {
  const page = await browser.newPage();
  page.setDefaultTimeout(3000);
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  try {
    await page.goto(base);
    await page.locator('[data-message]').waitFor();
    await run(page);
    assert.deepEqual(errors, [], 'Browser must not report unhandled async errors');
  } finally { await page.close(); }
}
async function clickRead(page, selector, body) {
  const button = page.locator(selector);
  await button.click();
  assert.equal(await button.isDisabled(), true, 'Disable while the read request is pending');
  assert.deepEqual(await page.evaluate(() => window.inboxTest.posts.at(-1)), {method: 'POST', body});
  assert.equal(await page.evaluate(() => window.inboxTest.lastClick.currentTarget === null), true,
    'A real browser clears currentTarget after event dispatch');
}
async function finishRead(page, success) {
  await page.evaluate(value => window.finishRead(value), success);
}
async function waitEnabled(page, selector) {
  await page.waitForFunction(value => document.querySelector(value)?.disabled === false, selector, {timeout: 3000});
}
async function assertReadList(page) {
  await page.waitForFunction(() => window.inboxTest.listLoads >= 2 && !document.querySelector('[data-messages]').textContent.includes('未读'));
}

test('browser: read-all succeeds, refreshes the list and can be clicked again', async () => withInbox(async page => {
  await clickRead(page, '[data-read-all]', {through_seq: 12});
  await finishRead(page, true);
  await waitEnabled(page, '[data-read-all]');
  await assertReadList(page);
  await clickRead(page, '[data-read-all]', {through_seq: 12});
  await finishRead(page, true);
  await waitEnabled(page, '[data-read-all]');
  assert.equal(await page.evaluate(() => window.inboxTest.posts.length), 2);
}));

test('browser: read-all failure shows feedback, re-enables the button and permits retry', async () => withInbox(async page => {
  await clickRead(page, '[data-read-all]', {through_seq: 12});
  await finishRead(page, false);
  await waitEnabled(page, '[data-read-all]');
  assert.equal(await page.locator('.dialog-feedback').textContent(), '模拟请求失败，请重试');
  assert.match(await page.locator('[data-messages]').textContent(), /未读/);
  await clickRead(page, '[data-read-all]', {through_seq: 12});
  await finishRead(page, true);
  await waitEnabled(page, '[data-read-all]');
  await assertReadList(page);
}));

test('browser: marking a detail read closes the detail and refreshes the inbox', async () => withInbox(async page => {
  await page.locator('[data-message]').click();
  await page.locator('[data-read]').waitFor();
  await clickRead(page, '[data-read]', {ids: [messageId]});
  await finishRead(page, true);
  await page.locator('[data-read]').waitFor({state: 'detached'});
  await assertReadList(page);
  assert.equal(await page.locator('dialog').count(), 1);
}));

test('browser: detail read failure keeps the content, re-enables the button and permits retry', async () => withInbox(async page => {
  await page.locator('[data-message]').click();
  await page.locator('[data-read]').waitFor();
  await clickRead(page, '[data-read]', {ids: [messageId]});
  await finishRead(page, false);
  await waitEnabled(page, '[data-read]');
  const detail = page.locator('dialog').filter({has: page.locator('[data-read]')});
  assert.equal(await detail.locator('.dialog-feedback').textContent(), '模拟请求失败，请重试');
  assert.equal(await detail.locator('pre').textContent(), '验证码 123456\n第二行');
  assert.equal(await page.locator('dialog').count(), 2);
  await clickRead(page, '[data-read]', {ids: [messageId]});
  await finishRead(page, true);
  await page.locator('[data-read]').waitFor({state: 'detached'});
  await assertReadList(page);
}));
