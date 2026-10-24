// Bound CI JVMs inside the existing cgroup; this never changes kernel limits or test scope.
import {existsSync,readFileSync, writeFileSync} from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';

export const JVM_RESOURCES = Object.freeze({daemonHeapMiB:1536, daemonMetaspaceMiB:256,
  testHeapMiB:3072, testInitialHeapMiB:256, testMetaspaceMiB:768, nativeReserveMiB:512, workers:2});
const need=(condition,message)=>{if(!condition)throw Error(message);};
const quantity=value=>{
  const raw=value.trim();
  need(raw==='max'||/^(0|[1-9][0-9]*)$/.test(raw),'Invalid kernel memory quantity');
  return raw==='max'?null:BigInt(raw);
};
export function resourceArguments() {
  const r=JVM_RESOURCES;
  return [`-Dorg.gradle.jvmargs=-Xmx${r.daemonHeapMiB}m -XX:MaxMetaspaceSize=${r.daemonMetaspaceMiB}m -XX:+HeapDumpOnOutOfMemoryError -Dfile.encoding=UTF-8`,
    `-Dorg.gradle.workers.max=${r.workers}`,`-PauraTestMaxHeap=${r.testHeapMiB}m`,
    `-PauraTestMinHeap=${r.testInitialHeapMiB}m`,`-PauraTestMaxMetaspace=${r.testMetaspaceMiB}m`];
}
export function inspectMemoryBudget({root='/sys/fs/cgroup',membership=readFileSync('/proc/self/cgroup','utf8')}={}) {
  const matches=membership.trim().split('\n').filter(line=>line.startsWith('0::'));
  need(matches.length===1,'Unified kernel cgroup membership required');
  const relative=matches[0].slice(3);
  need(relative.startsWith('/')&&!relative.split('/').includes('..'),'Unaliased cgroup membership required');
  const base=path.resolve(root),directory=path.resolve(base,'.'+relative);
  need(directory===base||directory.startsWith(base+path.sep),'Cgroup escapes mounted hierarchy');
  const rows=[];
  for(let current=directory;;current=path.dirname(current)) {
    // The unified mount root has no memory controller files on the registered host.
    if(current===base&&['memory.current','memory.high','memory.max'].every(file=>!existsSync(path.join(current,file))))break;
    const used=quantity(readFileSync(path.join(current,'memory.current'),'utf8'));
    need(used!==null,'Current kernel memory must be finite');
    for(const file of ['memory.high','memory.max']) {
      const limit=quantity(readFileSync(path.join(current,file),'utf8'));
      if(limit!==null)rows.push({directory:current,file,limitBytes:limit.toString(),currentBytes:used.toString(),
        availableBytes:(limit>used?limit-used:0n).toString()});
    }
    if(current===base)break;
  }
  need(rows.length>0,'Existing finite memory limits required for CI admission');
  const r=JVM_RESOURCES;
  const required=BigInt(r.daemonHeapMiB+r.daemonMetaspaceMiB+r.testHeapMiB+r.testMetaspaceMiB+r.nativeReserveMiB)*1024n**2n;
  return {schemaVersion:1,scope:'backend-ci-jvm-memory-admission',resources:r,requiredBytes:required.toString(),
    arguments:resourceArguments(),limits:rows,admitted:rows.every(row=>BigInt(row.availableBytes)>=required),
    testDenominatorChanged:false,kernelLimitsChanged:false};
}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url)) {
  try {
    need(process.argv.length===3&&path.isAbsolute(process.argv[2]),'Absolute original artifact report required');
    const result=inspectMemoryBudget();
    writeFileSync(process.argv[2],JSON.stringify(result,null,2)+'\n',{flag:'wx',mode:0o600});
    need(result.admitted,'Insufficient existing cgroup headroom for bounded daemon/test JVMs');
    process.stdout.write(result.arguments.join('\n')+'\n');
  }catch(error){console.error('environment-invalid: '+error.message);process.exitCode=2;}
}
