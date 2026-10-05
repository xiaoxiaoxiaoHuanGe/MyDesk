import {escapeHtml as e} from './ui.js';
import {icon} from './icons.js';

export function openDialog(title,body,{root=document.body,wide=false,replace=true}={}) {
  if(replace)root.querySelector('dialog')?.close();
  const previous=root instanceof ShadowRoot?root.activeElement:document.activeElement;
  const dialog=document.createElement('dialog');dialog.className=wide?'wide':'';
  dialog.innerHTML=`<div class="sheet-handle" aria-hidden="true"></div><header class="dialog-heading"><div><span class="eyebrow">MYDESK</span><h2>${e(title)}</h2></div><button class="icon-button close-dialog" type="button" aria-label="关闭窗口">${icon('close')}</button></header><div class="dialog-content">${body}</div><p class="dialog-feedback" role="status"></p>`;
  root.append(dialog);
  const heading=dialog.querySelector('h2');heading.id='dialog-title-'+crypto.randomUUID();dialog.setAttribute('aria-labelledby',heading.id);
  dialog.querySelector('.close-dialog').onclick=()=>dialog.close();
  dialog.addEventListener('keydown',event=>{if(event.key==='Escape'&&!event.target.matches('select')){event.preventDefault();dialog.close();}});
  dialog.addEventListener('click',event=>{if(event.target===dialog){const r=dialog.getBoundingClientRect();if(event.clientX<r.left||event.clientX>r.right||event.clientY<r.top||event.clientY>r.bottom)dialog.close();}});
  dialog.addEventListener('close',()=>{dialog.remove();previous?.focus({preventScroll:true});},{once:true});
  dialog.showModal();
  // Let the browser own scrolling. A pull from the top of a mobile sheet closes it.
  const content=dialog.querySelector('.dialog-content');let start=null,drag=0;
  dialog.addEventListener('touchstart',event=>{start=content.scrollTop<=0&&!event.target.closest('input,select,textarea,button,a')?event.touches[0].clientY:null;drag=0;},{passive:true});
  dialog.addEventListener('touchmove',event=>{if(start===null)return;drag=Math.max(0,event.touches[0].clientY-start);if(drag>8&&matchMedia('(max-width:600px)').matches){event.preventDefault();dialog.style.transform=`translateY(${Math.min(drag,240)}px)`;}},{passive:false});
  dialog.addEventListener('touchend',()=>{if(start!==null&&drag>100)dialog.close();else dialog.style.transform='';start=null;drag=0;});
  dialog.feedback=(text,error=false)=>{const node=dialog.querySelector('.dialog-feedback');node.textContent=text;node.classList.toggle('error',error);};
  return dialog;
}
export async function confirmDialog(title,message,accept='确认',options={}) {
  const dialog=openDialog(title,`<p class="muted">${e(message)}</p><div class="dialog-actions"><button class="secondary" data-no>取消</button><button class="primary" data-yes>${e(accept)}</button></div>`,options);
  return new Promise(resolve=>{let result=false;dialog.querySelector('[data-no]').onclick=()=>dialog.close();dialog.querySelector('[data-yes]').onclick=()=>{result=true;dialog.close();};dialog.addEventListener('close',()=>resolve(result),{once:true});});
}
