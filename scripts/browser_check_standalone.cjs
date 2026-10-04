const {chromium}=require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');

(async()=>{
  const base=process.env.MYDESK_BASE || 'http://127.0.0.1:8787';
  const credentials=JSON.parse(fs.readFileSync(process.env.MYDESK_ACCESS || '.local/standalone/local-access.json','utf8'));
  const channel=process.env.MYDESK_BROWSER_CHANNEL || 'msedge';
  const browser=await chromium.launch({headless:true,...(channel==='chromium'?{}:{channel})});
  try {
    const context=await browser.newContext({viewport:{width:1440,height:1100}});
    const page=await context.newPage();const errors=[];
    await page.addInitScript(()=>{
      const Original=window.WebSocket;window.__mydeskSockets=[];
      window.WebSocket=class extends Original {constructor(...args){super(...args);window.__mydeskSockets.push(this);}};
    });
    page.on('pageerror',error=>errors.push(error.message));
    await page.goto(base);
    await page.getByRole('heading',{name:'登录 MyDesk'}).waitFor();
    fs.mkdirSync('artifacts',{recursive:true});
    await page.screenshot({path:'artifacts/standalone-login.png',fullPage:true});
    await page.getByLabel('用户名',{exact:true}).fill(credentials.username);
    await page.getByLabel('密码',{exact:true}).fill('wrong');
    await page.getByRole('button',{name:'登录',exact:true}).click();
    await page.getByText('账号或密码不正确',{exact:true}).waitFor();
    await page.getByLabel('密码',{exact:true}).fill(credentials.password);
    await page.getByRole('button',{name:'登录',exact:true}).click();
    await page.getByText('已连接',{exact:true}).waitFor({timeout:20000});
    const sessionInfo=await context.request.get(base+'/api/session').then(r=>r.json());
    const command=async(action,payload={})=>context.request.post(base+'/api/command',{headers:{'X-MyDesk-CSRF':sessionInfo.csrf},data:{action,payload}});
    const state=await command('snapshot').then(r=>r.json());
    for(const r of state.reminders.filter(r=>r.title.startsWith('独立版浏览器验证 '))) await command('reminder/action',{id:r.id,action:'complete'});
    const second=await context.newPage();await second.goto(base);await second.getByText('已连接',{exact:true}).waitFor();
    const reminder=page.locator('mydesk-card.tile-reminder');
    const title='独立版浏览器验证 '+Date.now();
    await reminder.getByRole('button',{name:'新建提醒'}).click();
    await reminder.getByLabel('提醒内容',{exact:true}).fill(title);
    await reminder.getByRole('button',{name:'30分钟后',exact:true}).click();
    await reminder.getByRole('button',{name:'创建提醒',exact:true}).click();
    await second.getByText(title,{exact:true}).waitFor();
    await page.reload();await reminder.getByText(title,{exact:true}).waitFor();
    await reminder.getByRole('combobox',{name:'稍后提醒：'+title}).selectOption('30');
    await reminder.getByText('已延后',{exact:true}).waitFor();
    await context.setOffline(true);
    await page.evaluate(()=>window.__mydeskSockets.at(-1).close());
    await page.getByText('连接异常',{exact:true}).waitFor({timeout:30000});
    await context.setOffline(false);
    await page.getByText('已连接',{exact:true}).waitFor({timeout:30000});
    assert.equal(await page.getByText('连接中断，正在重新连接',{exact:true}).count(),0,'Reconnect should clear old error messages');
    await reminder.getByRole('button',{name:'完成提醒：'+title}).click();
    await second.getByText(title,{exact:true}).waitFor({state:'hidden'});
    await page.screenshot({path:'artifacts/standalone-dashboard-desktop.png',fullPage:true});
    for(const width of [375,768,1024,1440]) {
      await page.setViewportSize({width,height:1000});
      assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);
    }
    await page.setViewportSize({width:375,height:1000});
    const positions=await Promise.all(['attention','reminder','automation','server','network','wxstep','mail'].map(mode=>page.locator('mydesk-card.tile-'+mode).boundingBox()));
    for(let i=1;i<positions.length;i++)assert(positions[i].y>positions[i-1].y);
    await page.screenshot({path:'artifacts/standalone-dashboard-mobile.png',fullPage:true});
    await page.getByRole('link',{name:'设置',exact:true}).click();
    await page.getByRole('heading',{name:'手机系统通知'}).waitFor();
    const basic=page.locator('form[data-config="basic"]');
    const retention=await basic.getByLabel('历史保留天数').inputValue();
    await basic.getByRole('button',{name:'保存工作台设置'}).click();
    await page.getByText('设置已保存。服务接入后将在后台同步。',{exact:true}).waitFor();
    await page.reload();await basic.getByLabel('历史保留天数').waitFor();
    assert.equal(await basic.getByLabel('历史保留天数').inputValue(),retention);
    assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);
    assert.equal(await page.locator('body').innerText().then(text=>text.includes('Home Assistant')),false);
    await page.setViewportSize({width:1440,height:1100});
    // Do not screenshot the secret-bearing webhook address.
    await page.locator('#webhook-url').evaluate(node=>node.textContent='上报地址已隐藏');
    await page.screenshot({path:'artifacts/standalone-settings.png',fullPage:true});
    await page.getByRole('button',{name:'切换外观'}).click();
    await page.getByRole('link',{name:'工作台',exact:true}).click();
    await page.screenshot({path:'artifacts/standalone-dashboard-dark.png',fullPage:true});
    await page.emulateMedia({reducedMotion:'reduce'});
    await page.setViewportSize({width:812,height:375});
    assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);
    await page.getByRole('button',{name:'退出登录'}).click();
    await page.getByRole('heading',{name:'登录 MyDesk'}).waitFor();
    await second.reload();await second.getByRole('heading',{name:'登录 MyDesk'}).waitFor();
    assert.deepEqual(errors,[]);
    console.log('Independent browser passed: login/errors, live two-tab updates, create/reload/snooze/complete, offline reconnect, settings persistence, widths/dark/reduced-motion, logout revocation.');
  } finally {await browser.close();}
})().catch(error=>{console.error(error);process.exit(1);});
