#!/usr/bin/env node
// Retain the already tested OCI bytes before the native image gate cleans its
// work root. This is transport only; no build, deployment or new qualification.
import {createReadStream} from 'node:fs';
import {readFile,lstat,readdir,mkdtemp,link,rm,chmod,writeFile,rename} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import {execFileSync} from 'node:child_process';
import path from 'node:path';
import {fileURLToPath} from 'node:url';

const requireThat=(ok,message)=>{if(!ok)throw new Error(message);};
const sha=async file=>{const hash=createHash('sha256');for await(const chunk of createReadStream(file))hash.update(chunk);return 'sha256:'+hash.digest('hex');};
const read=async file=>JSON.parse(await readFile(file,'utf8'));
async function regular(file) {requireThat((await lstat(file)).isFile(),'OCI input must be a regular non-symlink file');return file;}
export async function verifyOciLayout(layout,expectedDigest,loadedImageId) {
  requireThat((await lstat(layout)).isDirectory(),'OCI layout must be a non-symlink directory');
  requireThat((await read(await regular(path.join(layout,'oci-layout')))).imageLayoutVersion==='1.0.0','unsupported OCI layout');
  const index=await read(await regular(path.join(layout,'index.json')));
  requireThat(Array.isArray(index.manifests)&&index.manifests.length>0&&index.manifests[0].digest===expectedDigest,'tested OCI image differs');
  const visited=new Set(),configs=new Set(),primaryImages=new Set();
  const visit=async (descriptor,primary=false)=>{
    requireThat(/^sha256:[a-f0-9]{64}$/.test(descriptor?.digest)&&Number.isSafeInteger(descriptor.size)&&descriptor.size>=0
      &&descriptor.urls===undefined,'invalid or external OCI descriptor');
    const file=await regular(path.join(layout,'blobs','sha256',descriptor.digest.slice(7)));
    requireThat((await lstat(file)).size===descriptor.size&&await sha(file)===descriptor.digest,'OCI blob checksum mismatch');
    if(visited.has(descriptor.digest))return;visited.add(descriptor.digest);
    if(['application/vnd.oci.image.index.v1+json','application/vnd.docker.distribution.manifest.list.v2+json'].includes(descriptor.mediaType)) {
      const value=await read(file);requireThat(Array.isArray(value.manifests)&&value.manifests.length>0,'empty OCI image index');
      for(const child of value.manifests)await visit(child,primary);
    }else if(['application/vnd.oci.image.manifest.v1+json','application/vnd.docker.distribution.manifest.v2+json'].includes(descriptor.mediaType)) {
      const value=await read(file);requireThat(value.config&&Array.isArray(value.layers)&&value.layers.length>0,'incomplete OCI image manifest');
      if(primary&&value.layers.every(layer=>[
        'application/vnd.oci.image.layer.v1.tar','application/vnd.oci.image.layer.v1.tar+gzip','application/vnd.oci.image.layer.v1.tar+zstd',
        'application/vnd.docker.image.rootfs.diff.tar.gzip',
      ].includes(layer.mediaType)))primaryImages.add(descriptor.digest);
      if(primary)configs.add(value.config.digest);await visit(value.config);for(const child of value.layers)await visit(child);
    }else requireThat(['application/vnd.oci.image.config.v1+json','application/vnd.docker.container.image.v1+json',
      'application/vnd.oci.image.layer.v1.tar','application/vnd.oci.image.layer.v1.tar+gzip','application/vnd.oci.image.layer.v1.tar+zstd',
      'application/vnd.docker.image.rootfs.diff.tar.gzip','application/vnd.in-toto+json'].includes(descriptor.mediaType),'unsupported OCI media type');
  };
  // Reject links/extra files that would otherwise enter the archive without a digest.
  for(const item of await readdir(layout))requireThat(['index.json','oci-layout','blobs'].includes(item),'unindexed OCI layout input');
  requireThat((await lstat(path.join(layout,'blobs'))).isDirectory()&&(await lstat(path.join(layout,'blobs','sha256'))).isDirectory(),'OCI blob directory cannot be a symlink');
  requireThat((await readdir(path.join(layout,'blobs'))).join(',')==='sha256','unsupported OCI blob algorithm');
  for(const [position,descriptor] of index.manifests.entries())await visit(descriptor,position===0);
  // Classic Docker exposes the config digest as Id. Containerd-backed Docker
  // can expose a primary manifest/index digest instead. Both must belong to the
  // selected, completely verified image graph; layers and auxiliary roots do not.
  requireThat(configs.has(loadedImageId)||primaryImages.has(loadedImageId)
    ||(primaryImages.size>0&&loadedImageId===expectedDigest),'tested loaded image is absent from OCI layout');
  const stored=await readdir(path.join(layout,'blobs','sha256'));
  requireThat(stored.length===visited.size&&stored.every(name=>visited.has('sha256:'+name)),'unindexed OCI blob');
  return visited.size;
}
export async function exportProductImage({layout,receiptFile}) {
  await regular(receiptFile);const receipt=await read(receiptFile);
  requireThat(receipt.schemaVersion===1&&receipt.status==='PASS'
    &&['payload','browserJourney','freshDatabase','readiness'].every(key=>receipt[key]==='PASS'),'completed native image receipt required');
  requireThat(receipt.transport===undefined&&receipt.artifacts?.imageArchive===undefined,'OCI export already selected');
  await verifyOciLayout(layout,receipt.ociLayoutDigest,receipt.loadedImageId);
  const root=path.dirname(receiptFile),temporary=await mkdtemp(path.join(root,'.oci-export-'));
  const archive=path.join(temporary,'image.oci.tar'),destination=path.join(root,'image.oci.tar');
  try {
    execFileSync('tar',['-C',layout,'-cf',archive,'.'],{stdio:['ignore','pipe','pipe'],env:{...process.env,COPYFILE_DISABLE:'1'}});
    // Recheck after the archiver read: concurrent source drift cannot produce a
    // transport receipt for another image. Native source roots are frozen by CI.
    await verifyOciLayout(layout,receipt.ociLayoutDigest,receipt.loadedImageId);
    const digest=await sha(archive);await chmod(archive,0o440);await link(archive,destination);
    requireThat(JSON.stringify(await read(receiptFile))===JSON.stringify(receipt),'native receipt changed during export');
    const result={...receipt,transport:{mode:'ci-oci-archive'},artifacts:{...receipt.artifacts,imageArchive:{path:'image.oci.tar',digest}}};
    const output=path.join(temporary,'receipt.json');await writeFile(output,JSON.stringify(result,null,2)+'\n',{mode:0o600});
    await rename(output,receiptFile);return result;
  }finally{await rm(temporary,{recursive:true,force:true});}
}
export async function unpackOciArchive({archive,destination,archiveSha256,manifestDigest,imageId}) {
  requireThat(path.isAbsolute(archive)&&path.isAbsolute(destination),'explicit absolute OCI archive paths required');
  requireThat(/^[a-f0-9]{64}$/.test(archiveSha256)&&await sha(await regular(archive))==='sha256:'+archiveSha256,
    'selected OCI archive checksum differs');
  const program=`import pathlib,shutil,sys,tarfile
archive,destination=sys.argv[1:]
root=pathlib.Path(destination)
assert not root.exists() and not root.is_symlink(), 'OCI destination must be fresh'
with tarfile.open(archive,'r:*') as source:
 members=source.getmembers(); names=set()
 assert 0<len(members)<=10000 and sum(m.size for m in members)<=16*1024**3, 'invalid OCI archive denominator'
 for member in members:
  p=pathlib.PurePosixPath(member.name); name=str(p)
  assert not p.is_absolute() and '..' not in p.parts and '\\\\' not in member.name, 'unsafe OCI archive path'
  assert name not in names, 'duplicate OCI archive member'
  names.add(name)
  if member.isdir(): assert name in ('.','blobs','blobs/sha256'), 'unindexed OCI directory'
  else:
   assert member.isfile(), 'OCI links or special members rejected'
   assert name in ('index.json','oci-layout') or (len(p.parts)==3 and p.parts[:2]==('blobs','sha256') and len(p.parts[2])==64 and all(c in '0123456789abcdef' for c in p.parts[2])), 'unindexed OCI file'
 root.mkdir()
 for member in members:
  target=root/str(pathlib.PurePosixPath(member.name))
  if member.isdir(): target.mkdir(parents=True,exist_ok=True)
  else:
   target.parent.mkdir(parents=True,exist_ok=True)
   with source.extractfile(member) as stream, target.open('xb') as output: shutil.copyfileobj(stream,output)
`;
  execFileSync('python3',['-c',program,archive,destination],{stdio:['ignore','pipe','pipe']});
  await verifyOciLayout(destination,manifestDigest,imageId);
  return {archiveSha256,manifestDigest,imageId};
}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url)) {
  try{
    const args=process.argv.slice(2);
    if(args[0]==='unpack') {
      requireThat(args.length===6,'usage: export-product-image.mjs unpack <archive> <destination> <archive-sha256> <manifest-digest> <image-id>');
      const [,archive,destination,archiveSha256,manifestDigest,imageId]=args;
      await unpackOciArchive({archive,destination,archiveSha256,manifestDigest,imageId});
    }else {const [layout,receiptFile,...extra]=args;requireThat(layout&&receiptFile&&extra.length===0,'usage: export-product-image.mjs <OCI-layout> <native-receipt>');await exportProductImage({layout,receiptFile});}
  }
  catch(error){console.error(error.message);process.exitCode=2;}
}
