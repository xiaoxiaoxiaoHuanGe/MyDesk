import './mydesk-card.js';
import {DeskClient} from './transport.js';
import {escapeHtml as e} from './ui.js';

const $=selector=>document.querySelector(selector);
let csrf,client,theme=matchMedia('(prefers-color-scheme: dark)').matches?'dark':'light';
document.documentElement.dataset.theme=theme;
function showLogin() {
  client?.close(); $('#desk').remove();
  const card=document.createElement('mydesk-card');card.id='desk';$('#dashboard').append(card);
  $('#application').hidden=true;$('#login').hidden=false;csrf=null;
}
async function request(path,{method='GET',body}={}) {
  const response=await fetch(path,{method,credentials:'same-origin',headers:{'Content-Type':'application/json',...(csrf?{'X-MyDesk-CSRF':csrf}:{})},...(body!==undefined?{body:JSON.stringify(body)}:{})});
  const result=await response.json();
  if(!response.ok) {if(response.status===401 && path!=='/api/login') showLogin();throw new Error(result.error || '请求失败');}
  return result;
}
function signedIn(session) {
  csrf=session.csrf;$('#login').hidden=true;$('#application').hidden=false;
  $('#account-name').textContent=session.username;
  client=new DeskClient(request,showLogin);client.darkMode=theme==='dark';
  $('#desk').setConfig({mode:'dashboard'});$('#desk').client=client;
  if('serviceWorker' in navigator && isSecureContext) navigator.serviceWorker.register('/sw.js').catch(()=>{});
  route();
}
$('#show-password').onclick=()=>{const show=$('#password').type==='password';$('#password').type=show?'text':'password';$('#show-password').textContent=show?'隐藏':'显示';$('#show-password').setAttribute('aria-pressed',String(show));};
$('#login-form').onsubmit=async event=>{
  event.preventDefault();const form=event.currentTarget,button=form.querySelector('[type=submit]');button.disabled=true;button.textContent='正在登录…';$('#login-error').textContent='';
  try {signedIn(await request('/api/login',{method:'POST',body:Object.fromEntries(new FormData(form))}));form.reset();}
  catch(error){$('#login-error').textContent=error.message;}
  finally {button.disabled=false;button.textContent='登录';}
};
$('#logout').onclick=async()=>{try {await request('/api/logout',{method:'POST'});showLogin();}catch(error){feedback(error.message,true);}};
$('#theme').onclick=()=>{theme=theme==='dark'?'light':'dark';document.documentElement.dataset.theme=theme;if(client){client.darkMode=theme==='dark';$('#desk').client=client;}};
function feedback(message,error=false) {$('#settings-feedback').textContent=message;$('#settings-feedback').classList.toggle('error',error);}
function field(name,label,value='',type='text',help='') {return `<label for="${name}">${label}</label><input id="${name}" name="${name}" type="${type}" value="${type==='password'?'':e(value || '')}" ${type==='password'?'autocomplete="new-password"':''}>${help?`<p class="help">${e(help)}</p>`:''}`;}
function enabled(name,label,on) {return `<label><input type="checkbox" name="enabled" ${on?'checked':''}>${label}</label>`;}
function credential(item) {return `<p class="credential">${item?.credential_set?'凭据已保存；密码或 Token 留空会保留原值':'尚未保存凭据'}</p>`;}
function serviceForms(collection, entries) {
  const mail=collection==='gmail_accounts';
  const newId='source_'+crypto.randomUUID().replaceAll('-','').slice(0,12);
  const rows=[...Object.entries(entries),[newId,{provider:'1panel',enabled:true,limit:5}]];
  return `<section class="settings-card"><h2>${mail?'Gmail 邮箱':'服务器监控'}</h2><p class="help">每项单独管理；已有凭据留空保留。</p>${rows.map(([id,row])=>{
    const adding=!entries[id];
    const input=(key,label,type='text')=>`<label for="${id}-${key}">${label}</label><input id="${id}-${key}" name="${key}" type="${type}" value="${type==='password'?'':e(row[key]??'')}" ${type==='password'?'autocomplete="new-password"':''}>`;
    const provider=row.provider||'1panel';
    return `<details><summary>${adding?(mail?'添加邮箱':'添加服务器'):e(row.name)}</summary><form data-config="multi" data-collection="${collection}" data-source="${id}">
      ${input('name',mail?'邮箱名称':'服务器名称')}${enabled('', '启用同步', row.enabled!==false)}
      ${mail?`${input('username','Gmail 地址','email')}${input('password','应用专用密码','password')}<label>展示数量<select name="limit">${[3,4,5].map(n=>`<option value="${n}" ${n===(row.limit||5)?'selected':''}>${n} 封</option>`).join('')}</select></label>`:
        `<label>类型<select name="provider"><option value="1panel" ${provider==='1panel'?'selected':''}>1Panel v2</option><option value="beszel" ${provider==='beszel'?'selected':''}>Beszel</option></select></label>${input('url','面板根地址','url')}
        <div data-provider-fields="1panel" ${provider!=='1panel'?'hidden':''}>${input('api_key','API Key','password')}<label>签名<select name="signature"><option value="md5" ${row.signature!=='hmac-sha256'?'selected':''}>兼容模式</option><option value="hmac-sha256" ${row.signature==='hmac-sha256'?'selected':''}>HMAC-SHA256</option></select></label></div>
        <div data-provider-fields="beszel" ${provider!=='beszel'?'hidden':''}>${input('email','登录邮箱','email')}${input('password','密码','password')}</div>`}
      ${credential(row)}<button class="primary">保存${mail?'邮箱':'服务器'}</button>${adding?'':`<button type="button" data-service-check="${id}" data-kind="${mail?'mail':'servers'}">检查已保存的连接</button><button type="button" data-service-remove="${id}" data-collection="${collection}">移除</button>`}
      </form></details>`;
  }).join('')}</section>`;
}
async function renderSettings() {
  feedback('');
  const [s,n,w]=await Promise.all([request('/api/settings'),request('/api/mobile/devices'),request('/api/webhook-info')]);
  const gh=s.github || {},mail=s.gmail || {},beszel=s.beszel || {};
  $('#settings-content').innerHTML=`<div class="settings-grid">
    <form class="settings-card" data-config="basic"><h2>工作台</h2><p class="help">时间显示与业务历史保留。</p>${field('timezone','时区',s.timezone)}${field('history_days','历史保留天数',s.history_days,'number')}<button class="primary">保存工作台设置</button></form>
    <section class="settings-card"><h2>MyDesk Android App</h2><p class="help">手机通知已改为原生 App。定时提醒由手机执行，网页通知已停用。已登记的设备不代表当前在线或已允许系统通知。</p><div class="device-list">${n.devices.map(d=>`<div class="device"><div>${e(d.name)}<p class="help">本地提醒：${d.local_alarm?'已登记':'未启用'} · 原生推送：${d.push_registered?'已登记':'未配置'}</p></div><button type="button" data-remove="${e(d.id)}">移除</button></div>`).join('') || '<p class="help">尚未连接 MyDesk App。</p>'}</div></section>
    <form class="settings-card" data-config="github"><h2>微信步数 · GitHub</h2>${enabled('github','启用 GitHub 工作流',s.github)}${field('owner','仓库所有者',gh.owner)}${field('repo','仓库名称',gh.repo)}${field('workflow','工作流文件名',gh.workflow,'text','例如 wxstep.yml；步数输入参数为 steps')}${field('ref','运行分支',gh.ref || 'main')}${field('token','GitHub Token','','password')}${credential(gh)}<button class="primary">保存 GitHub 接入</button></form>
    ${serviceForms('gmail_accounts',s.gmail_accounts||{})}
    ${serviceForms('server_sources',s.server_sources||{})}
    <form class="settings-card" data-config="network"><h2>网络检测</h2>${enabled('network','启用联网、公网 IP 和节点检测',s.network.enabled)}<label for="nodes">网络节点</label><textarea id="nodes" name="nodes" placeholder="US=us.example.com\nJP=jp.example.com\nHK=hk.example.com">${e(s.network.nodes.map(n=>n.name+'='+n.host).join('\n'))}</textarea><p class="help">每行一个「名称=域名或 IP」。检测位置是运行 MyDesk 的机器。</p><button class="primary">保存网络检测</button></form>
    <form class="settings-card" data-config="tasks"><h2>自动任务</h2><label for="tasks">预期上报任务</label><textarea id="tasks" name="tasks" placeholder="glados=GLaDOS=36\n52pojie=吾爱破解=36">${e(Object.entries(s.expected_tasks).map(([id,t])=>`${id}=${t.name}=${t.max_age_hours}`).join('\n'))}</textarea><p class="help">每行「任务 ID=名称=超期小时数」。超过此时间未上报会进入需要处理。</p><h3>任务上报地址</h3><code id="webhook-url">${e(location.origin+w.path)}</code><p class="help">此地址包含上报密钥，仅提供给自己的工作流；本机地址不能被 GitHub Actions 直接访问。</p><button class="primary">保存任务设置</button></form>
    <form class="settings-card" id="password-form"><h2>账号</h2>${field('current_password','当前密码','','password')}${field('new_password','新密码','','password','至少 12 个字符；修改后需要重新登录。')}<button class="primary">修改密码</button></form>
  </div>`;
  $('#settings-content').querySelectorAll('form[data-config]').forEach(form=>form.onsubmit=async event=>{
    event.preventDefault();const data=Object.fromEntries(new FormData(form)),kind=form.dataset.config;let body;
    try {
      if(kind==='basic') body={timezone:data.timezone,history_days:Number(data.history_days)};
      else if(kind==='network') body={network:{enabled:data.enabled==='on',nodes:data.nodes.split('\n').filter(x=>x.trim()).map(line=>{const [name,host]=line.split('=');return {name:name.trim(),host:host?.trim()};})}};
      else if(kind==='multi') {
        const collection=form.dataset.collection,id=form.dataset.source,mail=collection==='gmail_accounts';
        const row={name:data.name.trim(),enabled:data.enabled==='on'};
        if(mail) {row.username=data.username.trim();row.limit=Number(data.limit);if(data.password)row.password=data.password.replaceAll(' ','');for(const key of ['host','mailbox','proxy'])if(s[collection]?.[id]?.[key])row[key]=s[collection][id][key];}
        else {row.provider=data.provider;row.url=data.url.trim().replace(/\/$/,'');if(row.provider==='1panel'){row.signature=data.signature;if(data.api_key)row.api_key=data.api_key;}else {row.email=data.email;if(data.password)row.password=data.password;}}
        body={[collection]:{...s[collection],[id]:row}};
      }
      else if(kind==='tasks') body={expected_tasks:Object.fromEntries(data.tasks.split('\n').filter(x=>x.trim()).map(line=>{const [id,name,hours]=line.split('=');return [id.trim(),{name:name?.trim(),max_age_hours:Number(hours)}];}))};
      else {delete data.enabled;if(kind==='gmail') data.limit=Number(data.limit);body={[kind]:form.elements.enabled.checked?data:null};}
      const button=form.querySelector('button');button.disabled=true;
      await request('/api/settings',{method:'PUT',body});await renderSettings();feedback('设置已保存。服务接入后将在后台同步。');
    } catch(error) {feedback(error.message,true);form.querySelector('button').disabled=false;}
  });
  $('#settings-content').querySelectorAll('select[name=provider]').forEach(select=>select.onchange=()=>select.closest('form').querySelectorAll('[data-provider-fields]').forEach(panel=>panel.hidden=panel.dataset.providerFields!==select.value));
  $('#settings-content').querySelectorAll('[data-service-remove]').forEach(button=>button.onclick=async()=>{if(!confirm('只移除当前接入？'))return;try {const collection=button.dataset.collection;const entries={...s[collection]};delete entries[button.dataset.serviceRemove];await request('/api/settings',{method:'PUT',body:{[collection]:entries}});await renderSettings();feedback('当前接入已移除。');}catch(error){feedback(error.message,true);}});
  $('#settings-content').querySelectorAll('[data-service-check]').forEach(button=>button.onclick=async()=>{button.disabled=true;try {const result=await request('/api/command',{method:'POST',body:{action:'service/check',payload:{kind:button.dataset.kind,id:button.dataset.serviceCheck}}});feedback(result.result?.message||result.message||'连接检查完成');}catch(error){feedback(error.message,true);}finally{button.disabled=false;}});
  $('#settings-content').querySelectorAll('[data-remove]').forEach(button=>button.onclick=async()=>{try {await request('/api/mobile/devices/'+button.dataset.remove,{method:'DELETE'});await renderSettings();feedback('通知设备已移除。');}catch(error){feedback(error.message,true);}});
  $('#password-form').onsubmit=async event=>{event.preventDefault();try {await request('/api/password',{method:'POST',body:Object.fromEntries(new FormData(event.currentTarget))});showLogin();$('#login-error').textContent='密码已修改，请重新登录。';}catch(error){feedback(error.message,true);}};
}
async function route() {
  if(!csrf) return;
  const page=location.hash==='#settings'?'settings':'dashboard';
  $('#dashboard').hidden=page!=='dashboard';$('#settings').hidden=page!=='settings';
  document.querySelectorAll('[data-page]').forEach(link=>{if(link.dataset.page===page)link.setAttribute('aria-current','page');else link.removeAttribute('aria-current');});
  if(page==='settings') {try {await renderSettings();}catch(error){feedback(error.message,true);}}
}
window.addEventListener('hashchange',route);
try {signedIn(await request('/api/session'));}catch {showLogin();}
