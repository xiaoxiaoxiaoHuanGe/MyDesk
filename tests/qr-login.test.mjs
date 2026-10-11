import test from 'node:test';
import assert from 'node:assert/strict';
import {QrLogin} from '../frontend/qr-login.js';

test('QR login waits for app confirmation and stops polling after approval',async()=>{
  const states=[],logged=[],jobs=[];let polls=0;
  const controller=new QrLogin({request:async path=>path==='/api/qr-login'?{id:'test',image:'qr',expires_in:120}:
    {status:++polls===1?'scanned':'approved',username:'mydesk',csrf:'guard'},
    onState:state=>states.push(state),onLogin:result=>logged.push(result),
    schedule:fn=>{jobs.push(fn);return jobs.length;},cancel:()=>{}});
  await controller.start();assert.equal(logged.length,0);
  await jobs.shift()();assert.equal(states.at(-1).status,'scanned');assert.equal(logged.length,0);
  await jobs.shift()();assert.equal(logged.length,1);assert.equal(logged[0].csrf,'guard');assert.equal(jobs.length,0);
});

test('switching to password cancels stale QR creation and confirmation callbacks',async()=>{
  let resolve;const states=[],logged=[],jobs=[];
  const controller=new QrLogin({request:()=>new Promise(done=>resolve=done),onState:state=>states.push(state),
    onLogin:result=>logged.push(result),schedule:fn=>jobs.push(fn),cancel:()=>{}});
  const pending=controller.start();controller.stop();resolve({id:'old',image:'old',expires_in:120});await pending;
  assert.deepEqual(states.map(s=>s.status),['loading']);assert.equal(jobs.length,0);assert.equal(logged.length,0);
});

test('expired QR and network failures offer refresh without automatic login',async()=>{
  for(const failure of ['expired','network']){
    const states=[],jobs=[],logged=[];
    const controller=new QrLogin({request:async path=>{
      if(path==='/api/qr-login')return {id:'test',image:'qr',expires_in:120};
      if(failure==='network')throw new Error('连接失败');return {status:'expired'};
    },onState:s=>states.push(s),onLogin:s=>logged.push(s),schedule:fn=>{jobs.push(fn);return 1;},cancel:()=>{}});
    await controller.start();await jobs.shift()();
    assert.equal(states.at(-1).status,failure==='network'?'error':'expired');
    assert.equal(jobs.length,0);assert.equal(logged.length,0);
  }
});
