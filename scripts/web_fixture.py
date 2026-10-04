"""Disposable localhost UI fixture. Uses synthetic data and never contacts external services."""
from pathlib import Path
import sys,asyncio
from datetime import timedelta
from aiohttp import web
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from mydesk.app import create_app,STATE
from mydesk.runtime import now_utc
from mydesk.github_checkins import task_binding

project=Path(__file__).resolve().parents[1]
app=create_app(project/'.local/web-fixture',scheduling=False)
s=app[STATE]['settings']
s.update({'network':{'enabled':True,'nodes':[{'name':n,'host':n.lower().replace(' ','-')+'.example.com'} for n in ['Hong Kong','Tokyo','Singapore','Frankfurt','Seattle','London','Sydney','Paris']]},
 'github':{'owner':'demo-owner','repo':'WxStepCustom','workflow':'steps.yml','ref':'main','token':'demo-steps-token'},
 'github_tasks':{k:{'name':n,'owner':'demo-owner','repo':r,'workflow':'checkin.yml','ref':'main','token':'demo-'+k+'-token','adapter':'workflow','step':'MyDesk result','max_age_hours':36,'enabled':True} for k,n,r in [('forum','每日论坛签到','Forum-Daily'),('glados','GLaDOS 签到','Glados-Checkin'),('buddy','WorkBuddy 签到','WorkBuddy-Daily')]},
 'gmail_accounts':{k:{'name':n,'username':k+'@example.com','password':'demo-mail-password-'+k,'limit':5,'enabled':True} for k,n in [('personal','个人邮箱'),('work','工作邮箱')]},
 'server_sources':{k:{'name':n,'provider':'1panel','url':'https://'+k+'.example.com','api_key':'demo-panel-key-'+k,'signature':'md5','enabled':True} for k,n in [('main','主服务器'),('backup','备份服务器')]}})
async def seed(app):
 rt=app[STATE]['runtime'];now=now_utc()
 async def feeds(*args,**kwargs):
  now=now_utc()
  nodes=[{'name':n,'host':n.lower().replace(' ','-')+'.example.com','online':i!=5,'ping':None if i==5 else 23+i*19} for i,n in enumerate(['Hong Kong','Tokyo','Singapore','Frankfurt','Seattle','London','Sydney','Paris'])]
  rt.desk.set_feed('network',{'online':True,'ping':38.246,'public_ip':'203.0.113.42','nodes':nodes},now)
  rt.desk.set_feed('servers',{'items':[{'id':k,'name':n,'provider':'1panel','status':'up','cpu':v,'ram':42.847391,'disk':61.233421,'load':[0.31,0.47,0.28],'network':{'sent_bytes':2415919104,'received_bytes':9532456789},'temperature':None} for k,n,v in [('main','主服务器',17.2468),('backup','备份服务器',6.5223)]]},now)
  accounts=[];all_items=[]
  subjects=['项目周报 · 本周进展与下周计划','你的服务已续费成功','新版本发布：让每件事更有条理','下午的会议资料已准备好','欢迎使用你的个人工作台']
  for ai,(k,n) in enumerate([('personal','个人邮箱'),('work','工作邮箱')]):
   items=[{'id':k+str(i),'account_id':k,'account_name':n,'account_email':k+'@example.com','sender':['MyDesk 团队','账单服务','产品通知'][i%3],'subject':subjects[i],'received_at':(now-timedelta(minutes=12+i*41+ai*17)).isoformat(),'unread':i<2} for i in range(5)]
   accounts.append({'id':k,'name':n,'username':k+'@example.com','items':items,'unread':2});all_items.extend(items)
  rt.desk.set_feed('mail',{'accounts':accounts,'items':sorted(all_items,key=lambda m:m['received_at'],reverse=True)[:3],'unread':4,'unread_complete':True},now)
  rt.desk.set_feed('daily_quote',{'text':'生活的理想，就是为了理想的生活。','source':'每日一言','author':'张闻天','date':now.date().isoformat()},now)
  await rt.publish()
 for key,spec in rt.config['github_tasks'].items():
  rt.desk.report_task({'task_id':'github.'+key,'task_name':spec['name'],'status':'warning' if key=='buddy' else 'success','message':'本次任务尚未完成，请稍后查看' if key=='buddy' else '今日签到已完成','source':'GitHub Actions','timestamp':now,'event_id':'github.'+key+'.'+task_binding(spec)+'.demo.completed'},now)
 if not rt.desk.snapshot(now)['reminders']:
  for title,minutes in [('整理本周计划',45),('给植物浇水',120),('检查服务器备份',180)]:rt.desk.create_reminder(title,now+timedelta(minutes=minutes),now)
 if not rt.desk.snapshot(now)['wxstep']:
  job=rt.desk.create_job(10899,now);rt.desk.update_job(job['id'],status='success',message='success')
 rt.refresh_all=feeds
 original=rt.command
 async def command(action,payload,now=None):
  if action in ['service/check','github_task/check']:return {'connected':True,'message':'演示环境连接检查成功'}
  if action=='wxstep/submit':
   job=rt.desk.create_job(payload['steps'],now_utc());rt.desk.update_job(job['id'],status='success',message='success');await rt.publish();return job
  return await original(action,payload,now)
 rt.command=command
 await feeds()
app.on_startup.append(seed)
web.run_app(app,host='127.0.0.1',port=8790,access_log=None,print=lambda _:print('Isolated MyDesk Web preview: http://127.0.0.1:8790'))
