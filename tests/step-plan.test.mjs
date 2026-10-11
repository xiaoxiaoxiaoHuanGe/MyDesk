import test from 'node:test';
import assert from 'node:assert/strict';

test('progressive input predicts the first value strictly beyond the target',async()=>{
  const {planInput}=await import('../frontend/step-plan.js');
  assert.deepEqual(planInput({start:'1000',increment:'30',interval_minutes:'3',target:'1090'}),
    {start:1000,increment:30,interval_minutes:3,target:1090,random_percent:0,count:5,final:1120,duration:12});
  for(const patch of [{start:''},{increment:'0'},{interval_minutes:'0'},{target:'30000'},{increment:'1.5'},{start:'1100'}])
    assert.throws(()=>planInput({start:'1000',increment:'30',interval_minutes:'3',target:'1090',...patch}));
});

test('preset fill copies four values and never changes daily timing or the source object',async()=>{
  const {fillPreset}=await import('../frontend/step-plan.js');
  const preset={id:'walk',name:'散步',start:1000,increment:30,interval_minutes:3,target:1090};
  const original=JSON.stringify(preset);
  const draft=fillPreset({daily:true,start_time:'09:10'},preset);
  assert.equal(draft.daily,true);assert.equal(draft.start_time,'09:10');assert.equal(draft.increment,30);
  draft.start=2000;assert.equal(JSON.stringify(preset),original);
});

test('random range uses integer bounds, cap and slowest duration',async()=>{
  const {planInput,fillPreset}=await import('../frontend/step-plan.js');
  const p=planInput({start:80,increment:80,interval_minutes:3,target:975,random_percent:10});
  assert.equal(p.low,72);assert.equal(p.high,88);assert.deepEqual(p.count,[12,14]);assert.deepEqual(p.duration,[33,39]);assert.deepEqual(p.final,[976,1063]);
  assert.equal(fillPreset({},{}).random_percent,0);
  const capped=planInput({start:29999,increment:80,interval_minutes:1,target:29999,random_percent:10});assert.deepEqual(capped.final,[30000,30000]);
  assert.equal(planInput({start:1,increment:1,interval_minutes:1,target:1,random_percent:10}).low,1);
});
