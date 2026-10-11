import {escapeHtml as e,statusLabel} from './ui.js';
import {openDialog,confirmDialog} from './dialog.js';

export function planInput(value) {
  const result={};
  for(const key of ['start','increment','interval_minutes','target']) {
    if(!/^\d+$/.test(String(value[key]??'')))throw new Error('请填写四项整数参数');
    result[key]=Number(value[key]);
  }
  const {start,increment,interval_minutes:interval,target}=result;
  if(start<0||start>target||target>30000||increment<1||increment>30000||interval<1||interval>1440)throw new Error('起始不高于终止步数，增量大于零，间隔为 1–1440 分钟');
  const percent=value.random_percent===undefined?0:Number(value.random_percent);
  if(![0,10].includes(percent)||typeof value.random_percent==='boolean')throw new Error('随机浮动必须为 0 或 10');
  const spread=Math.floor(increment*percent/100),low=Math.max(1,increment-spread),high=increment+spread;
  const n=Math.floor((target-start)/increment)+1,final=start+n*increment;
  if(target===30000||(!percent&&final>30000))throw new Error('最后超过终止步数的提交也必须不超过 30000 步');
  if(!percent)return {...result,random_percent:0,count:n+1,final,duration:n*interval};
  const least=Math.floor((target-start)/high)+1,most=Math.floor((target-start)/low)+1;
  return {...result,random_percent:percent,count:[least+1,most+1],duration:[least*interval,most*interval],final:[target+1,Math.min(target+high,30000)],low,high};
}
export function fillPreset(draft,preset) {
  return {...draft,...Object.fromEntries(['start','increment','interval_minutes','target'].map(k=>[k,preset[k]])),random_percent:preset.random_percent??0};
}
export const planLabel=value=>({waiting:'等待下一次',running:'提交进行中',paused:'异常暂停',completed:'已完成',stopped:'已终止'}[value]||'尚未开始');
export function planSummary(plan,date) {
  const run=plan?.run;
  return `<div class="plan-summary">${run?`<strong>${e(run.name)} · ${planLabel(run.status)}</strong><p>最近成功提交 ${run.last_success==null?'—':Number(run.last_success).toLocaleString('zh-CN')+' 步'} · 成功 ${run.success_count} 次</p>${run.current_job?`<p>当前 ${run.current_job.steps} 步 · ${e(statusLabel(run.current_job.status))}</p>`:run.next_at?`<p>下一次 ${run.next_steps} 步 · ${e(date(run.next_at))}</p>`:''}${run.message?`<p>${e(run.message)}</p>`:''}`:'<p>设置固定增量和间隔，首次超过终止步数后结束。</p>'}<div class="dialog-actions"><button class="secondary" data-action="step-plan">管理渐进任务</button>${run?'<button class="secondary" data-action="step-records">本轮记录</button>':''}${run&&['waiting','running','paused'].includes(run.status)?'<button class="secondary" data-action="step-stop">终止本轮</button>':''}</div>${plan?.settings.daily?`<p>每日 ${e(plan.settings.start_time)} · 已启用</p>`:''}</div>`;
}
const recordsHtml=(rows,date)=>rows.map(r=>`<article class="detail-row"><div><strong>${r.steps} 步 · ${e(statusLabel(r.status))}</strong><p>${e(date(r.created_at))} · ${r.source==='manual'?'手动提交':'计划提交'}${r.finished_at?' · 完成 '+e(date(r.finished_at)):''}</p>${['failed','tracking_error'].includes(r.status)?`<p>${e(r.message)}</p>`:''}</div></article>`).join('')||'<p>暂无提交记录</p>';

