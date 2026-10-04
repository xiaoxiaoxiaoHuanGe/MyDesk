import {pickerDate} from './ui.js';

export function mountPicker(container,mode,timeZone) {
  container._observer?.disconnect();
  const current=pickerDate(timeZone,new Date(Date.now()+5*60000));
  const today=pickerDate(timeZone).date;
  const pad=n=>String(n).padStart(2,'0');
  const dates=Array.from({length:366},(_,i)=>{const date=new Date(today+'T12:00:00Z');date.setUTCDate(date.getUTCDate()+i);return {value:date.toISOString().slice(0,10),label:i<3?['今天','明天','后天'][i]:`${date.getUTCMonth()+1}月${date.getUTCDate()}日`};});
  const numbers=n=>Array.from({length:n},(_,i)=>({value:pad(i),label:pad(i)}));
  const specs=mode==='countdown'?[['hour','小时',numbers(24),'00'],['minute','分钟',numbers(60),'15'],['second','秒',numbers(60),'00']]:[['date','日期',dates,current.date],['hour','小时',numbers(24),current.hour],['minute','分钟',numbers(60),current.minute]];
  container.replaceChildren();
  for(const [name,label,items,value] of specs){
    const column=document.createElement('div');column.className='picker-column';
    column.innerHTML=`<span class="picker-label">${label}</span><input type="hidden" name="${name}" value="${value}"><div class="wheel" role="listbox" tabindex="0" aria-label="${label}">${items.map(item=>`<button type="button" tabindex="-1" role="option" aria-selected="false" data-value="${item.value}">${item.label}</button>`).join('')}</div>`;
    container.append(column);const wheel=column.querySelector('.wheel'),input=column.querySelector('input'),options=[...wheel.children];let selected=items.findIndex(item=>item.value===value),timer,interacting=false;
    function select(index,scroll=false){selected=Math.max(0,Math.min(items.length-1,index));input.value=items[selected].value;options.forEach((option,i)=>option.setAttribute('aria-selected',String(i===selected)));wheel.setAttribute('aria-activedescendant',options[selected].id);if(scroll)wheel.scrollTo({top:selected*52,behavior:'instant'});container.dispatchEvent(new Event('change',{bubbles:true}));}
    options.forEach((option,index)=>{option.id=`picker-${name}-${index}`;option.onclick=()=>select(index,true);});
    const finishGesture=()=>{clearTimeout(timer);timer=setTimeout(()=>interacting=false,160);};
    wheel.addEventListener('pointerdown',()=>{interacting=true;clearTimeout(timer);},{passive:true});
    wheel.addEventListener('pointerup',finishGesture,{passive:true});
    wheel.addEventListener('pointercancel',finishGesture,{passive:true});
    wheel.addEventListener('wheel',()=>{interacting=true;finishGesture();},{passive:true});
    // Hidden elements may be clamped to scrollTop=0 by layout. Only a user
    // gesture may turn scroll position into a new value; resizing must not.
    wheel.addEventListener('scroll',()=>{if(!interacting||!wheel.clientHeight)return;select(Math.round(wheel.scrollTop/52));finishGesture();},{passive:true});
    wheel.addEventListener('keydown',event=>{const directions={ArrowDown:1,ArrowUp:-1,PageDown:5,PageUp:-5};if(event.key in directions){event.preventDefault();select(selected+directions[event.key],true);}else if(event.key==='Home'||event.key==='End'){event.preventDefault();select(event.key==='Home'?0:items.length-1,true);}});
    // The form can be mounted while its page is hidden; scroll after it is shown.
    select(selected);requestAnimationFrame(()=>select(selected,true));
    wheel.dataset.initial=String(selected);
    wheel._endGesture=()=>{interacting=false;clearTimeout(timer);};
  }
  container._restore=()=>container.querySelectorAll('.wheel').forEach(wheel=>{wheel._endGesture();if(!wheel.clientHeight)return;const selected=[...wheel.children].findIndex(o=>o.getAttribute('aria-selected')==='true');wheel.scrollTop=Math.max(0,selected)*52;});
  container._observer=new ResizeObserver(()=>container._restore());
  container._observer.observe(container);
}
