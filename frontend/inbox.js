import {escapeHtml as e,formatDate} from './ui.js';
import {openDialog,confirmDialog} from './dialog.js';

export const inboxRows=(rows,zone)=>rows.map(row=>`<article class="detail-row"><button class="text-button" data-message="${e(row.id)}"><strong>${e(row.source_name)} · ${e(row.title)}${row.read_at?'':' · 未读'}</strong><p>${e(row.summary??row.body?.slice(0,500)??'')}</p><small>${e(formatDate(row.received_at,zone,true))}</small></button></article>`).join('');
export function openInbox(request,snapshot={},initial='') {
  const dialog=openDialog('通知收件箱','<label>来源<select data-source><option value="">全部来源</option></select></label><button class="secondary" data-read-all>全部已读</button><div data-messages></div><button class="secondary" data-more>加载更早通知</button><button class="secondary" data-refresh>刷新 / 重试</button>');
  let rows=[],cursor=null,busy=false,through=0;
  const zone=snapshot.timezone||'Asia/Shanghai';
  for(const source of snapshot.inbox?.sources||[]) {const option=document.createElement('option');option.value=source.id;option.textContent=source.name;dialog.querySelector('[data-source]').append(option);}
  async function detail(id) {
    try {
      const item=await request('/api/inbox/'+id);
      const child=openDialog(item.title,`<p>${e(item.source_name)} · ${e(formatDate(item.received_at,zone,true))}</p><pre style="white-space:pre-wrap;overflow-wrap:anywhere">${e(item.body)}</pre><button class="secondary" data-read>标为已读</button>`,{replace:false});
      child.querySelector('[data-read]').onclick=async event=>{event.currentTarget.disabled=true;try {await request('/api/inbox/read',{method:'POST',body:{ids:[id]}});child.close();await load();}catch(error){child.feedback(error.message,true);event.currentTarget.disabled=false;}};
    } catch(error) {dialog.feedback(error.message,true);}
  }
  async function load(more=false) {
    if(busy)return;busy=true;dialog.feedback('正在读取…');
    try {
      const source=dialog.querySelector('[data-source]').value;
      const result=await request('/api/inbox?limit=50'+(more&&cursor?'&before='+cursor:'')+(source?'&source_id='+encodeURIComponent(source):''));
      rows=more?[...rows,...result.items]:result.items;cursor=result.next_cursor;through=Math.max(through,...rows.map(r=>r.seq));
      dialog.querySelector('[data-messages]').innerHTML=inboxRows(rows,zone)||'<p>暂无通知</p>';
      dialog.querySelector('[data-more]').hidden=!cursor;
      dialog.querySelectorAll('[data-message]').forEach(button=>button.onclick=()=>detail(button.dataset.message));dialog.feedback('');
    }catch(error){dialog.feedback(error.message,true);}finally{busy=false;}
  }
  dialog.querySelector('[data-source]').onchange=()=>load();
  dialog.querySelector('[data-more]').onclick=()=>load(true);dialog.querySelector('[data-refresh]').onclick=()=>load();
  dialog.querySelector('[data-read-all]').onclick=async event=>{event.currentTarget.disabled=true;try {await request('/api/inbox/read',{method:'POST',body:{through_seq:through}});await load();}catch(error){dialog.feedback(error.message,true);}finally{event.currentTarget.disabled=false;}};
  load();if(initial)detail(initial);return dialog;
}
export function openNotificationSources(request) {
  const dialog=openDialog('通知接入',`<p data-push></p><form><label>来源名称<input name="name" required maxlength="80"></label><label>协议<select name="kind"><option value="cpe">CPE 短信（必须带幂等 ID）</option><option value="text">通用文本</option></select></label><button class="primary" type="submit">创建来源</button></form><div data-once></div><div data-sources></div><button class="secondary" data-refresh>刷新 / 重试</button>`);
  let busy=false;
  function reveal(path) {
    const target=dialog.querySelector('[data-once]');target.innerHTML='<p>接收地址仅此一次展示，请立即复制。重置会立即使旧地址失效。</p><button class="secondary" data-copy>复制完整接收地址</button><button class="secondary" data-hide>已保存，隐藏地址</button>';
    target.querySelector('[data-copy]').onclick=async()=>{try {await navigator.clipboard.writeText(new URL(path,location.origin).href);dialog.feedback('接收地址已复制');}catch {const input=document.createElement('input');input.readOnly=true;input.value=new URL(path,location.origin).href;target.append(input);input.select();dialog.feedback('请手动复制地址');}};
    target.querySelector('[data-hide]').onclick=()=>target.replaceChildren();
  }
  async function load() {
    const result=await request('/api/notification-sources');
    dialog.querySelector('[data-push]').textContent=result.push_available?'消息可同步，后台推送已配置；送达需真机验证':'消息可同步，后台推送未配置';
    dialog.querySelector('[data-sources]').innerHTML=result.sources.map(source=>`<article class="detail-row" data-source="${e(source.id)}"><div><strong>${e(source.name)}</strong><p>最近接收：${e(source.last_received_at||'尚未接收')}</p><label>名称<input value="${e(source.name)}" maxlength="80"></label><button class="secondary" data-rename>重命名</button><button class="secondary" data-toggle>${source.enabled?'停用':'启用'}</button><button class="secondary" data-rotate>重置密钥</button><button class="secondary" data-delete>删除</button></div></article>`).join('');
    for(const element of dialog.querySelectorAll('[data-source]')) {
      const id=element.dataset.source,source=result.sources.find(s=>s.id===id),path='/api/notification-sources/'+id;
      element.querySelector('[data-rename]').onclick=()=>perform(async()=>request(path,{method:'PATCH',body:{name:element.querySelector('input').value}}));
      element.querySelector('[data-toggle]').onclick=()=>perform(async()=>request(path,{method:'PATCH',body:{enabled:!source.enabled}}));
      element.querySelector('[data-rotate]').onclick=()=>perform(async()=>reveal((await request(path+'/rotate-secret',{method:'POST'})).receive_path));
      element.querySelector('[data-delete]').onclick=async()=>{if(await confirmDialog('删除来源','历史消息保留，此来源将停止接收。','删除',{replace:false}))perform(async()=>request(path,{method:'DELETE'}));};
    }
  }
  async function perform(operation) {
    if(busy)return;busy=true;dialog.querySelectorAll('button').forEach(b=>b.disabled=true);
    try {await operation();await load();dialog.feedback('');}catch(error){dialog.feedback(error.message,true);}finally {busy=false;dialog.querySelectorAll('button').forEach(b=>b.disabled=false);}
  }
  dialog.querySelector('form').onsubmit=event=>{event.preventDefault();const form=event.currentTarget;perform(async()=>{reveal((await request('/api/notification-sources',{method:'POST',body:Object.fromEntries(new FormData(form))})).receive_path);form.reset();});};
  dialog.querySelector('[data-refresh]').onclick=()=>perform(load);perform(load);return dialog;
}
