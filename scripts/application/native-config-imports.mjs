// Project the existing lock's ordered config nodes for native initialization.
import {readFileSync} from 'node:fs';
import {resolve} from 'node:path';
import {pathToFileURL} from 'node:url';
export function nativeConfigImportOrder(lock) {
  const artifacts=lock.artifacts.filter(a=>a.type==='config');
  const nodes=lock.composition.nodes.filter(n=>n.kind==='config').sort((a,b)=>a.order-b.order);
  if(!nodes.length||nodes.length!==artifacts.length||new Set(nodes.map(n=>n.id)).size!==nodes.length
    || new Set(nodes.map(n=>n.order)).size!==nodes.length
    || nodes.some(n=>!Number.isInteger(n.order)||n.order<0||!/^[a-z][a-z0-9-]*$/.test(n.id)
      ||artifacts.filter(a=>a.id===n.id&&a.localPath==='config/'+n.id).length!==1)) {
    throw Error('Native config import order does not match the verified lock');
  }
  return nodes.map(n=>n.id);
}
if(process.argv[1]&&pathToFileURL(resolve(process.argv[1])).href===import.meta.url) {
  if(process.argv.length!==3) throw Error('Usage: native-config-imports.mjs <application.lock>');
  console.log(nativeConfigImportOrder(JSON.parse(readFileSync(process.argv[2],'utf8'))).join('\n'));
}
