import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import fs from 'node:fs';
import {DeskClient} from '../frontend/transport.js';

test('upgrade retires only this application browser subscription and notifications',async()=>{
  const listeners={};let unsubscribed=0,closed=0,claimed=0,promise;
  const self={addEventListener:(type,fn)=>listeners[type]=fn,skipWaiting:async()=>{},
    registration:{pushManager:{getSubscription:async()=>({unsubscribe:async()=>unsubscribed++})},getNotifications:async()=>[{close:()=>closed++}]},
    clients:{claim:async()=>claimed++}};
  vm.runInNewContext(fs.readFileSync('frontend/sw.js','utf8'),{self});
  assert.equal(listeners.push,undefined);
  assert.equal(listeners.notificationclick,undefined);
  listeners.activate({waitUntil:p=>promise=p});
  await promise;
  assert.equal(unsubscribed,1);assert.equal(closed,1);assert.equal(claimed,1);
});

test('real-time connection retries again after an offline session check fails',async()=>{
  const priorLocation=globalThis.location,priorSocket=globalThis.WebSocket;
  const sockets=[];
  globalThis.location={href:'http://localhost/',protocol:'http:'};
  globalThis.WebSocket=class {constructor(){sockets.push(this);}close(){}};
  let checks=0;
  const client=new DeskClient(async()=>{if(++checks===1)throw new Error('offline');return {};},()=>{});
  client.retry=1;
  try {
    const stop=await client.subscribe(()=>{},()=>{});
    sockets[0].onclose({code:1006});
    await new Promise(resolve=>setTimeout(resolve,80));
    assert.equal(sockets.length,2,'A network error must not permanently stop reconnection');
    stop();
  } finally {client.close();globalThis.location=priorLocation;globalThis.WebSocket=priorSocket;}
});
