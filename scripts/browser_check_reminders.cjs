const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
const assert=require('node:assert/strict');
const fs=require('node:fs');
(async()=>{
  const base=process.env.MYDESK_BASE||'http://127.0.0.1:8790';
  assert.equal(process.env.MYDESK_WEB_TEST_ISOLATED,'1');
  assert.equal(new URL(base).hostname,'127.0.0.1');
  const credentials=JSON.parse(fs.readFileSync('.local/web-fixture/local-access.json','utf8'));
  const browser=await chromium.launch({headless:true,channel:'msedge'});
  try {
    const context=await browser.newContext({viewport:{width:390,height:844}}),page=await context.newPage(),errors=[];
    page.on('pageerror',e=>errors.push(e.message));
    await page.goto(base+'/#reminders');
    await page.getByRole('button',{name:'账号密码登录',exact:true}).click();
    await page.getByLabel('用户名',{exact:true}).fill(credentials.username);
    await page.getByLabel('密码',{exact:true}).fill(credentials.password);
    await page.getByRole('button',{name:'登录',exact:true}).click();
    const form=page.locator('#reminders-desk .reminder-form');
    await form.getByLabel('提醒事项（选填）',{exact:true}).waitFor();
    const minute=form.getByRole('listbox',{name:'分钟',exact:true});
    await minute.press('End');await minute.press('ArrowDown');
    assert.equal(await form.locator('[name=minute]').inputValue(),'00');
    await minute.press('ArrowUp');
    assert.equal(await form.locator('[name=minute]').inputValue(),'59');
    await form.getByLabel('重复',{exact:true}).selectOption('weekdays');
    const response=page.waitForResponse(r=>r.url().endsWith('/api/command')&&r.request().postDataJSON()?.action==='reminder/create');
    await form.getByRole('button',{name:'创建提醒',exact:true}).click();
    const result=await (await response).json();
    assert.equal(result.title,'提醒');assert.deepEqual(result.repeat.days,[1,2,3,4,5]);
    // Locate by the independent ID, since duplicate names are intentionally allowed.
    const summary=page.locator(`#reminders-desk button[data-action="reminder-detail"][data-id="${result.id}"]`);
    await summary.click();
    await page.getByRole('button',{name:'暂停重复',exact:true}).click();
    await summary.locator('.badge.paused').waitFor();
    await summary.click();await page.getByRole('button',{name:'恢复重复',exact:true}).click();
    await summary.locator('.badge.paused').waitFor({state:'hidden'});
    await page.screenshot({path:'artifacts/reminders-150-mobile.png',fullPage:true});
    await summary.click();await page.getByRole('button',{name:'取消提醒',exact:true}).click();
    await summary.waitFor({state:'hidden'});
    assert.deepEqual(errors,[]);
    console.log('PASS: minute wrap in both directions, unnamed recurring create, weekday rule, pause/resume, cancellation, mobile layout');
  } finally {await browser.close();}
})().catch(e=>{console.error(e);process.exit(1)});
