const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const assert = require('node:assert/strict');
let resets = 0;
const flags = new Uint8Array(8).fill(1);
const model = {
  renderOrders: new Int32Array(8),
  drawables: {
    masks: [new Int32Array([6]), new Int32Array([7]), ...Array.from({length:6}, () => new Int32Array())],
    dynamicFlags: flags,
    resetDynamicFlags() { resets++; this.dynamicFlags.fill(1); return 'native-reset'; },
  },
};
const core = { Model: { fromMoc(moc) { return moc === null ? null : model; } } };
const sandbox = { window: {}, PurismCore:core };
vm.runInNewContext(fs.readFileSync(path.join(__dirname,'purism-pixi-bridge.js'),'utf8'), sandbox);
assert.equal(core.Model.fromMoc(null), null);
const result = core.Model.fromMoc({});
assert.equal(result.drawables.renderOrders, result.renderOrders);
for (let frame=0; frame<120; frame++) {
  assert.equal(result.drawables.resetDynamicFlags(), 'native-reset');
  assert.equal(flags[6], 33);
  assert.equal(flags[7], 33);
  for (let index=0;index<6;index++) assert.equal(flags[index],1);
}
assert.equal(resets,120);
const report={pass:true,frames:120,native_reset_preserved:true,mask_sources_redrawn_after_reset:true,
  unrelated_flags_unchanged:true,null_model_preserved:true,scope:'Mock compatibility adapter; GPU/browser acceptance is separate'};
fs.writeFileSync(path.resolve(process.argv[2] || 'mask-bridge-test.json'),JSON.stringify(report,null,2));
console.log(JSON.stringify(report));
