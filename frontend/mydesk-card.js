import {escapeHtml as e, safeUrl, parseSteps, formatDate, statusLabel, formatByteCount} from './ui.js';

const stylesheet = new URL('./mydesk.css', import.meta.url).href;
const controllers = new WeakMap();
const modes = ['attention','reminder','automation','server','network','wxstep','mail'];
const meta = {
  reminder: ['即时提醒','给重要的事一个时间','bell'],
  automation: ['自动任务','每一次执行，都有迹可循','activity'],
  server: ['服务器','你的服务，当前的状态','server'],
  network: ['网络','连接与关键节点','network'],
  wxstep: ['微信步数','提交目标步数，跟踪本次执行','steps'],
  mail: ['最近邮件','只看最近，打开 Gmail 处理','mail'],
};
function icon(name) {
  const paths = {
    bell:'<path d="M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9M10 21h4"/>',
    activity:'<path d="M3 12h4l3-8 4 16 3-8h4"/>',
    server:'<rect x="3" y="3" width="18" height="7" rx="2"/><rect x="3" y="14" width="18" height="7" rx="2"/><path d="M7 6.5h.01M7 17.5h.01M11 6.5h6M11 17.5h6"/>',
    network:'<path d="M4 9a13 13 0 0 1 16 0M7 13a8 8 0 0 1 10 0M10 17a3 3 0 0 1 4 0M12 21h.01"/>',
    steps:'<path d="M6 3v4l4 3-2 5H3m8 6 3-7 4-2V6m-5 8 5 7h3"/>',
    mail:'<rect x="3" y="5" width="18" height="14" rx="3"/><path d="m3 6 9 7 9-7"/>',
    arrow:'<path d="M5 12h14m-5-5 5 5-5 5"/>',
    check:'<path d="m5 12 4 4L19 6"/>',
    alert:'<path d="m12 3 10 18H2L12 3Zm0 6v5m0 3h.01"/>',
    plus:'<path d="M12 5v14M5 12h14"/>',
    desk:'<rect x="3" y="4" width="18" height="13" rx="3"/><path d="M8 21h8m-4-4v4M7 8h4m-4 4h8"/>',
  };
  return `<svg viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round">${paths[name] || paths.activity}</svg>`;
}
const badge = status => `<span class="badge ${e(status)}"><span class="dot"></span>${e(statusLabel(status))}</span>`;
const empty = (title, detail='') => `<div class="empty"><strong>${e(title)}</strong>${detail ? `<p>${e(detail)}</p>` : ''}</div>`;

class DeskController {
  constructor(client) { this.client=client; this.watchers=new Set(); this.state=null; this.error=null; }
  add(listener) {
    this.watchers.add(listener);
    listener(this.state, this.error);
    if (!this.subscription) {
      this.subscription = this.client.subscribe(state => {
        this.state=state; this.error=null; this.broadcast();
      }, error => {this.error=error;this.broadcast();}).then(unsubscribe => {
        if (!this.watchers.size) { unsubscribe(); this.subscription=null; }
        return unsubscribe;
      }).catch(err => { this.error=err.message || '连接失败，请检查 MyDesk 集成'; this.broadcast(); this.subscription=null; });
    }
    return () => {
      this.watchers.delete(listener);
      if (!this.watchers.size && this.subscription) {
        this.subscription.then(stop => stop?.()); this.subscription=null;
      }
    };
  }
  broadcast() { for (const listener of this.watchers) listener(this.state,this.error); }
  async command(action,payload={}) {
    return this.client.command(action,payload);
  }
}

