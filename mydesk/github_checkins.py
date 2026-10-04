"""Read-only adapters for the two supported check-in workflows.

Only whitelisted numeric/status summaries leave the transient job log. Raw logs,
account details, cookies and third-party notification payloads are never stored.
"""
import asyncio
import re
import hashlib
import json
from datetime import timedelta
from urllib.parse import quote, urlencode, urlsplit

from aiohttp import ClientTimeout

from .providers import GitHub
from .task_result import parse_result, result_message

SOURCES = {
    '52fzwg': dict(name='52 辅助论坛签到', owner='', repo='52fzwg-checkin',
                   workflow='checkin.yml', ref='main', step='Check in'),
    'glados': dict(name='GLaDOS 签到', owner='', repo='Glados-Railgun-checkin',
                  workflow='gladosCheck.yml', ref='master', step='Running checkin'),
}
MAX_LOG_BYTES = 2 * 1024 * 1024


def log_lines(log):
    """Exclude Actions' echoed shell scripts, ANSI escapes and timestamps."""
    in_group = False
    lines = []
    for line in log.splitlines():
        line = re.sub(r'\x1b\[[0-9;]*m', '', line)
        line = re.sub(r'^\d{4}-\d{2}-\d{2}T\S+\s+', '', line).strip()
        if line.startswith('##[group]'):
            in_group = True
        elif line.startswith('##[endgroup]'):
            in_group = False
        elif not in_group:
            lines.append(line)
    return lines


def result_summary(key, step, run, log):
    conclusion = step.get('conclusion')
    state = 'success' if conclusion == 'success' else 'failed'
    if conclusion not in ('success','failure','cancelled','timed_out','action_required','startup_failure','stale'):
        state = 'unknown'
    parts = ['签到成功' if state == 'success' else '签到失败' if state == 'failed' else '签到未执行']
    lines = log_lines(log)
    if key == '52fzwg':
        results = [line for line in lines if re.match(r'^✅\s*(?:签到成功|恭喜您签到成功|今日已经签到|您今日已经签到|已经签到)', line)]
        detail = results[-1] if results else ''
        if state == 'success' and '已经签到' in detail:
            parts[0] = '今日已签到'
        for label, pattern in [('积分', r'增加积分\s*(\d+(?:\.\d+)?)'), ('金币', r'增加金币\s*(\d+(?:\.\d+)?)')]:
            match = re.search(pattern, detail)
            if match and state == 'success':
                parts.append(f'{label} +{match[1]}')
    else:
        summaries = [re.fullmatch(r'GLaDOS 签到, 成功(\d+), 失败(\d+), 重复(\d+)', line) for line in lines]
        summaries = [match for match in summaries if match]
        if summaries:
            success, failed, repeat = map(int, summaries[-1].groups())
            if failed:
                state, parts[0] = ('warning', '部分签到失败') if success + repeat else ('failed', '签到失败')
            elif success + repeat == 0:
                state, parts[0] = 'unknown', '没有有效的签到账号'
            elif state == 'success' and success == 0:
                parts[0] = '今日已签到'
            parts.append(f'成功 {success} / 失败 {failed} / 已签到 {repeat}')
        rewards = [re.fullmatch(r'#\d+ P:(\d+(?:\.\d+)?) 剩余:(\d+) 天 总积分:(\d+(?:\.\d+)?) 积分 \| (签到成功|重复签到|签到失败)(.*)', line) for line in lines]
        rewards = [match for match in rewards if match]
        if len(rewards) == 1:
            earned, days, total, status, extra = rewards[0].groups()
            if status == '签到成功':
                parts.append(f'积分 +{earned}')
            parts.extend([f'总积分 {total}', f'剩余 {days} 天'])
            if '兑换失败' in extra:
                parts.append('兑换未完成')
    retries = [line for line in lines if re.fullmatch(r'AUTO_RETRY (?:first_failed|retry_success|retry_repeat|retry_failed)', line)]
    if retries:
        parts.append({'AUTO_RETRY retry_success': '重试成功', 'AUTO_RETRY retry_repeat': '重试确认今日已签到',
                      'AUTO_RETRY retry_failed': '重试失败'}.get(retries[-1], '曾自动重试'))
    if state == 'success' and run.get('conclusion') != 'success':
        parts.append('工作流其他步骤异常')
    return state, parts


class CheckinGitHub(GitHub):
    async def job_log(self, job_id):
        headers = {'Authorization': f"Bearer {self.config['token']}", 'X-GitHub-Api-Version': '2026-03-10'}
        async def read(response):
            if response.status != 200:
                raise ValueError('签到日志暂不可用')
            data = bytearray()
            async for chunk in response.content.iter_chunked(65536):
                data.extend(chunk)
                if len(data) > MAX_LOG_BYTES:
                    raise ValueError('签到日志过大')
            return data.decode('utf-8', errors='replace')
        async with self.session.get(self.base+f'/actions/jobs/{int(job_id)}/logs', headers=headers,
                                    timeout=ClientTimeout(total=30), allow_redirects=False) as response:
            if response.status != 302:
                return await read(response)
            location = response.headers.get('Location', '')
        url = urlsplit(location)
        host = (url.hostname or '').lower()
        if url.scheme != 'https' or url.username or url.password or url.port not in (None, 443) or not (
                host.endswith('.blob.core.windows.net') or host.endswith('.actions.githubusercontent.com')):
            raise ValueError('GitHub 日志下载地址无效')
        # The signed storage URL gets no GitHub Authorization header and no redirects.
        async with self.session.get(location, timeout=ClientTimeout(total=30), allow_redirects=False) as response:
            return await read(response)


