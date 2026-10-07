import './mydesk-card.js';
import {DeskClient} from './transport.js';
import {resolveAppearance} from './ui.js';
import {icon} from './icons.js';
import {renderSettings,openSettingsTarget} from './settings.js';
import {openInbox} from './inbox.js';
import {openStepPlan} from './step-plan.js';

const $=selector=>document.querySelector(selector);
let csrf,client,stopWatching,sessionInfo,page='dashboard',toastTimer;
let appearance='system';
try {appearance=localStorage.getItem('mydesk-appearance')||'system';} catch {}
const media=matchMedia('(prefers-color-scheme: dark)');
export function setAppearance(choice) {
  appearance=['system','dark','light'].includes(choice)?choice:'system';
  try {localStorage.setItem('mydesk-appearance',appearance);} catch {}
  const theme=resolveAppearance(appearance,media.matches);
  document.documentElement.dataset.theme=theme;
  document.querySelector('meta[name=theme-color]').content=theme==='dark'?'#181A1E':'#F5F3EF';
  if(client){client.darkMode=theme==='dark';for(const card of document.querySelectorAll('mydesk-card'))card.client=client;}
  document.querySelectorAll('[data-appearance]').forEach(button=>button.setAttribute('aria-pressed',String(button.dataset.appearance===appearance)));
}
setAppearance(appearance);
media.addEventListener('change',()=>setAppearance(appearance));
export function toast(message,error=false) {
  const node=$('#toast');clearTimeout(toastTimer);node.textContent=message;node.classList.toggle('error',error);node.hidden=false;
  toastTimer=setTimeout(()=>node.hidden=true,error?7000:4500);
}
function showLogin() {
  stopWatching?.();stopWatching=null;client?.close();client=null;csrf=null;sessionInfo=null;
  document.querySelectorAll('dialog').forEach(dialog=>dialog.close());
  for(const [id,container] of [['desk','dashboard'],['reminders-desk','reminders']]){
    $('#'+id).remove();const card=document.createElement('mydesk-card');card.id=id;$('#'+container).append(card);
  }
  $('#application').hidden=true;$('#login').hidden=false;
}
export async function request(path,{method='GET',body}={}) {
  const response=await fetch(path,{method,credentials:'same-origin',headers:{'Content-Type':'application/json',...(csrf?{'X-MyDesk-CSRF':csrf}:{})},...(body!==undefined?{body:JSON.stringify(body)}:{})});
  const result=await response.json();
  if(!response.ok) {if(response.status===401&&path!=='/api/login')showLogin();throw new Error(result.error||'请求失败');}
  return result;
}
function updateHeader(state=client?.state) {
  const timezone=state?.timezone||'Asia/Shanghai';
  $('#today').textContent=new Intl.DateTimeFormat('zh-CN',{timeZone:timezone,month:'long',day:'numeric',weekday:'long'}).format(new Date());
  const titles={dashboard:['MyDesk',state?.daily_quote?.text||'把今天的事情，安静地安排好。'],reminders:['提醒','给重要的事，一个合适的时间。'],settings:['设置','按你的习惯，照看每一处连接。']};
  $('#page-title').textContent=titles[page][0];$('#page-description').textContent=titles[page][1];
  $('#quote-credit').textContent=page==='dashboard'?[state?.daily_quote?.author,state?.daily_quote?.source].filter(Boolean).join(' · '):'';
}
async function signedIn(session) {
  csrf=session.csrf;sessionInfo=session;$('#login').hidden=true;$('#application').hidden=false;$('#account-name').textContent=session.username;
  client=new DeskClient(request,showLogin);client.darkMode=document.documentElement.dataset.theme==='dark';
  $('#desk').setConfig({mode:'dashboard'});$('#desk').client=client;
  $('#reminders-desk').setConfig({mode:'reminder'});$('#reminders-desk').client=client;
  stopWatching=await client.subscribe(state=>{updateHeader(state);$('.connection').hidden=true;},()=>{$('.connection').hidden=false;});
  if('serviceWorker' in navigator&&isSecureContext)navigator.serviceWorker.register('/sw.js').catch(()=>{});
  route();
}
export async function logout() {await request('/api/logout',{method:'POST'});showLogin();}
$('#show-password').onclick=()=>{const show=$('#password').type==='password';$('#password').type=show?'text':'password';$('#show-password').textContent=show?'隐藏':'显示';$('#show-password').setAttribute('aria-pressed',String(show));};
$('#login-form').onsubmit=async event=>{
  event.preventDefault();const form=event.currentTarget,button=form.querySelector('[type=submit]');button.disabled=true;button.textContent='正在登录…';$('#login-error').textContent='';
  try {await signedIn(await request('/api/login',{method:'POST',body:Object.fromEntries(new FormData(form))}));form.reset();}
  catch(error){$('#login-error').textContent=error.message;}
  finally{button.disabled=false;button.textContent='登录';}
};
async function route() {
  if(!csrf)return;
  page=['reminders','settings'].includes(location.hash.slice(1))?location.hash.slice(1):'dashboard';
  for(const name of ['dashboard','reminders','settings'])$('#'+name).hidden=name!==page;
  document.querySelectorAll('[data-page]').forEach(link=>{if(link.dataset.page===page)link.setAttribute('aria-current','page');else link.removeAttribute('aria-current');});
  updateHeader();window.scrollTo({top:0,behavior:'instant'});
  if(page==='settings')try {await renderSettings({request,toast,logout,setAppearance,getAppearance:()=>appearance,session:sessionInfo});}catch(error){toast(error.message,true);}
}
document.querySelectorAll('[data-icon]').forEach(node=>node.insertAdjacentHTML('afterbegin',icon(node.dataset.icon)));
window.addEventListener('hashchange',route);
document.addEventListener('mydesk-navigate',async event=>{
  try {
    const d=event.detail,cards=$('#desk').childrenCards||[],card=mode=>cards.find(c=>c.config.mode===mode);
    if(d.type==='inbox')openInbox(request,client.state,d.id);
    else if(d.type==='reminder')card('attention').showReminder(d.id);
    else if(d.type==='task')card('automation').showTask(d.id);
    else if(d.type==='server')card('server').showServer(d.id);
    else if(d.type==='network'){card('network').showNodes(d.id);}
    else if(d.type==='steps')openStepPlan(card('wxstep').controller,v=>card('wxstep').date(v,true));
    else if(d.type==='settings'){
      await renderSettings({request,toast,logout,setAppearance,getAppearance:()=>appearance,session:sessionInfo});
      openSettingsTarget(d.section,d.id);
    }
  }catch(error){toast(error.message,true);}
});
setInterval(()=>updateHeader(),60000);
try {await signedIn(await request('/api/session'));}catch {showLogin();}
