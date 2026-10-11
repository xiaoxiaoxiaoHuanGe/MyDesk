/* Real browser + disposable local backend; no production accounts or services. */
const {chromium,request}=require(process.env.PLAYWRIGHT_MODULE||'playwright-core');
const assert=require('node:assert/strict');
const fs=require('node:fs');
(async()=>{
 const base=process.env.MYDESK_BASE||'http://127.0.0.1:8790';
 assert.equal(process.env.MYDESK_WEB_TEST_ISOLATED,'1','Use only a disposable fixture');
 assert(['127.0.0.1','localhost','[::1]'].includes(new URL(base).hostname));
 const access=JSON.parse(fs.readFileSync(process.env.MYDESK_ACCESS||'.local/web-fixture/local-access.json','utf8'));
 const browser=await chromium.launch({headless:true,...(process.env.MYDESK_BROWSER_CHANNEL?{channel:process.env.MYDESK_BROWSER_CHANNEL}:process.platform==='win32'?{channel:'msedge'}:{})});
 const phone=await request.newContext({baseURL:base});
 try {
  const context=await browser.newContext({viewport:{width:1440,height:1000}});
  const page=await context.newPage(),errors=[];
  page.on('pageerror',error=>errors.push(error.message));
  let pollCount=0,createCount=0;page.on('request',req=>{if(req.method()==='GET'&&/\/api\/qr-login\/[a-f0-9]{32}$/.test(req.url()))pollCount++;if(req.method()==='POST'&&req.url()===base+'/api/qr-login')createCount++;});
  const nextCode=()=>page.waitForResponse(r=>r.url()===base+'/api/qr-login'&&r.request().method()==='POST').then(r=>r.json());
  let pending=nextCode();await page.goto(base);let code=await pending;
  await page.getByAltText('MyDesk 登录二维码').waitFor();
  assert.equal(createCount,1,'Initial authentication failure creates exactly one QR challenge');
  assert.equal(await page.locator('#login-form').isVisible(),false);
  assert.equal(await page.getByRole('button',{name:'App 扫码登录',exact:true}).getAttribute('aria-pressed'),'true');
  fs.mkdirSync('artifacts',{recursive:true});
  await page.screenshot({path:'artifacts/qr-login-desktop.png',fullPage:true});
  for(const width of [320,375,768,1440]) {
   await page.setViewportSize({width,height:1000});
   assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false,'Login overflow at '+width);
  }
  await page.setViewportSize({width:375,height:812});await page.screenshot({path:'artifacts/qr-login-mobile.png',fullPage:true});
  await page.getByRole('button',{name:'账号密码登录',exact:true}).click();
  await page.getByLabel('用户名',{exact:true}).waitFor();const stopped=pollCount;await page.waitForTimeout(1800);assert.equal(pollCount,stopped);
  pending=nextCode();await page.getByRole('button',{name:'App 扫码登录',exact:true}).click();code=await pending;
  const params=new URL(code.uri).searchParams;
  const scan={id:params.get('id'),token:params.get('token')};
  const login=await phone.post('/api/login',{data:{...access,client:'android'}});assert(login.ok());
  const headers={'X-MyDesk-CSRF':(await login.json()).csrf};
  const preview=await phone.post('/api/qr-login/preview',{headers,data:scan});assert(preview.ok());
  await page.getByText('已扫码，请在 App 上确认登录',{exact:true}).waitFor();
  assert.equal(await page.locator('#application').isVisible(),false);
  const confirm=await phone.post('/api/qr-login/confirm',{headers,data:{...scan,approve:true}});assert(confirm.ok());
  await page.getByRole('heading',{name:'需要处理',exact:true}).waitFor();
  const session=await context.request.get(base+'/api/session').then(r=>r.json());assert.equal(session.username,access.username);
  await context.request.post(base+'/api/logout',{headers:{'X-MyDesk-CSRF':session.csrf}});
  pending=nextCode();await page.reload();await pending;
  await page.route('**/api/qr-login',async route=>{
   if(route.request().method()!=='POST')return route.continue();
   const response=await route.fetch();const value=await response.json();
   await route.fulfill({response,json:{...value,expires_in:0}});
  });
  pending=nextCode();await page.getByRole('button',{name:'账号密码登录',exact:true}).click();await page.getByRole('button',{name:'App 扫码登录',exact:true}).click();await pending;
  await page.getByText('二维码已过期，请刷新',{exact:true}).waitFor();await page.getByRole('button',{name:'刷新二维码',exact:true}).waitFor();
  await page.unroute('**/api/qr-login');pending=nextCode();await page.getByRole('button',{name:'刷新二维码',exact:true}).click();await pending;
  await page.getByAltText('MyDesk 登录二维码').waitFor();
  await page.evaluate(async()=>{const app=await import('/frontend/app.js');app.setAppearance('dark');});
  await page.waitForTimeout(250); // Let theme background transitions finish before visual review.
  await page.screenshot({path:'artifacts/qr-login-dark.png',fullPage:true});
  await page.getByRole('button',{name:'账号密码登录',exact:true}).click();
  await page.getByLabel('用户名',{exact:true}).fill(access.username);await page.getByLabel('密码',{exact:true}).fill(access.password);
  await page.getByRole('button',{name:'登录',exact:true}).click();await page.getByRole('heading',{name:'需要处理',exact:true}).waitFor();
  assert.deepEqual(errors,[]);
  console.log('QR browser passed: default QR, method switching, cancelled polling, explicit app approval, one-time browser login, expiry/refresh, password fallback, four widths, light/dark.');
 }finally{await phone.dispose();await browser.close();}
})().catch(error=>{console.error(error);process.exit(1)});
