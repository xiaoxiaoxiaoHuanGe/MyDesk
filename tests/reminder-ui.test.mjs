import test from 'node:test';
import assert from 'node:assert/strict';
import * as picker from '../frontend/time-picker.js';

test('numeric picker wraps across midnight and minute boundaries in both directions',()=>{
  assert.equal(typeof picker.wrapIndex,'function');
  assert.equal(picker.wrapIndex(-1,60),59);
  assert.equal(picker.wrapIndex(60,60),0);
  assert.equal(picker.wrapIndex(24,24),0);
});

test('entering the reminder form selects the next minute including the next date',()=>{
  assert.equal(typeof picker.initialPickerDate,'function');
  const selected=picker.initialPickerDate('Asia/Shanghai',new Date('2026-10-16T15:59:45Z'));
  assert.equal(selected.date,'2026-10-17');
  assert.equal(selected.hour,'00');
  assert.equal(selected.minute,'00');
});
