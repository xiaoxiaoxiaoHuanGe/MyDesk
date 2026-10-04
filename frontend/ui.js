export const escapeHtml = value => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
export function safeUrl(value) {
  try { const url = new URL(value); return ['https:','http:'].includes(url.protocol) ? escapeHtml(url.href) : '#'; }
  catch { return '#'; }
}
export function parseSteps(value) {
  if (!/^\d+$/.test(value) || Number(value) > 30000) throw new Error('请输入 0–30000 的整数');
  return Number(value);
}
export function formatDate(value, timeZone='Asia/Shanghai', full=false) {
  if (!value || !Number.isFinite(new Date(value).getTime())) return '等待更新';
  return new Intl.DateTimeFormat('zh-CN', {timeZone, ...(full ? {month:'numeric',day:'numeric'} : {}), hour:'2-digit',minute:'2-digit',hour12:false}).format(new Date(value));
}
export const statusLabel = state => ({up:'在线',down:'离线',success:'成功',failed:'失败',running:'运行中',queued:'排队中',dispatching:'提交中',warning:'警告',unknown:'未知',tracking_error:'跟踪异常',pending:'待提醒',snoozed:'已延后'}[state] || '未知');

export function formatByteCount(value) {
  if (typeof value !== 'number' || !Number.isFinite(value) || value < 0) return '未采集';
  const units = ['B','KiB','MiB','GiB','TiB','PiB','EiB'];
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) {value /= 1024; unit++;}
  return `${value.toFixed(2)} ${units[unit]}`;
}

export function countdownTime(hours, minutes, seconds, now=new Date()) {
  const parts=[hours,minutes,seconds].map(Number);
  if (parts.some((v,i)=>!Number.isInteger(v)||v<0||v>(i===0?23:59)) || parts.every(v=>v===0)) throw new Error('请选择大于零的倒计时时间');
  return new Date(now.getTime()+(parts[0]*3600+parts[1]*60+parts[2])*1000).toISOString();
}
export function pickerDate(timeZone='Asia/Shanghai', now=new Date()) {
  const parts=Object.fromEntries(new Intl.DateTimeFormat('en-CA',{timeZone,year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',hourCycle:'h23'}).formatToParts(now).map(p=>[p.type,p.value]));
  return {date:`${parts.year}-${parts.month}-${parts.day}`,hour:parts.hour,minute:parts.minute};
}
export function selectMail(data, accountId='') {
  const items=accountId ? data.accounts?.find(a=>a.id===accountId)?.items || [] : data.items || [];
  const sorted=[...items].sort((a,b)=>new Date(b.received_at)-new Date(a.received_at));
  return accountId ? sorted : sorted.slice(0,3);
}
export const resolveAppearance=(choice,dark)=>choice==='system'||!['light','dark'].includes(choice)?(dark?'dark':'light'):choice;
export const formatMetric=value=>typeof value==='number'&&Number.isFinite(value)?value.toFixed(2):'—';
