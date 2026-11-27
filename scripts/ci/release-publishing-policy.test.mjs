import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {fileURLToPath} from 'node:url';
import path from 'node:path';
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'../..');
for(const name of ['build-image.yml','release-notes.yml']) {
  const text=readFileSync(path.join(root,'.github/workflows',name),'utf8');
  test(`${name}: automatic events cannot publish`,()=>{
    const block=text.match(/^on:\n([\s\S]*?)(?=^[^\s#]|$(?![\s\S]))/m)?.[1];
    assert.ok(block,'canonical event mapping required');
    assert.deepEqual([...block.matchAll(/^  ([a-z_]+):/gm)].map(m=>m[1]),['workflow_dispatch']);
    assert.match(block,/^      publish:\n[\s\S]*?^        type: boolean\n        default: false$/m);
  });
  test(`${name}: every publishing job requires explicit manual opt-in`,()=>{
    const jobs=text.slice(text.indexOf('\njobs:\n'));
    const sections=[...jobs.matchAll(/^  ([a-z][a-z0-9_-]*):\n([\s\S]*?)(?=^  [a-z][a-z0-9_-]*:|$(?![\s\S]))/gm)];
    assert.ok(sections.length>0);
    for(const [,id,section] of sections) assert.match(section,/^    if: github.event_name == 'workflow_dispatch' && inputs.publish == true$/m,id);
  });
}
test('image publishing retains its full three-image/two-architecture matrix and pushes only in guarded jobs',()=>{
 const text=readFileSync(path.join(root,'.github/workflows/build-image.yml'),'utf8');
 assert.match(text,/image: \[backend, frontend, postgres\]/);assert.match(text,/arch: \[amd64, arm64\]/);
 assert.match(text,/packages: write/);assert.match(text,/push=true/);
});
