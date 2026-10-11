import test from 'node:test';
import assert from 'node:assert/strict';
import {inboxRows} from '../frontend/inbox.js';
test('inbox escapes sender text and preserves safe list summaries',()=>{
  const html=inboxRows([{id:'a'.repeat(32),source_name:'CPE',title:'<script>x</script>',summary:'验证码 "123"\n📱',received_at:'2026-10-07T12:00:00Z',read_at:null}],'Asia/Shanghai');
  assert.ok(html.includes('&lt;script&gt;'));assert.ok(!html.includes('<script>'));assert.ok(html.includes('未读'));assert.ok(html.includes('📱'));
});
