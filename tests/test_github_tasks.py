import asyncio
import json
import subprocess
import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path

from aiohttp import ClientSession, web
from mydesk.config import Settings, DEFAULTS
from mydesk.domain import Desk
from mydesk.runtime import Runtime

NOW=datetime(2026,10,2,8,tzinfo=timezone.utc)

def task(repo,token,**extra):
    return dict(name=repo,owner='owner',repo=repo,workflow='task.yml',ref='main',adapter='standard',
                step='MyDesk result',max_age_hours=36,enabled=True,token=token,**extra)

class GitHubTaskSettingsTests(unittest.TestCase):
    def test_arbitrary_tasks_keep_independent_tokens_and_remove_without_affecting_steps(self):
        with tempfile.TemporaryDirectory() as directory:
            s=Settings(Path(directory)/'settings.json')
            s.update({'github':dict(owner='owner',repo='WxStepCustom',workflow='wx.yml',token='steps-secret')})
            try:
                public=s.update({'github_tasks':{'forum':task('forum','forum-secret'),'glados':task('glados','glados-secret'),'backup':task('backup','backup-secret')}})
            except ValueError as exc:
                self.fail(f'必须支持独立凭据和自定义任务: {exc}')
            self.assertNotIn('secret',json.dumps(public))
            public['github_tasks']['forum']['name']='论坛签到'
            public['github_tasks']['forum']['token']=''
            del public['github_tasks']['glados']
            s.update({'github_tasks':public['github_tasks']})
            self.assertEqual(s.value['github_tasks']['forum']['token'],'forum-secret')
            self.assertEqual(s.value['github_tasks']['backup']['token'],'backup-secret')
            self.assertNotIn('glados',s.value['github_tasks'])
            self.assertEqual(s.value['github']['token'],'steps-secret')
            before=json.dumps(s.value)
            with self.assertRaises(ValueError):
                s.update({'github_tasks':{'bad':task('repo','')}})
            self.assertEqual(json.dumps(s.value),before)

    def test_migration_never_reuses_the_steps_token_for_checkins(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'settings.json'
            path.write_text(json.dumps({**DEFAULTS,'github':dict(owner='a',repo='wx',workflow='wx.yml',token='steps-secret'),
                'github_checkins':{'sources':['52fzwg','glados'],'use_steps_token':True}}),encoding='utf-8')
            s=Settings(path)
            self.assertIn('github_tasks',s.value)
            self.assertNotIn('github_checkins',s.value)
            self.assertEqual(set(s.value['github_tasks']),{'52fzwg','glados'})
            for item in s.value['github_tasks'].values():
                self.assertFalse(item['enabled'])
                self.assertFalse(item.get('token'))
            self.assertEqual(s.value['github']['token'],'steps-secret')
            self.assertEqual(Settings(path).value,s.value)

class GitHubTaskRuntimeTests(unittest.IsolatedAsyncioTestCase):
    async def test_actions_only_token_can_check_without_contents_permission(self):
        paths=[]
        async def respond(request):
            paths.append(request.path)
            if '/branches/' in request.path:
                return web.json_response({'message':'Resource not accessible by personal access token'},status=403)
            return web.json_response({'workflow_runs':[]})
        app=web.Application();app.router.add_route('*','/{tail:.*}',respond)
        runner=web.AppRunner(app);await runner.setup()
        site=web.TCPSite(runner,'127.0.0.1',0);await site.start()
        try:
            with tempfile.TemporaryDirectory() as directory:
                async with ClientSession() as session:
                    rt=Runtime(Desk(Path(directory)/'desk.db'),{'github_tasks':{'backup':task('backup','backup-secret')}},session,None)
                    rt.checkins.base_url=f'http://127.0.0.1:{site._server.sockets[0].getsockname()[1]}'
                    result=await rt.command('github_task/check',{'task_id':'backup'},NOW)
                    self.assertTrue(result['connected'])
                    self.assertIn('尚无执行记录',result['message'])
                    self.assertFalse(any('/branches/' in path for path in paths))
        finally:await runner.cleanup()

    async def test_connection_check_remains_available_after_template_api_is_removed(self):
        methods=[]
        async def respond(request):
            methods.append(request.method)
            if '/branches/' in request.path:return web.json_response({'name':'main'})
            return web.json_response({'workflow_runs':[]})
        app=web.Application();app.router.add_route('*','/{tail:.*}',respond)
        runner=web.AppRunner(app);await runner.setup()
        site=web.TCPSite(runner,'127.0.0.1',0);await site.start()
        try:
            with tempfile.TemporaryDirectory() as directory:
                async with ClientSession() as session:
                    rt=Runtime(Desk(Path(directory)/'desk.db'),{'github_tasks':{'backup':task('backup','backup-secret')}},session,None)
                    if rt.checkins:rt.checkins.base_url=f'http://127.0.0.1:{site._server.sockets[0].getsockname()[1]}'
                    try:
                        result=await rt.command('github_task/check',{'task_id':'backup'},NOW)
                    except ValueError as exc:self.fail(f'需要保留连接检查: {exc}')
                    with self.assertRaises(ValueError):
                        await rt.command('github_task/template',{},NOW)
                    self.assertTrue(result['connected'])
                    self.assertIn('尚无执行记录',result['message'])
                    self.assertEqual(methods,['GET'])
                    self.assertNotIn('backup-secret',str(result))
        finally:await runner.cleanup()

    async def test_standard_template_two_repositories_use_distinct_tokens_and_import_business_failure(self):
        seen=[]
        async def respond(request):
            repo=request.match_info['repo']
            seen.append((repo,request.headers.get('Authorization')))
            if '/workflows/' in request.path:
                return web.json_response({'workflow_runs':[dict(id=1 if repo=='one' else 2,run_attempt=1,head_branch='main',
                    path='.github/workflows/task.yml',status='completed',conclusion='success',updated_at=NOW.isoformat())]})
            if request.path.endswith('/jobs'):
                return web.json_response({'total_count':1,'jobs':[dict(id=1,steps=[dict(name='MyDesk result',status='completed',conclusion='success')])]})
            result={'version':1,'status':'success' if repo=='one' else 'failed','message':'备份完成' if repo=='one' else '签到账号失效',
                    'metrics':[{'label':'文件','value':3,'unit':'个'}]}
            return web.Response(text='2026-10-02T08:00:00Z MYDESK_RESULT='+json.dumps(result,ensure_ascii=False)+'\n')
        app=web.Application()
        app.router.add_route('*','/repos/owner/{repo}/{tail:.*}',respond)
        runner=web.AppRunner(app)
        await runner.setup()
        site=web.TCPSite(runner,'127.0.0.1',0)
        await site.start()
        try:
            with tempfile.TemporaryDirectory() as directory:
                async with ClientSession() as session:
                    rt=Runtime(Desk(Path(directory)/'desk.db'),{'network':{'enabled':False},'github_tasks':{
                        'one':task('one','one-secret'),'two':task('two','two-secret')}},session,None)
                    if rt.checkins:rt.checkins.base_url=f'http://127.0.0.1:{site._server.sockets[0].getsockname()[1]}'
                    await rt.refresh(NOW)
                    tasks=(await rt.snapshot(NOW))['tasks']
                    self.assertEqual(len(tasks),2,'新增任务应进入工作台')
                    self.assertEqual(next(t for t in tasks if t['task_id']=='github.two')['status'],'failed','业务失败不能被工作流绿色状态掩盖')
                    self.assertIn('文件 3 个',next(t for t in tasks if t['task_id']=='github.one')['message'])
                    self.assertEqual(set(seen),{('one','Bearer one-secret'),('two','Bearer two-secret')})
                    self.assertNotIn('secret',str(tasks))
        finally:
            await runner.cleanup()

if __name__=='__main__':unittest.main()