function openRoundHistory(controller,date,id) {
  const plan=controller.state?.wxstep_plan;const round=plan?.run?.id===id?plan.run:(plan?.recent_runs||[]).find(r=>r.id===id);const p=round?.params;
  const description=p?`<p>起始 ${e(p.start)} · 终止 ${e(p.target)} · 基准增加 ${e(p.increment)} 步 · ${p.random_percent===10?'随机 ±10%':'固定增量'} · 间隔 ${e(p.interval_minutes)} 分钟</p>`:'';
  const dialog=openDialog('本轮提交记录',description+'<div class="history-rows"></div><button class="secondary" data-more>加载更早记录</button>',{replace:false});
  let rows=[],busy=false;
  async function load(){
    if(busy)return;busy=true;
    try{const page=await controller.command('wxstep/plan/history',{run_id:id,limit:100,...(rows.length?{before:rows.at(-1).seq}:{})});rows.push(...page);dialog.querySelector('.history-rows').innerHTML=recordsHtml(rows,date);dialog.querySelector('[data-more]').hidden=page.length<100;}
    catch(error){dialog.feedback(error.message,true);}finally{busy=false;}
  }
  dialog.querySelector('[data-more]').onclick=load;load();return dialog;
}

export function openStepPlan(controller,date,recordsOnly=false) {
  let plan=controller.state?.wxstep_plan||{settings:{},presets:[]},selected='',stopSubscription;
  const field=(key,label)=>`<label>${label}<input name="${key}" type="number" inputmode="numeric" min="${key==='start'||key==='target'?0:1}" max="${key==='interval_minutes'?1440:30000}" step="1" value="${e(plan.settings[key]??'')}" required></label>`;
  const dialog=openDialog(recordsOnly?'步数运行记录':'渐进步数任务',`${recordsOnly?'':`<form class="plan-form"><div class="preset-options" role="group" aria-label="快捷预设"></div><div class="plan-fields">${field('start','起始步数')}${field('increment','基准增加步数')}${field('interval_minutes','间隔时间（分钟）')}${field('target','终止步数')}</div><label class="check-label"><input name="random" type="checkbox" ${(plan.capabilities?.random_steps&&(plan.settings.start===undefined||plan.settings.random_percent===10))?'checked':''} ${plan.capabilities?.random_steps?'':'disabled'}>随机浮动 ±10%</label>${plan.capabilities?.random_steps?'':'<p>随机功能需要升级服务器</p>'}<p class="plan-preview" role="status"></p><label>预设名称<input name="name" maxlength="40" placeholder="例如：散步、跑步、逛街"></label><div class="dialog-actions"><button class="secondary" type="button" data-preset-save>保存为预设</button><button class="secondary" type="button" data-preset-new>另存为新预设</button><button class="secondary" type="button" data-preset-delete>删除选中预设</button><button class="secondary" type="button" data-preset-up>前移</button><button class="secondary" type="button" data-preset-down>后移</button></div><label class="check-label"><input name="daily" type="checkbox" ${plan.settings.daily?'checked':''}>每日重复</label><label>每日开始时间<input name="start_time" type="time" value="${e(plan.settings.start_time||'08:00')}" required></label><p class="hint">保存不提交；每日设置次日生效。已开始的轮次保留原参数。时间使用工作台时区。</p><div class="dialog-actions"><button class="secondary" type="button" data-plan-save>保存设置</button><button class="primary" type="submit">立即开始</button></div></form>`}<div class="plan-live" aria-live="polite"></div><div class="plan-records"></div><button class="secondary" data-older>加载更早记录</button><div class="recent-rounds"></div>`);
  const form=dialog.querySelector('form');
  const values=()=>({...Object.fromEntries(new FormData(form)),random_percent:form.elements.random.checked&&plan.capabilities?.random_steps?10:0});
  const parameters=()=>{const v=values();const p=planInput(v);return {...Object.fromEntries(['start','increment','interval_minutes','target','random_percent'].map(k=>[k,p[k]])),name:v.name||'渐进任务',preset_id:selected,daily:v.daily==='on',start_time:v.start_time,revision:plan.revision};};
  let draftRevision=plan.revision;
  let paintedPresets='';
  function preview(){try{const p=planInput(values());dialog.querySelector('.plan-preview').textContent=`预计提交 ${Array.isArray(p.count)?p.count.join("～"):p.count} 次 · ${Array.isArray(p.duration)?p.duration.join("～"):p.duration} 分钟 · 最后提交 ${Array.isArray(p.final)?p.final.join("～"):p.final} 步${p.random_percent?` · 每次增加 ${p.low}～${p.high} 步（末次可能因 30000 封顶减少）`:""}；排队与重试会增加耗时`; }catch(err){dialog.querySelector('.plan-preview').textContent=err.message;}}
  function paint(snapshot){
    plan=snapshot.wxstep_plan||plan;
    if(form){
      dialog.querySelector('[data-preset-save]').textContent=selected?'更新此预设':'保存为预设';
      const signature=JSON.stringify(plan.presets);
      if(signature!==paintedPresets){
        paintedPresets=signature;
        const options=dialog.querySelector('.preset-options');
        options.innerHTML=(plan.presets||[]).map(p=>`<button class="secondary" type="button" data-preset="${e(p.id)}" aria-pressed="${selected===p.id}">${e(p.name)}</button>`).join('')||'<p class="hint">填写参数和名称，可以保存自己的快捷预设。</p>';
        options.querySelectorAll('[data-preset]').forEach(b=>b.onclick=()=>{
          const p=plan.presets.find(p=>p.id===b.dataset.preset);selected=p.id;draftRevision=plan.revision;
          const v=fillPreset(values(),p);for(const k of ['start','increment','interval_minutes','target'])form.elements[k].value=v[k];form.elements.name.value=p.name;form.elements.random.checked=v.random_percent===10&&!!plan.capabilities?.random_steps;
          options.querySelectorAll('button').forEach(button=>button.setAttribute('aria-pressed',String(button.dataset.preset===selected)));
          dialog.querySelector('[data-preset-save]').textContent='更新此预设';preview();
        });
      }
    }
    const run=plan.run;
    dialog.querySelector('.plan-live').innerHTML=run?`<hr><strong>${e(run.name)} · ${planLabel(run.status)}</strong><p>终止步数 ${run.params.target} · 基准增加 ${run.params.increment} 步 · ${run.params.random_percent===10?'随机 ±10%':'固定增量'} · 成功 ${run.success_count} 次 · 最近成功 ${run.last_success??'—'} 步</p>${run.current_job?`<p>当前提交 ${run.current_job.steps} 步 · ${e(statusLabel(run.current_job.status))}</p>`:run.next_at?`<p>下一次 ${run.next_steps} 步 · ${e(date(run.next_at))} <span data-countdown></span></p>`:''}${run.message?`<p>${e(run.message)}</p>`:''}<div class="dialog-actions">${['waiting','running','paused'].includes(run.status)?'<button class="secondary" data-stop>终止本轮</button>':''}${run.status==='paused'?'<button class="secondary" data-resume>检查后重试 / 恢复</button>':''}${plan.settings.daily?'<button class="secondary" data-disable>关闭每日计划</button>':''}</div>`:'<p>尚未开始渐进任务</p>';
    dialog.querySelector('[data-stop]')?.addEventListener('click',()=>send('wxstep/plan/stop',{run_id:run.id}));
    dialog.querySelector('[data-disable]')?.addEventListener('click',()=>send('wxstep/plan/disable',{}));
    dialog.querySelector('[data-resume]')?.addEventListener('click',()=>send('wxstep/plan/resume',{run_id:run.id}));
    const job=snapshot.wxstep;
    if(job&&['failed','tracking_error'].includes(job.status)){
      dialog.querySelector('.plan-live').insertAdjacentHTML('afterbegin',`<div class="step-issue"><strong>最近提交 ${job.steps} 步 · ${e(statusLabel(job.status))}</strong><p>${e(job.message)}</p>${job.status==='tracking_error'?'<button class="secondary" data-release>检查后结束本地跟踪</button>':''}</div>`);
      dialog.querySelector('[data-release]')?.addEventListener('click',async()=>{if(await confirmDialog('结束本地跟踪','请先在 GitHub Actions 确认本次任务；此操作不会取消已发出的提交。','已检查，结束跟踪',{replace:false}))await send('wxstep/release',{confirmed:true});});
    }
    dialog.querySelector('.plan-records').innerHTML=run?recordsHtml(run.records||[],date):'';
    dialog.querySelector('[data-older]').hidden=!run;
    dialog.querySelector('[data-older]').textContent='查看全部提交记录';
    dialog.querySelector('.recent-rounds').innerHTML=(plan.recent_runs||[]).filter(r=>r.id!==run?.id).map(r=>`<button class="text-button" data-round="${e(r.id)}">${e(r.name)} · ${e(date(r.started_at))} · ${planLabel(r.status)}</button>`).join('');
    dialog.querySelectorAll('[data-round]').forEach(b=>b.onclick=()=>openRoundHistory(controller,date,b.dataset.round));
  }
  async function send(action,payload){
    try {
      dialog.feedback('');
      const result=await controller.command(action,payload);
      if(action==='wxstep/preset/save')selected=result.presets.find(p=>p.name===payload.name)?.id||selected;
      if(action==='wxstep/preset/delete')selected='';
      const snapshot=await controller.command('snapshot');controller.state=snapshot;controller.broadcast();
      draftRevision=plan.revision;
      dialog.feedback(action.endsWith('/stop')?'已终止本轮，已发出的提交继续跟踪':action.endsWith('/start')?'本轮已开始':'已保存');
    }catch(error){dialog.feedback(error.message,true);}
  }
  if(form){
    form.addEventListener('input',preview);
    form.onsubmit=event=>{event.preventDefault();try{send('wxstep/plan/start',parameters());}catch(error){dialog.feedback(error.message,true);}};
    dialog.querySelector('[data-plan-save]').onclick=()=>{try{send('wxstep/plan/save',{...parameters(),revision:draftRevision});}catch(error){dialog.feedback(error.message,true);}};
    const savePreset=newPreset=>{try{const p=parameters();const name=values().name.trim();if(!name)throw new Error('请填写预设名称');send('wxstep/preset/save',{...p,name,...(!newPreset&&selected?{id:selected}:{}),revision:draftRevision});}catch(error){dialog.feedback(error.message,true);}};
    dialog.querySelector('[data-preset-save]').onclick=()=>savePreset(false);
    dialog.querySelector('[data-preset-new]').onclick=()=>savePreset(true);
    dialog.querySelector('[data-preset-delete]').onclick=async()=>{if(selected&&await confirmDialog('删除预设','删除不影响运行中的任务。','确认',{replace:false})){await send('wxstep/preset/delete',{id:selected,revision:draftRevision});}};
    for(const [selector,offset] of [['[data-preset-up]',-1],['[data-preset-down]',1]])dialog.querySelector(selector).onclick=()=>{
      const ids=plan.presets.map(p=>p.id),i=ids.indexOf(selected),j=i+offset;if(i<0||j<0||j>=ids.length)return;
      [ids[i],ids[j]]=[ids[j],ids[i]];send('wxstep/preset/order',{ids,revision:plan.revision});
    };
    preview();
  }
  dialog.querySelector('[data-older]').onclick=()=>openRoundHistory(controller,date,plan.run.id);
  stopSubscription=controller.add(snapshot=>{if(snapshot)paint(snapshot);});
  paint(controller.state||{});
  const timer=setInterval(()=>{const node=dialog.querySelector('[data-countdown]');if(node&&plan.run?.next_at){const seconds=Math.max(0,Math.ceil((new Date(plan.run.next_at)-Date.now())/1000));node.textContent=` · ${Math.floor(seconds/60)} 分 ${seconds%60} 秒`; }},1000);
  dialog.addEventListener('close',()=>{stopSubscription?.();clearInterval(timer);},{once:true});
  return dialog;
}