class MyDeskCard extends HTMLElement {
  constructor() {
    super(); this.attachShadow({mode:'open'}); this._busy=false;
    this._listener=(state,error) => { const recovered=this._error && !error; this._state=state; this._error=error; if(recovered)this.message('',false); this.render(); };
  }
  setConfig(config) {
    const mode=config.mode || 'dashboard';
    if (mode !== 'dashboard' && !modes.includes(mode)) throw new Error('MyDesk mode 无效');
    if(this._shell && this._shell!==mode) { this._shell=null; this.childrenCards=null; this._contentHtml=null; }
    this.config={...config,mode}; this.render();
  }
  set client(value) {
    this._client=value;
    this.toggleAttribute('dark',value.darkMode ?? window.matchMedia('(prefers-color-scheme: dark)').matches);
    if (this.isConnected) this.connect();
    if (this.childrenCards) for (const card of this.childrenCards) card.client=value;
  }
  connectedCallback() {
    this.render(); this.connect();
    this._clock=setInterval(() => this.updateClock(),60000);
  }
  disconnectedCallback() {
    clearInterval(this._clock); this._remove?.(); this._remove=null; this._connection=null;
  }
  connect() {
    const connection=this._client;
    if (!connection || connection===this._connection) return;
    this._remove?.(); this._connection=connection;
    if (!controllers.has(connection)) controllers.set(connection,new DeskController(this._client));
    this.controller=controllers.get(connection);
    this._remove=this.controller.add(this._listener);
  }
  async action(action,payload) {
    if (this._busy) return;
    this._busy=true; this.message('',false); this.shadowRoot.querySelectorAll('button').forEach(b=>b.disabled=true);
    try { return await this.controller.command(action,payload); }
    catch (err) { this.message(err.message || '操作失败，请稍后重试',true); throw err; }
    finally { this._busy=false; this.shadowRoot.querySelectorAll('button').forEach(b=>b.disabled=false); this.render(); }
  }
  message(text,error=false) {
    const node=this.shadowRoot.querySelector('.feedback');
    if (node) { node.textContent=text; node.classList.toggle('error',error); }
  }
  date(value,full=false) { return formatDate(value,this._state?.timezone,full); }
  updateClock() {
    const node=this.shadowRoot.querySelector('.clock');
    if (node) node.textContent=new Intl.DateTimeFormat('zh-CN',{timeZone:this._state?.timezone || 'Asia/Shanghai',month:'long',day:'numeric',weekday:'long',hour:'2-digit',minute:'2-digit',hour12:false}).format(new Date());
  }
  render() {
    if (!this.config) return;
    if (!this._shell) {
      const mode=this.config.mode;
      this.shadowRoot.innerHTML=`<link rel="stylesheet" href="${stylesheet}">${mode==='dashboard' ? `
        <main class="workbench"><header class="topbar"><div class="brand"><span class="brand-icon">${icon('desk')}</span><div><h1>MyDesk<span>我的工作台</span></h1><p>让今天的事情，清晰一点。</p></div></div><div class="top-meta"><time class="clock"></time><span class="connection">正在连接</span></div></header><div class="grid"></div><footer>MYDESK <span>个人状态 · 通知 · 快捷操作</span></footer></main>` : `
        <section class="card ${e(mode)}">${mode==='attention' ? '' : `<header class="card-header"><div class="card-icon">${icon(meta[mode][2])}</div><div><h2>${meta[mode][0]}</h2><p>${meta[mode][1]}</p></div></header>`}<div class="content"></div>${mode==='reminder' ? this.reminderForm() : ''}${mode==='wxstep' ? `<form class="steps-form"><label for="steps">输入需要提交的步数</label><div class="step-input"><input id="steps" name="steps" type="text" inputmode="numeric" value="18888" aria-describedby="steps-help"><span>步</span></div><p id="steps-help" class="hint">30000 步以内，且不能低于当前微信运动步数</p><button class="primary" type="submit">提交步数 ${icon('arrow')}</button></form>` : ''}<p class="feedback" aria-live="polite"></p></section>`}`;
      this._shell=mode;
      if (mode==='dashboard') {
        const grid=this.shadowRoot.querySelector('.grid');
        this.childrenCards=modes.map(mode=>{const card=document.createElement('mydesk-card'); card.setConfig({mode}); card.className=`tile-${mode}`; grid.append(card); if(this._client) card.client=this._client; return card;});
      }
      this.bind();
    }
    if (this.config.mode==='dashboard') {
      this.updateClock();
      const node=this.shadowRoot.querySelector('.connection');
      node.textContent=this._error ? '连接异常' : this._state ? '已连接' : '正在连接';
      node.classList.toggle('connected',!!this._state && !this._error);
      return;
    }
    const content=this.shadowRoot.querySelector('.content');
    if (!this._state) { content.innerHTML=empty(this._error || '正在连接工作台',this._error ? '请检查 MyDesk 服务与登录状态' : '等待服务器状态'); return; }
    const mode=this.config.mode;
    const html=this[`${mode}Content`]();
    if(html!==this._contentHtml) { content.innerHTML=html; this._contentHtml=html; }
    if(this._error) this.message(this._error,true);
    if(mode==='wxstep') {
      const active=['dispatching','queued','running','tracking_error'].includes(this._state.wxstep?.status);
      this.shadowRoot.querySelector('.steps-form button').disabled=active || !this._state.configured.github;
    }
  }
  bind() {
    this.shadowRoot.addEventListener('click', async event => {
      const button=event.target.closest('button[data-action]');
      if(!button) return;
      const {action,id,minutes}=button.dataset;
      try {
        if(action==='new') { const form=this.shadowRoot.querySelector('.reminder-form'); form.hidden=!form.hidden; button.setAttribute('aria-expanded',String(!form.hidden)); if(!form.hidden) form.querySelector('input').focus(); }
        else if(action==='preset') this.shadowRoot.querySelector('[name=time]').value=button.dataset.value;
        else if(action==='complete' || action==='cancel' || action==='snooze') { await this.action('reminder/action',{id,action,minutes:Number(minutes || 10)}); this.message(action==='snooze' ? '已延后提醒' : '提醒已处理'); }
        else if(action==='history') await this.showHistory();
        else if(action==='more-history') await this.showHistory(Number(button.dataset.before),true);
        else if(action==='server-detail') this.showServer(id);
        else if(action==='release') { if(confirm('请先在 GitHub Actions 确认本次任务。结束本地跟踪不会取消 GitHub 任务，继续？')) await this.action('wxstep/release',{confirmed:true}); }
        else if(action==='close') this.shadowRoot.querySelector('dialog')?.close();
      } catch { /* feedback handled by action */ }
    });
    this.shadowRoot.querySelector('.reminder-form')?.addEventListener('submit',async event=>{
      event.preventDefault(); const form=event.currentTarget; const data=new FormData(form);
      try {await this.action('reminder/create',{title:data.get('title'),time:data.get('time')}); form.reset(); form.hidden=true; this.shadowRoot.querySelector('.add-reminder').setAttribute('aria-expanded','false'); this.message('提醒已创建');} catch {}
    });
    this.shadowRoot.querySelector('.steps-form')?.addEventListener('submit',async event=>{
      event.preventDefault();
      try {const steps=parseSteps(new FormData(event.currentTarget).get('steps')); await this.action('wxstep/submit',{steps});} catch(err) {this.message(err.message || '提交失败',true);}
    });
    this.shadowRoot.addEventListener('change',async event=>{
      if(!event.target.matches('select[data-id]') || !event.target.value) return;
      try {await this.action('reminder/action',{id:event.target.dataset.id,action:'snooze',minutes:Number(event.target.value)}); this.message('已延后提醒');} catch {}
    });
  }
  reminderForm() {
    return `<button class="secondary add-reminder" data-action="new" aria-expanded="false" aria-controls="reminder-form">${icon('plus')} 新建提醒</button><form id="reminder-form" class="reminder-form" hidden><label for="title">提醒内容</label><input id="title" name="title" maxlength="200" placeholder="例如：续费 VPS" required><label for="time">时间</label><div class="presets">${[['30分钟后','30分钟后'],['1小时后','1小时后'],['今晚','今天20:00'],['明早','明天09:00']].map(([label,value])=>`<button type="button" data-action="preset" data-value="${value}">${label}</button>`).join('')}</div><input id="time" name="time" placeholder="明天09:00 或 2026-10-05 15:00" aria-describedby="time-help" required><p id="time-help" class="hint">按工作台时区解析，支持指定日期时间。</p><button class="primary" type="submit">创建提醒</button></form>`;
  }
  attentionContent() {
    const items=this._state.attention;
    return `<div class="attention-heading ${items.length ? 'needs-action':'all-clear'}"><span class="attention-icon">${icon(items.length ? 'alert':'check')}</span><div><h2>${items.length ? '需要处理':'暂无需要处理的事项'}</h2><p>${items.length ? `${items.length} 项需要你留意`:'已接入的数据源当前没有待处理异常'}</p></div><span class="count">${items.length ? String(items.length).padStart(2,'0'):'ALL CLEAR'}</span></div>${items.length ? `<div class="attention-list">${items.map(item=>`<div class="attention-item"><strong>${e(item.title)}</strong><span>${e(item.message)}</span></div>`).join('')}</div>`:''}`;
  }
  reminderContent() {
    const reminders=this._state.reminders;
    return `${reminders.length ? reminders.map(r=>`<article class="reminder-row"><div class="reminder-time"><time>${this.date(r.remind_at)}</time><span>${new Intl.DateTimeFormat('zh-CN',{timeZone:this._state.timezone,month:'numeric',day:'numeric'}).format(new Date(r.remind_at))}</span></div><div class="row-main"><strong>${e(r.title)}</strong><span>${new Date(r.remind_at)<=new Date() ? '已到时间' : statusLabel(r.status)}</span></div><button class="icon-button" data-action="complete" data-id="${e(r.id)}" aria-label="完成提醒：${e(r.title)}">${icon('check')}</button><select aria-label="稍后提醒：${e(r.title)}" data-id="${e(r.id)}"><option value="">稍后</option><option value="10">10分钟</option><option value="30">30分钟</option><option value="60">1小时</option></select><button class="text-button" data-action="cancel" data-id="${e(r.id)}">取消</button></article>`).join('') : empty('还没有待办提醒','想到一件事，就给它设个时间。')}<div class="card-note">${this._state.configured.notifications ? '已登记 MyDesk App；定时提醒由手机本地执行' : '请安装并登录 MyDesk App，以同步手机提醒'}</div>`;
  }
  automationContent() {
    return `${this._state.tasks.length ? this._state.tasks.map(t=>`<article class="list-row"><div class="row-main"><strong>${e(t.task_name)}</strong><span>${e(t.message)}</span></div><div class="row-right">${badge(t.status)}<time>${this.date(t.timestamp)}</time></div></article>`).join('') : empty('等待任务上报','现有 GitHub Actions 接入后，执行结果会显示在这里。')}<button class="text-button history-link" data-action="history">查看历史 ${icon('arrow')}</button>`;
  }
  serverContent() {
    const feed=this._state.feeds.servers;
    if(!this._state.configured.servers) return empty('服务器监控尚未接入','添加 1Panel v2 或 Beszel，可分别查看每台服务器。');
    if(!feed || feed.data.error) return empty(feed?.data.error || '等待服务器数据');
    return `${(feed.data.items || []).map(s=>`<button class="server-row" data-action="server-detail" data-id="${e(s.id)}"><div class="server-line"><strong>${e(s.name)}</strong>${badge(s.status)}</div>${s.error?`<p class="hint">${e(s.error)}</p>`:`<div class="metrics">${[['CPU',s.cpu],['RAM',s.ram],['Disk',s.disk]].map(([label,value])=>`<div><span>${label}</span><strong>${typeof value==='number' ? value.toFixed(0)+'%' : '—'}</strong><div class="meter"><i style="width:${typeof value==='number' ? Math.max(0,Math.min(100,value)) : 0}%"></i></div></div>`).join('')}</div>`}</button>`).join('') || empty('等待已接入的服务器数据')}${this.feedTime(feed)}`;
  }
  networkContent() {
    const feed=this._state.feeds.network;
    if(!this._state.configured.network) return empty('网络检测尚未启用');
    if(!feed || feed.data.error) return empty(feed?.data.error || '等待网络检测');
    const data=feed.data;
    return `<div class="network-summary"><div><span>Internet</span>${badge(data.online===true ? 'up':data.online===false ? 'down':'unknown')}</div><div><span>Ping</span><strong>${data.ping==null ? '—':e(data.ping)+' <small>ms</small>'}</strong></div></div><div class="ip-line"><span>Public IP</span><code>${e(data.public_ip || '未获取')}</code></div>${(data.nodes||[]).map(node=>`<div class="node-row"><strong>${e(node.name)}</strong><span>${node.ping==null ? '—':e(node.ping)+' ms'}</span>${badge(node.online ? 'up':'down')}</div>`).join('')}${this.feedTime(feed)}<p class="hint">检测位置：部署 MyDesk 的服务器</p>`;
  }
  wxstepContent() {
    const job=this._state.wxstep;
    if(!this._state.configured.github) return empty('微信步数尚未配置','在服务端设置仓库、Workflow 和 Token 后即可提交。');
    return job ? `<div class="job-result">${badge(job.status)}<span>${e(job.steps)} 步</span><p>${e(job.message)}</p>${job.url ? `<a class="text-link" href="${safeUrl(job.url)}" target="_blank" rel="noopener noreferrer">查看本次任务 ${icon('arrow')}</a>`:''}${job.status==='tracking_error' ? '<button class="text-button" data-action="release">检查后结束本地跟踪</button>':''}</div>` : '<p class="hint">等待操作</p>';
  }
  mailContent() {
    const feed=this._state.feeds.mail;
    if(!this._state.configured.mail) return empty('Gmail 尚未接入','接入 IMAP 后，展示最近 3–5 封邮件。');
    if(!feed || feed.data.error) return empty(feed?.data.error || '等待邮件同步');
    const accounts=feed.data.accounts||[],failures=accounts.filter(account=>account.error);
    return `<div class="mail-meta"><span>全部邮箱 · 最近 3 封</span><span>${e(feed.data.unread)} 未读${feed.data.unread_complete===false?' · 部分邮箱':''}</span></div>${failures.map(account=>`<p class="hint">${e(account.name)}：${e(account.error)}</p>`).join('')}${(feed.data.items||[]).slice(0,3).map(mail=>`<a class="mail-row" href="${safeUrl(accounts.find(account=>account.id===mail.account_id)?.url||'https://mail.google.com/')}" target="_blank" rel="noopener noreferrer"><span class="mail-avatar">${e(mail.sender.slice(0,1).toUpperCase())}</span><div class="row-main"><strong>${e(mail.sender)}${mail.unread ? '<span class="unread-dot" aria-label="未读"></span>':''}</strong><span>${e(mail.subject)}</span>${mail.account_name?`<small>${e([mail.account_name,mail.account_email||accounts.find(account=>account.id===mail.account_id)?.username].filter(Boolean).join(" · "))}</small>`:''}</div><time>${this.date(mail.received_at)}</time></a>`).join('') || empty(failures.length?'部分邮箱未完成同步':'收件箱暂无邮件')}<a class="text-link" href="https://mail.google.com/" target="_blank" rel="noopener noreferrer">打开 Gmail ${icon('arrow')}</a>${this.feedTime(feed)}`;
  }
  feedTime(feed) { return `<p class="feed-time">${feed.stale ? '数据已过期 · ':'更新于 '}${this.date(feed.updated_at)}</p>`; }
  dialog(title,body) {
    this.shadowRoot.querySelector('dialog')?.remove();
    const dialog=document.createElement('dialog');
    dialog.innerHTML=`<div class="dialog-heading"><h2>${e(title)}</h2><button class="text-button" data-action="close">关闭</button></div><div class="dialog-content">${body}</div>`;
    this.shadowRoot.append(dialog); dialog.showModal();
    dialog.addEventListener('close',()=>dialog.remove(),{once:true});
  }
  async showHistory(before=null,append=false) {
    const items=await this.action('history',{limit:50,...(before?{before}:{})});
    const html=items.map(t=>`<article class="list-row"><div class="row-main"><strong>${e(t.task_name)}</strong><span>${e(t.message)}</span><time>${this.date(t.timestamp,true)}</time></div>${badge(t.status)}</article>`).join('');
    const more=items.length===50 ? `<button class="secondary" data-action="more-history" data-before="${items.at(-1).id}">加载更多</button>`:'';
    if(append) { const body=this.shadowRoot.querySelector('.dialog-content'); body.querySelector('[data-action=more-history]')?.remove(); body.insertAdjacentHTML('beforeend',html+more); }
    else this.dialog('自动任务记录',html+more || empty('还没有执行记录'));
  }
  showServer(id) {
    const server=this._state.feeds.servers.data.items.find(s=>s.id===id);
    if(!server) return;
    const networkRows=server.network && typeof server.network==='object' && !Array.isArray(server.network) ? [['累计上传',formatByteCount(server.network.sent_bytes)],['累计下载',formatByteCount(server.network.received_bytes)]] : [['Network',server.network==null?'—':String(server.network)]];
    const rows=[['状态',statusLabel(server.status)],['CPU',server.cpu==null?'—':`${server.cpu}%`],['RAM',server.ram==null?'—':`${server.ram}%`],['Disk',server.disk==null?'—':`${server.disk}%`],['Load',server.load?.join(' / ') || '—'],...networkRows,['Temperature',server.temperature==null?(server.provider==='1panel'?'接口未提供':'—'):`${server.temperature} °C`]];
    this.dialog(server.name,rows.map(([name,value])=>`<div class="detail-row"><span>${name}</span><strong>${e(value)}</strong></div>`).join('')+(this._state.links.beszel ? `<a class="text-link" href="${safeUrl(this._state.links.beszel)}" target="_blank" rel="noopener noreferrer">在 Beszel 查看完整监控 ${icon('arrow')}</a>`:''));
  }
}
customElements.define('mydesk-card',MyDeskCard);
