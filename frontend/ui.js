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