def task_binding(spec):
    fields={key:spec.get(key,'') for key in ('owner','repo','workflow','ref','adapter','step')}
    return hashlib.sha256(json.dumps(fields,sort_keys=True).encode()).hexdigest()[:16]


def legacy_binding(key,spec):
    return key in SOURCES and spec.get('adapter')==key and all(spec.get(field)==value for field,value in SOURCES[key].items() if field!='name')


class CheckinPoller:
    def __init__(self,session,tasks):
        self.session,self.tasks=session,tasks
        self.base_url='https://api.github.com'
        self.log_retry_after={}

    async def poll(self,exists,now):
        async def source(key,spec,events):
            task_id='github.'+key
            try:
                github=CheckinGitHub(self.session,spec,self.base_url)
                query=urlencode({'branch':spec['ref'],'per_page':7})
                runs=await github.request('GET',f"/actions/workflows/{quote(spec['workflow'],safe='')}/runs?{query}")
                for run in runs.get('workflow_runs',[]):
                    if run.get('head_branch')!=spec['ref'] or run.get('path','').split('@')[0]!='.github/workflows/'+spec['workflow']:
                        continue
                    prefix=f"{task_id}.{task_binding(spec)}.{int(run['id'])}.{int(run.get('run_attempt',1))}"
                    completed=run.get('status')=='completed'
                    event_id=prefix+'.completed' if completed else prefix+'.'+str(run.get('status'))
                    if await exists(event_id):continue
                    if completed and legacy_binding(key,spec) and await exists(f"{task_id}.{int(run['id'])}.{int(run.get('run_attempt',1))}.completed"):
                        continue
                    event=dict(task_id=task_id,task_name=spec['name'],source='GitHub Actions',timestamp=run['updated_at'],event_id=event_id)
                    if not completed:
                        event.update(status='running',message='任务执行中' if run.get('status')=='in_progress' else '任务排队中')
                        events.append(event);continue
                    if spec.get('adapter')=='workflow':
                        conclusion=run.get('conclusion')
                        event.update(status='success' if conclusion=='success' else 'failed' if conclusion in ('failure','cancelled','timed_out') else 'unknown',
                                     message='工作流执行成功' if conclusion=='success' else '工作流执行失败' if conclusion in ('failure','cancelled','timed_out') else '工作流未完成有效执行')
                        events.append(event);continue
                    if self.log_retry_after.get(prefix,now)>now:continue
                    jobs=await github.request('GET',f"/actions/runs/{int(run['id'])}/attempts/{int(run.get('run_attempt',1))}/jobs?per_page=100")
                    matches=[(job,step) for job in jobs.get('jobs',[]) for step in job.get('steps',[]) if step.get('name')==spec['step']]
                    if len(matches)!=1 or jobs.get('total_count',0)>100:
                        event.update(status='failed' if run.get('conclusion')=='failure' and not matches else 'unknown',
                            message='任务准备失败，未进入结果步骤' if not matches and run.get('conclusion')=='failure' else '未找到唯一的结果步骤，请检查步骤名称和统一模板')
                    else:
                        job,step=matches[0]
                        try:
                            log=await github.job_log(job['id'])
                        except Exception:
                            log='';self.log_retry_after[prefix]=now+timedelta(minutes=15);event['event_id']=prefix+'.pending_details'
                        try:
                            result=parse_result(log_lines(log))
                        except ValueError:
                            result={'version':1,'status':'unknown','message':'标准结果格式无效，请检查任务输出','metrics':[]}
                        if result is not None:
                            status,parts=result['status'],[result_message(result)]
                            if step.get('conclusion') in ('failure','cancelled','timed_out'):
                                status,parts='failed',['结果步骤执行失败']
                            elif status=='success' and run.get('conclusion')!='success':parts.append('工作流其他步骤异常')
                        elif spec.get('adapter')=='standard':
                            status,parts='unknown',['未收到标准结果，请检查结果步骤输出' if log else '结果日志暂不可用']
                        else:
                            status,parts=result_summary(spec['adapter'],step,run,log)
                            if not log:parts.append('详情暂不可用')
                        if any(s.get('name')=='Send Feishu notification' and s.get('conclusion')=='failure' for s in job.get('steps',[])):
                            parts=[part for part in parts if part!='工作流其他步骤异常']+['飞书通知失败']
                        event.update(status=status,message=' · '.join(parts))
                    events.append(event)
                return events,dict(task_id=task_id,name=spec['name'])
            except Exception:
                return events,dict(task_id=task_id,name=spec['name'],error='GitHub 任务同步失败，请检查此任务的 Token、仓库和 Actions 读取权限')
        async def bounded(key,spec):
            events=[]
            try:return await asyncio.wait_for(source(key,spec,events),45)
            except asyncio.TimeoutError:return events,dict(task_id='github.'+key,name=spec['name'],error='GitHub 任务同步超时，稍后自动重试')
        results=await asyncio.gather(*(bounded(key,spec) for key,spec in self.tasks.items() if spec.get('enabled',True)))
        return [event for events,_ in results for event in events],{'items':[item for _,item in results]}
