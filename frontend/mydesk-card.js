import {escapeHtml as e,safeUrl,parseSteps,formatDate,statusLabel,formatByteCount,formatMetric,selectMail,countdownTime} from './ui.js';
import {icon} from './icons.js';
import {openDialog,confirmDialog} from './dialog.js';
import {mountPicker} from './time-picker.js';

const stylesheet=new URL('./mydesk.css',import.meta.url).href;
const controllers=new WeakMap();
const modes=['attention','wxstep','mail','automation','server','network'];
const meta={reminder:['待办提醒','bell'],automation:['自动任务','activity'],server:['服务器','server'],network:['网络连通性','network'],wxstep:['微信步数','steps'],mail:['最近邮件','mail']};
const badge=status=>`<span class="badge ${e(status)}"><span class="dot"></span>${e(statusLabel(status))}</span>`;
const empty=(title,detail='')=>`<div class="empty"><strong>${e(title)}</strong>${detail?`<p>${e(detail)}</p>`:''}</div>`;
class DeskController {
  constructor(client){this.client=client;this.watchers=new Set();this.state=null;this.error=null;}
  add(listener){
    this.watchers.add(listener);listener(this.state,this.error);
    if(!this.subscription)this.subscription=this.client.subscribe(state=>{this.state=state;this.error=null;this.broadcast();},error=>{this.error=error;this.broadcast();}).then(stop=>{if(!this.watchers.size){stop();this.subscription=null;}return stop;});
    return()=>{this.watchers.delete(listener);if(!this.watchers.size&&this.subscription){this.subscription.then(stop=>stop?.());this.subscription=null;}};
  }
  broadcast(){for(const listener of this.watchers)listener(this.state,this.error);}
  command(action,payload={}){return this.client.command(action,payload);}
}
class MyDeskCard extends HTMLElement {
  constructor(){super();this.attachShadow({mode:'open'});this._busy=false;this.mailAccount='';this.timerMode='date';this._listener=(state,error)=>{this._state=state;this._error=error;this.render();};}
  setConfig(config){const mode=config.mode||'dashboard';if(!['dashboard','reminder',...modes].includes(mode))throw new Error('MyDesk mode 无效');this.config={...config,mode};this.render();}
  set client(value){this._client=value;this.toggleAttribute('dark',value.darkMode);if(this.isConnected)this.connect();for(const card of this.childrenCards||[])card.client=value;}
  connectedCallback(){this.render();this.connect();}
  disconnectedCallback(){this._remove?.();this._remove=null;this._connection=null;this.shadowRoot.querySelector('.time-picker')?._observer?.disconnect();}
  connect(){if(!this._client||this._client===this._connection)return;this._remove?.();this._connection=this._client;if(!controllers.has(this._client))controllers.set(this._client,new DeskController(this._client));this.controller=controllers.get(this._client);this._remove=this.controller.add(this._listener);}
  message(text,error=false){const node=this.shadowRoot.querySelector('.feedback');if(node){node.textContent=text;node.classList.toggle('error',error);}}
  async action(action,payload){
    if(this._busy)throw new Error('操作正在进行，请稍候');this._busy=true;this.message('');this.render();
    try{return await this.controller.command(action,payload);}catch(error){this.message(error.message||'操作失败',true);throw error;}finally{this._busy=false;this.render();}
  }
  date(value,full=false){return formatDate(value,this._state?.timezone,full);}
  render(){
    if(!this.config)return;
    const mode=this.config.mode;
    if(!this._shell){
      this.shadowRoot.innerHTML=`<link rel="stylesheet" href="${stylesheet}">${mode==='dashboard'?'<div class="grid"></div>':mode==='reminder'?`<div class="reminders-layout"><section class="card compose">${this.reminderForm()}<p class="feedback" role="status"></p></section><section class="card reminder-list"><header class="card-header"><span class="card-icon">${icon('bell')}</span><h2>待办提醒</h2><span class="reminder-count"></span></header><div class="content"></div></section></div>`:`<section class="card ${e(mode)}">${mode==='attention'?'':`<header class="card-header"><span class="card-icon">${icon(meta[mode][1])}</span><h2>${meta[mode][0]}</h2>${mode==='mail'?'<div class="mail-filter"></div>':mode==='network'?'<button class="icon-button" data-action="nodes" aria-label="查看所有网络节点">'+icon('arrow')+'</button>':''}</header>`}<div class="content"></div>${mode==='wxstep'?'<form class="steps-form"><label for="steps">目标步数</label><div class="step-input"><input id="steps" name="steps" inputmode="numeric" autocomplete="off" placeholder="填写步数" aria-describedby="steps-help" required><button class="primary" type="submit">提交</button></div><p id="steps-help" class="hint">30000 步以内，且不能低于当前微信运动步数</p></form>':''}<p class="feedback" role="status"></p></section>`}`;
      this._shell=mode;
      if(mode==='dashboard'){
        const grid=this.shadowRoot.querySelector('.grid');grid.innerHTML='<div class="column primary-column"></div><div class="column secondary-column"></div>';
        this.childrenCards=modes.map((childMode,index)=>{const card=document.createElement('mydesk-card');card.className=`tile-${childMode}`;card.setConfig({mode:childMode});grid.querySelector(index<3?'.primary-column':'.secondary-column').append(card);if(this._client)card.client=this._client;return card;});
      }
      this.bind();
    }
    if(mode==='dashboard')return;
    const content=this.shadowRoot.querySelector('.content');
    if(!this._state){content.innerHTML=empty(this._error?'暂时无法连接':'正在同步',this._error?'连接恢复后会自动更新。':'等待工作台数据。');return;}
    if(mode==='reminder'&&!this.pickerMounted){this.mountTime();this.pickerMounted=true;}
    const html=this[mode+'Content']();if(html!==this._contentHtml){content.innerHTML=html;this._contentHtml=html;}
    if(mode==='reminder')this.shadowRoot.querySelector('.reminder-count').textContent=`${this._state.reminders.length} 项`;
    if(mode==='mail')this.renderMailFilter();
    this.shadowRoot.querySelectorAll('form button[type=submit]').forEach(button=>{button.disabled=this._busy||!!this._error;});
    if(mode==='wxstep')this.shadowRoot.querySelector('.steps-form button').disabled=this._busy||!!this._error||!this._state.configured.github||['dispatching','queued','running','tracking_error'].includes(this._state.wxstep?.status);
  }
  bind(){
    this.shadowRoot.addEventListener('click',async event=>{
      const button=event.target.closest('button[data-action]');if(!button)return;
      const {action,id,minutes}=button.dataset;
      try{
        if(action==='toggle-time'){this.timerMode=this.timerMode==='date'?'countdown':'date';button.textContent=this.timerMode==='date'?'切换倒计时':'切换日期';this.mountTime();}
        else if(['complete','cancel','snooze'].includes(action)){await this.action('reminder/action',{id,action,...(minutes?{minutes:Number(minutes)}:{})});this.shadowRoot.querySelector('dialog')?.close();this.message(action==='snooze'?'提醒已延后':'提醒已处理');}
        else if(action==='reminder-detail')this.showReminder(id);
        else if(action==='history')await this.showHistory();
        else if(action==='more-history')await this.showHistory(Number(button.dataset.before),true);
        else if(action==='server-detail')this.showServer(id);
        else if(action==='nodes')this.showNodes();
        else if(action==='release'&&await confirmDialog('结束本地跟踪','请先在 GitHub Actions 确认本次任务。结束跟踪不会取消正在执行的任务。'))await this.action('wxstep/release',{confirmed:true});
      }catch(error){this.shadowRoot.querySelector('dialog')?.feedback?.(error.message,true);}
    });
    this.shadowRoot.querySelector('.reminder-form')?.addEventListener('submit',async event=>{
      event.preventDefault();const form=event.currentTarget,data=new FormData(form);
      try{const time=this.timerMode==='countdown'?countdownTime(data.get('hour'),data.get('minute'),data.get('second')):`${data.get('date')} ${data.get('hour')}:${data.get('minute')}`;await this.action('reminder/create',{title:data.get('title').trim(),time});form.querySelector('[name=title]').value='';this.message('提醒已创建，已登录的 App 会同步此提醒。');}catch(error){this.message(error.message,true);}
    });
    this.shadowRoot.querySelector('.steps-form')?.addEventListener('submit',async event=>{event.preventDefault();const form=event.currentTarget;try{await this.action('wxstep/submit',{steps:parseSteps(new FormData(form).get('steps'))});form.querySelector('input').value='';}catch(error){this.message(error.message,true);}});
  }
  reminderForm(){return `<form class="reminder-form"><label for="reminder-title">提醒事项</label><input id="reminder-title" name="title" maxlength="200" placeholder="有什么事需要提醒？" required><div class="time-picker"></div><p class="time-preview"></p><div class="form-actions"><button class="secondary" type="button" data-action="toggle-time">切换倒计时</button><button class="primary" type="submit">创建提醒 ${icon('plus')}</button></div></form>`;}
  mountTime(){const container=this.shadowRoot.querySelector('.time-picker');mountPicker(container,this.timerMode,this._state?.timezone);const preview=()=>{const d=new FormData(this.shadowRoot.querySelector('.reminder-form'));this.shadowRoot.querySelector('.time-preview').textContent=this.timerMode==='date'?`${d.get('date')} ${d.get('hour')}:${d.get('minute')} · ${this._state?.timezone||'Asia/Shanghai'}`:`${Number(d.get('hour'))} 小时 ${Number(d.get('minute'))} 分钟 ${Number(d.get('second'))} 秒后提醒`;};container.onchange=preview;preview();}
  attentionContent(){const items=this._state.attention;return `<header class="attention-heading"><h2>需要处理</h2><span>${items.length} 项</span></header><div class="attention-list">${items.length?items.map(item=>`<article class="attention-item"><span class="attention-indicator"></span><div><strong>${e(item.title)}</strong><p>${e(item.message)}</p></div></article>`).join(''):`<div class="all-clear">${icon('check')}<strong>一切井然有序</strong><p>当前没有需要处理的事项。</p></div>`}</div>`;}
  reminderContent(){return this._state.reminders.length?this._state.reminders.map(r=>`<article class="reminder-row"><button class="reminder-summary" data-action="reminder-detail" data-id="${e(r.id)}"><strong>${e(r.title)}</strong><span>${icon('clock')}<time>${this.date(r.remind_at,true)}</time>${badge(r.status)}</span></button><button class="icon-button" data-action="complete" data-id="${e(r.id)}" aria-label="完成提醒：${e(r.title)}">${icon('check')}</button></article>`).join(''):empty('还没有待办提醒','想到一件事，就给它设个时间。');}
  showReminder(id){const r=this._state.reminders.find(r=>r.id===id);if(!r)return;this.dialog('提醒详情',`<div class="reminder-detail-title"><h3>${e(r.title)}</h3>${badge(r.status)}<p>${this.date(r.remind_at,true)} · ${e(this._state.timezone)}</p></div><div class="snooze-section"><span class="muted">稍后提醒</span><div class="snooze-options">${[10,30,60].map(n=>`<button class="secondary" data-action="snooze" data-id="${e(id)}" data-minutes="${n}">${n===60?'1 小时':n+' 分钟'}</button>`).join('')}</div></div><div class="dialog-actions"><button class="primary" data-action="complete" data-id="${e(id)}">完成提醒</button><button class="secondary" data-action="cancel" data-id="${e(id)}">取消提醒</button></div>`);}
  automationContent(){return `<div class="task-list">${this._state.tasks.length?this._state.tasks.map(t=>`<article class="list-row"><span class="task-symbol">${icon('github')}</span><div class="row-main"><strong>${e(t.task_name)}</strong><span>${e(t.message)}</span></div><div class="row-right">${badge(t.status)}<time>${this.date(t.timestamp,true)}</time></div></article>`).join(''):empty('等待任务执行结果','在设置中添加 GitHub 工作流。')}</div><button class="text-button history-link" data-action="history">执行记录 ${icon('arrow')}</button>`;}
  serverContent(){const feed=this._state.feeds.servers;if(!this._state.configured.servers)return empty('还没有接入服务器','前往设置，添加 1Panel v2 或 Beszel。');if(!feed||feed.data.error)return empty(feed?.data.error||'等待服务器数据');return `${(feed.data.items||[]).map(s=>`<button class="server-row" data-action="server-detail" data-id="${e(s.id)}"><div class="server-line"><strong>${e(s.name)}</strong>${badge(s.status)}${icon('chevron')}</div>${s.error?`<p class="hint">${e(s.error)}</p>`:`<div class="metrics">${[['CPU',s.cpu],['内存',s.ram],['磁盘',s.disk]].map(([label,value])=>`<div><span>${label}</span><strong>${formatMetric(value)}${typeof value==='number'?'%':''}</strong><div class="meter"><i style="width:${typeof value==='number'?Math.max(0,Math.min(100,value)):0}%"></i></div></div>`).join('')}</div>`}</button>`).join('')||empty('等待服务器数据')}${this.feedTime(feed)}`;}
  networkContent(){const feed=this._state.feeds.network;if(!this._state.configured.network)return empty('网络检测未启用','可在设置中添加需要关注的节点。');if(!feed||feed.data.error)return empty(feed?.data.error||'等待网络检测');const d=feed.data,nodes=d.nodes||[],healthy=nodes.filter(n=>n.online).length;return `<div class="network-summary"><div><span>服务器网络</span>${badge(d.online===true?'up':d.online===false?'down':'unknown')}</div><div><span>延迟</span><strong>${d.ping==null?'—':Math.round(d.ping)}<small> ms</small></strong></div><div><span>节点状态</span><strong>${healthy}<small> / ${nodes.length}</small></strong></div></div><div class="ip-line"><span>公网 IP</span><code>${e(d.public_ip||'未获取')}</code></div>${nodes.slice(0,3).map(n=>this.nodeRow(n)).join('')}${nodes.length>3?`<button class="text-button" data-action="nodes">查看全部 ${nodes.length} 个节点 ${icon('arrow')}</button>`:''}${this.feedTime(feed)}`;}
  nodeRow(node){return `<article class="node-row"><div><strong>${e(node.name)}</strong>${node.host?`<small>${e(node.host)}</small>`:''}</div><span>${node.ping==null?'—':Math.round(node.ping)+' ms'}</span>${badge(node.online?'up':'down')}</article>`;}
  showNodes(){const nodes=this._state.feeds.network?.data?.nodes||[];const dialog=this.dialog('网络节点',`<div class="filter-bar"><input type="search" aria-label="搜索网络节点" placeholder="搜索名称或地址"><select aria-label="节点状态"><option value="all">全部节点</option><option value="up">在线</option><option value="down">离线</option></select></div><p class="hint">检测位置：部署 MyDesk 的服务器</p><div class="nodes-results"></div>`);const update=()=>{const q=dialog.querySelector('input').value.toLowerCase(),status=dialog.querySelector('select').value;const visible=nodes.filter(n=>`${n.name} ${n.host||''}`.toLowerCase().includes(q)&&(status==='all'||(status==='up')===!!n.online));dialog.querySelector('.nodes-results').innerHTML=visible.map(n=>this.nodeRow(n)).join('')||empty('没有匹配的节点');};dialog.querySelector('input').oninput=update;dialog.querySelector('select').onchange=update;update();}
  wxstepContent(){const job=this._state.wxstep;if(!this._state.configured.github)return empty('微信步数尚未接入','在设置的 GitHub 任务中单独配置。');return job?`<div class="job-result"><span class="hint">最近提交</span><div><strong>${Number(job.steps).toLocaleString('zh-CN')}<small> 步</small></strong><span class="badge ${e(job.status)}">${e(job.status==='success'?'提交成功':statusLabel(job.status))}</span></div>${['failed','tracking_error'].includes(job.status)?`<p class="hint">${e(job.message)}</p>`:''}${job.status==='tracking_error'?'<button class="text-button" data-action="release">检查后结束本地跟踪</button>':''}</div>`:'<p class="hint">还没有提交记录</p>';}
  renderMailFilter(){const accounts=this._state.feeds.mail?.data?.accounts||[];if(this.mailAccount&&!accounts.some(a=>a.id===this.mailAccount)){this.mailAccount='';this._contentHtml=null;this.render();return;}const html=`<select aria-label="选择邮箱"><option value="">全部邮箱</option>${accounts.map(a=>`<option value="${e(a.id)}" ${a.id===this.mailAccount?'selected':''}>${e(a.name)}</option>`).join('')}</select>`;const node=this.shadowRoot.querySelector('.mail-filter');if(html!==this._filterHtml){node.innerHTML=html;this._filterHtml=html;node.querySelector('select').onchange=event=>{this.mailAccount=event.target.value;this.render();};}}
  mailContent(){const feed=this._state.feeds.mail;if(!this._state.configured.mail)return empty('还没有接入邮箱','添加多个 Gmail，在这里集中查看。');if(!feed||feed.data.error)return empty(feed?.data.error||'等待邮件同步');const data=feed.data,accounts=data.accounts||[],selected=accounts.find(a=>a.id===this.mailAccount),failures=(selected?[selected]:accounts).filter(a=>a.error),items=selectMail(data,this.mailAccount);return `<div class="mail-meta"><span>${selected?e(selected.name):'全部邮箱 · 最近 3 封'}</span><span>${e(selected?.unread??data.unread??0)} 未读${!selected&&data.unread_complete===false?' · 部分邮箱':''}</span></div>${failures.map(a=>`<p class="hint error">${e(a.name)}：${e(a.error)}</p>`).join('')}${items.map(mail=>`<article class="mail-row"><div class="mail-row-top"><span class="mail-account">${e(mail.account_name||selected?.name||'Gmail')}${mail.unread?'<i class="unread-dot" aria-label="未读"></i>':''}</span><time>${this.date(mail.received_at,true)}</time></div><strong>${e(mail.subject||'（无主题）')}</strong><div class="mail-row-bottom"><span>${e(mail.sender)}</span><small>${e(mail.account_email||selected?.username||accounts.find(a=>a.id===mail.account_id)?.username||'')}</small></div></article>`).join('')||empty(failures.length?'等待邮箱恢复同步':'收件箱暂无邮件')}${this.feedTime(feed)}`;}
  feedTime(feed){return `<p class="feed-time">${feed.stale?'数据已过期 · ':'更新于 '}${this.date(feed.updated_at)}</p>`;}
  dialog(title,body){return openDialog(title,body,{root:this.shadowRoot});}
  async showHistory(before=null,append=false){const items=await this.action('history',{limit:50,...(before?{before}:{})});const html=items.map(t=>`<article class="list-row"><div class="row-main"><strong>${e(t.task_name)}</strong><span>${e(t.message)}</span><time>${this.date(t.timestamp,true)}</time></div>${badge(t.status)}</article>`).join('');const more=items.length===50?`<button class="secondary" data-action="more-history" data-before="${items.at(-1).id}">加载更多</button>`:'';if(append){const body=this.shadowRoot.querySelector('.dialog-content');body.querySelector('[data-action=more-history]')?.remove();body.insertAdjacentHTML('beforeend',html+more);}else this.dialog('执行记录',html+more||empty('还没有执行记录'));}
  showServer(id){const server=this._state.feeds.servers.data.items.find(s=>s.id===id);if(!server)return;const rows=[['状态',statusLabel(server.status)],['CPU 使用率',formatMetric(server.cpu)+(typeof server.cpu==='number'?'%':'')],['内存使用率',formatMetric(server.ram)+(typeof server.ram==='number'?'%':'')],['磁盘使用率',formatMetric(server.disk)+(typeof server.disk==='number'?'%':'')],['系统负载',server.load?.map(formatMetric).join(' / ')||'—'],['累计上传',formatByteCount(server.network?.sent_bytes)],['累计下载',formatByteCount(server.network?.received_bytes)],['温度',server.temperature==null?'接口未提供':formatMetric(server.temperature)+' °C']];this.dialog(server.name,`<div class="detail-hero">${icon('server')}<span>${server.provider==='1panel'?'1Panel v2':'Beszel'} · 服务器状态</span></div>${server.error?`<p class="hint error">${e(server.error)}</p>`:''}<div class="detail-grid">${rows.map(([name,value])=>`<div class="detail-row"><span>${name}</span><strong>${e(value)}</strong></div>`).join('')}</div>`);}
}
customElements.define('mydesk-card',MyDeskCard);
