import unittest
from mydesk.attention import actionable_attention


class AttentionTests(unittest.TestCase):
    def test_priority_destinations_and_duplicate_source_are_stable(self):
        state=dict(feeds={'servers':{'data':{'items':[dict(id='server:a',source_id='server',error='认证失败')]}}},attention=[
            dict(kind='integration',id='mail.work',title='邮箱',message='失败'),
            dict(kind='server',id='server:a',title='服务器',message='失败'),
            dict(kind='reminder',id='r',title='提醒',message='到期'),
            dict(kind='task',id='github.check',title='任务',message='失败'),
            dict(kind='integration',id='mail.work',title='邮箱',message='失败'),
            dict(kind='steps',id='s',title='步数',message='不确定')])
        rows=actionable_attention(state)
        self.assertEqual([r['kind'] for r in rows],['reminder','steps','task','integration','server'])
        self.assertEqual(rows[3]['destination'],dict(type='settings',section='mail',id='work'))
        self.assertEqual(rows[4]['destination'],dict(type='settings',section='servers',id='server'))
