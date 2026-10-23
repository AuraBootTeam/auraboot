#!/usr/bin/env node
// Transport an explicitly selected native Core build. This does not publish
// Maven/npm coordinates, infer cache hits, or grant application qualification.
import {createReadStream} from 'node:fs';
import {readFile,lstat,readdir,mkdtemp,link,rm,chmod} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import {execFileSync} from 'node:child_process';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {canonicalJson,verifyArtifacts,sha256Path} from './application-contract.mjs';
import {verifyOciLayout} from './export-product-image.mjs';
import {verifyCoreWebComponent} from './core-web-component.mjs';

const need=(ok,message)=>{if(!ok)throw new Error(message);};
const read=async file=>JSON.parse(await readFile(file,'utf8'));
const hash=async file=>{
  const h=createHash('sha256');for await(const chunk of createReadStream(file))h.update(chunk);
  return h.digest('hex');
};
async function tree(root) {
  need(path.isAbsolute(root)&&(await lstat(root)).isDirectory(),'absolute non-symlink platform directory required');
  const entries={};
  const walk=async (directory,prefix='')=>{
    for(const name of (await readdir(directory)).sort()) {
      const relative=prefix?`${prefix}/${name}`:name,file=path.join(directory,name),s=await lstat(file);
      need(!name.includes('\\')&&(s.isFile()||s.isDirectory()),'platform links or special files rejected');
      entries[relative]={kind:s.isDirectory()?'directory':'file',mode:s.mode&0o777,
        ...(s.isFile()?{sha256:await hash(file)}:{})};
      if(s.isDirectory())await walk(file,relative);
    }
  };
  await walk(root);need(Object.keys(entries).length>0,'empty platform directory');return entries;
}
function within(root,relative) {
  need(typeof relative==='string'&&!path.isAbsolute(relative)&&!relative.includes('\\')
    &&relative.split('/').every(p=>p&&p!=='.'&&p!=='..'),'unsafe native platform path');
  return path.join(root,relative);
}
export async function verifyPlatformArtifacts({root,coreCommit}) {
  need(/^[a-f0-9]{40}$/.test(coreCommit),'exact Core source required');
  // Validate the complete directory before any checksum helper follows paths.
  const entries=await tree(root),receipt=await read(path.join(root,'release-receipt.json'));
  const catalog=await read(path.join(root,'artifact-catalog.json'));
  const lock=await read(path.join(root,'application.lock'));
  need(receipt.schemaVersion===1&&receipt.application?.id==='auraboot-core-only'
    &&receipt.source?.commit===coreCommit&&receipt.verification?.result==='PASS'
    &&receipt.verification.resolver==='application-contract-v1'&&receipt.verification.checksum==='sha256',
    'completed matching native Core build required');
  verifyArtifacts(lock,{artifactRoot:root});
  need(receipt.lockIdentity===lock.identity&&receipt.manifestDigest===lock.manifestDigest
    &&receipt.graphDigest===lock.composition.graphDigest
    &&canonicalJson(receipt.application)===canonicalJson(lock.application),'Core receipt and lock differ');
  need(catalog.schemaVersion===1&&Array.isArray(catalog.artifacts)&&catalog.artifacts.length>0,'native platform catalog required');
  const webComponent=verifyCoreWebComponent({root,catalog,receipt});
  const keys=new Set();
  for(const artifact of catalog.artifacts) {
    const key=`${artifact.type}:${artifact.id}`;
    need(!keys.has(key),'duplicate native platform coordinate');keys.add(key);
    need(typeof artifact.version==='string'&&/^[0-9A-Za-z][0-9A-Za-z.+-]*$/.test(artifact.version)
      && !/(snapshot|latest|workspace|branch)/i.test(artifact.version),'immutable platform version required');
    const selectedWeb=artifact.type==='npm'&&webComponent?.artifacts.some(item=>canonicalJson(item)===canonicalJson(artifact));
    need(selectedWeb||(artifact.source?.commit===coreCommit&&artifact.source?.repository===receipt.source.repository),
      'platform catalog source differs');
    need(sha256Path(within(root,artifact.localPath))===artifact.digest,'platform catalog checksum differs');
  }
  for(const artifact of lock.artifacts) {
    need(catalog.artifacts.filter(item=>canonicalJson(item)===canonicalJson(artifact)).length===1,
      'platform lock artifact differs from catalog');
  }
  const projection=lock.artifacts.map(({type,id,version,digest,localPath})=>({type,id,version,digest,localPath}));
  need(canonicalJson(receipt.artifacts)===canonicalJson(projection),'Core receipt artifact denominator differs');
  for(const item of [receipt.web,receipt.sbom,receipt.deploymentScript])
    need(item&&sha256Path(within(root,item.path))===item.digest,'Core auxiliary artifact differs');
  need(receipt.image?.id==='auraboot-runtime','native runtime image required');
  const image=within(root,receipt.image.layout);
  need(lock.artifacts.filter(a=>a.type==='oci'&&a.id==='auraboot-runtime'&&a.localPath===receipt.image.layout).length===1,
    'Core runtime image differs from lock');
  await verifyOciLayout(image,receipt.image.digest,receipt.image.digest);
  return {source:receipt.source,lockIdentity:lock.identity,entries};
}
export async function exportPlatformArtifacts({root,coreCommit,archive}) {
  need(path.isAbsolute(archive),'absolute platform archive required');
  const before=await verifyPlatformArtifacts({root,coreCommit});
  const temporary=await mkdtemp(path.join(path.dirname(archive),'.platform-export-'));
  try {
    const file=path.join(temporary,'platform.tar');
    execFileSync('tar',['-C',root,'-cf',file,'.'],{env:{...process.env,COPYFILE_DISABLE:'1'},stdio:['ignore','pipe','pipe']});
    need(canonicalJson(await verifyPlatformArtifacts({root,coreCommit}))===canonicalJson(before),'platform changed during export');
    const sha256=await hash(file);await chmod(file,0o440);await link(file,archive);
    return {schemaVersion:1,kind:'native-platform-artifact-package',source:before.source,
      lockIdentity:before.lockIdentity,archive:{sha256},releaseQualification:false};
  }finally{await rm(temporary,{recursive:true,force:true});}
}
export async function unpackPlatformArtifacts({archive,archiveSha256,destination,coreCommit}) {
  need(path.isAbsolute(archive)&&path.isAbsolute(destination),'absolute platform package paths required');
  need((await lstat(archive)).isFile()&&/^[a-f0-9]{64}$/.test(archiveSha256)
    &&await hash(archive)===archiveSha256,'selected platform archive checksum differs');
  const program=`import os,pathlib,shutil,sys,tarfile
archive,destination=sys.argv[1:]
root=pathlib.Path(destination)
assert not root.exists() and not root.is_symlink(), 'platform destination must be fresh'
with tarfile.open(archive,'r:*') as source:
 members=source.getmembers(); names=set()
 assert 0<len(members)<=200000 and sum(m.size for m in members)<=16*1024**3, 'invalid platform archive denominator'
 for member in members:
  p=pathlib.PurePosixPath(member.name); name=str(p)
  assert not p.is_absolute() and '..' not in p.parts and '\\\\' not in member.name, 'unsafe platform archive path'
  assert name not in names, 'duplicate platform archive member'
  assert member.isfile() or member.isdir(), 'platform links or special members rejected'
  assert member.mode & ~0o777 == 0, 'unsupported platform permissions'
  names.add(name)
 root.mkdir()
 for member in members:
  target=root/str(pathlib.PurePosixPath(member.name))
  if member.isdir(): target.mkdir(parents=True,exist_ok=True)
  else:
   target.parent.mkdir(parents=True,exist_ok=True)
   with source.extractfile(member) as stream,target.open('xb') as output: shutil.copyfileobj(stream,output)
 # Apply directory modes only after all children have been written.
 for member in reversed(members): os.chmod(root/str(pathlib.PurePosixPath(member.name)),member.mode)
`;
  execFileSync('python3',['-c',program,archive,destination],{stdio:['ignore','pipe','pipe']});
  const verified=await verifyPlatformArtifacts({root:destination,coreCommit});
  need(await hash(archive)===archiveSha256,'platform archive changed during consumption');
  return {schemaVersion:1,kind:'native-platform-artifact-consumption',source:verified.source,
    lockIdentity:verified.lockIdentity,archive:{sha256:archiveSha256},releaseQualification:false};
}
export async function consumePlatformArtifacts({archive,inputs,destination,coreCommit}) {
  need(path.isAbsolute(inputs)&&(await lstat(inputs)).isFile(),'original dataset receipt required');
  const before=await hash(inputs),receipt=await read(inputs);
  need(Array.isArray(receipt.files),'original dataset file denominator required');
  const selected=receipt.files.filter(item=>item.file===archive);
  need(selected.length===1&&/^[a-f0-9]{64}$/.test(selected[0].sha256),'one frozen platform archive input required');
  const result=await unpackPlatformArtifacts({archive,archiveSha256:selected[0].sha256,destination,coreCommit});
  need(await hash(inputs)===before,'platform dataset receipt changed during consumption');
  return result;
}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url)) {
  try {
    const [operation,...args]=process.argv.slice(2);let result;
    if(operation==='export') {
      need(args.length===3,'usage: platform-artifact-package.mjs export <root> <Core-SHA> <archive>');
      result=await exportPlatformArtifacts({root:args[0],coreCommit:args[1],archive:args[2]});
    }else if(operation==='consume') {
      need(args.length===4,'usage: platform-artifact-package.mjs consume <archive> <dataset-inputs> <destination> <Core-SHA>');
      result=await consumePlatformArtifacts({archive:args[0],inputs:args[1],destination:args[2],coreCommit:args[3]});
    }else {
      need(operation==='unpack'&&args.length===4,'usage: platform-artifact-package.mjs unpack <archive> <archive-SHA256> <destination> <Core-SHA>');
      result=await unpackPlatformArtifacts({archive:args[0],archiveSha256:args[1],destination:args[2],coreCommit:args[3]});
    }
    console.log(JSON.stringify(result,null,2));
  }catch(error){console.error(error.message);process.exitCode=2;}
}
