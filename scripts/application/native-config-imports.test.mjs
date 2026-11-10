import test from 'node:test';
import assert from 'node:assert/strict';
import {nativeConfigImportOrder} from './native-config-imports.mjs';
const lock=()=>({artifacts:['page-manager','core-meta'].map(id=>({type:'config',id,localPath:'config/'+id})),composition:{nodes:[{kind:'config',id:'page-manager',order:13},{kind:'runtime',id:'runtime',order:0},{kind:'config',id:'core-meta',order:12}]}});
test('uses composition order rather than artifact catalog sorting',()=>assert.deepEqual(nativeConfigImportOrder(lock()),['core-meta','page-manager']));
for(const [name,mutate] of [
 ['missing artifact',l=>l.artifacts.pop()],
 ['duplicate node',l=>l.composition.nodes.push({...l.composition.nodes[0]})],
 ['ambiguous order',l=>l.composition.nodes[2].order=13],
 ['unsafe config path',l=>l.artifacts[0].localPath='../page-manager'],
]) test('rejects '+name,()=>{const l=lock();mutate(l);assert.throws(()=>nativeConfigImportOrder(l),/does not match/);});
