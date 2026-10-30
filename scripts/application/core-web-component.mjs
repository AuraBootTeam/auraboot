// Explicit Core Web build inputs and bytes. This is not application qualification.
import {createHash} from 'node:crypto';
import {execFileSync} from 'node:child_process';
import {closeSync,constants,cpSync,existsSync,fstatSync,lstatSync,openSync,readFileSync,readdirSync,realpathSync} from 'node:fs';
import path from 'node:path';
import {canonicalJson,sha256Path} from './application-contract.mjs';

const need=(condition,message)=>{if(!condition)throw new Error(message);};
const digest=value=>'sha256:'+createHash('sha256').update(value).digest('hex');
function readRegularWebFile(file,limit=Infinity) {
  const descriptor=openSync(file,constants.O_RDONLY|constants.O_NOFOLLOW);
  try {
    const stat=fstatSync(descriptor);
    need(stat.isFile()&&stat.size<=limit,'Bounded regular Web file required');
    const bytes=readFileSync(descriptor),after=fstatSync(descriptor);
    need(['dev','ino','size','mode','mtimeMs','ctimeMs'].every(key=>stat[key]===after[key])
      &&bytes.length===stat.size,'Web file changed while reading');
    return {bytes,stat};
  } finally {closeSync(descriptor);}
}
const roots=['packages','web-admin','scripts/application','distribution/application'];
const files=['VERSION','LICENSE.txt','package.json','pnpm-lock.yaml','pnpm-workspace.yaml'];
const optional=['.npmrc','tsconfig.json','tsconfig.base.json'];
const required=[...files,'web-admin/package.json','scripts/application/stage-core-only-artifacts.mjs',
  'scripts/application/core-web-component.mjs','scripts/application/application-contract.mjs'];
export const coreWebShellEntries=Object.freeze(['app','packages','public','scripts','react-router.config.ts',
  'instrumentation.server.mjs','tailwind.config.js','tsconfig.json','vite.config.ts','vitest.config.ts','vitest.setup.ts']);
export function isWebShellPublishableSource(candidate) {
  return !/(?:^|\/)(?:__tests__|tests|test-results|golden-harness|build|node_modules|\.vite)(?:\/|$)/.test(candidate)
    &&!/\.(?:test|spec)\.[cm]?[jt]sx?$/.test(candidate)&&!/\.tsbuildinfo$/.test(candidate);
}
const exact=(value,keys)=>need(value&&typeof value==='object'&&!Array.isArray(value)
  &&canonicalJson(Object.keys(value).sort())===canonicalJson([...keys].sort()),'Exact Web component fields required');
const source=value=>need(value&&typeof value.repository==='string'&&value.repository.length>0
  &&/^[a-f0-9]{40}$/.test(value.commit),'Immutable Web producer source required');
const hash=value=>/^sha256:[a-f0-9]{64}$/.test(value);
function validateSelection(selection) {
  exact(selection,['schemaVersion','kind','producer','source','lockIdentity','archiveSha256','inputDigest','componentDigest']);
  exact(selection.producer,['attemptId','candidateDigest','qualificationDigest','job']);source(selection.source);
  need(selection.schemaVersion===1&&selection.kind==='native-core-web-selection'
    &&hash(selection.lockIdentity)&&hash(selection.inputDigest)&&hash(selection.componentDigest)
    &&/^[a-f0-9]{64}$/.test(selection.archiveSha256),'Exact native Core Web selection required');
  const producer=selection.producer;
  need(['candidateDigest','qualificationDigest'].every(key=>/^[a-f0-9]{64}$/.test(producer[key]))
    &&['attemptId','job'].every(key=>typeof producer[key]==='string'&&/^[A-Za-z0-9._:-]{1,160}$/.test(producer[key])),
    'Original native Web producer identity required');
}

export function readCoreWebSelection({archive,selection,inputs}) {
  need([archive,selection,inputs].every(file=>path.isAbsolute(file)&&lstatSync(file).isFile()),
    'Regular absolute frozen Web inputs required');
  need(lstatSync(selection).size<=1024*1024,'Bounded native Web selection required');
  const before=sha256Path(inputs),dataset=JSON.parse(readRegularWebFile(inputs).bytes.toString('utf8'));
  need(Array.isArray(dataset.files),'Original frozen Web dataset required');
  const bind=file=>{
    const selected=dataset.files.filter(item=>item.file===file),actual=sha256Path(file).slice(7);
    need(selected.length===1&&selected[0].sha256===actual,'Web input differs from frozen dataset');return actual;
  };
  const archiveSha256=bind(archive),selectionSha256=bind(selection),bytes=readRegularWebFile(selection,1024*1024).bytes.toString('utf8');
  const document=JSON.parse(bytes);validateSelection(document);
  need(bytes===JSON.stringify(document,null,2)+'\n'&&document.archiveSha256===archiveSha256,
    'Native Web selection bytes or archive binding differ');
  need(sha256Path(inputs)===before&&sha256Path(selection).slice(7)===selectionSha256,
    'Frozen Web dataset changed while reading');
  return {document,selectionSha256};
}
function within(root,relative) {
  need(typeof relative==='string'&&!path.isAbsolute(relative)&&!relative.includes('\\')
    &&relative.split('/').every(part=>part&&part!=='.'&&part!=='..'),'Safe Web component path required');
  return path.join(root,relative);
}
function regularOutput(root,relative) {
  const target=within(root,relative);
  let current=root;
  need(lstatSync(root).isDirectory(),'Regular Web output root required');
  for(const part of relative.split('/')) {
    current=path.join(current,part);const stat=lstatSync(current);
    need(stat.isDirectory()||stat.isFile(),'Web output links and special files rejected');
  }
  const walk=file=>{
    const stat=lstatSync(file);need(stat.isDirectory()||stat.isFile(),'Web output links and special files rejected');
    if(stat.isDirectory())for(const name of readdirSync(file))walk(path.join(file,name));
  };
  walk(target);return target;
}
function outputTreeDigest(root,relative) {
  const base=regularOutput(root,relative),entries=[];
  const walk=(file,name)=>{
    const stat=lstatSync(file);
    need(stat.isDirectory()||stat.isFile(),'Web output links and special files rejected');
    if(stat.isFile()) {
      const selected=readRegularWebFile(file);
      entries.push({path:name,mode:selected.stat.mode&0o777,kind:'file',sha256:digest(selected.bytes)});
    } else entries.push({path:name,mode:stat.mode&0o777,kind:'directory'});
    if(stat.isDirectory())for(const child of readdirSync(file).sort())walk(path.join(file,child),name+'/'+child);
  };
  walk(base,relative);return digest(canonicalJson(entries));
}
function included(relative) {
  if(relative.startsWith('web-admin/')) {
    const entry=relative.slice('web-admin/'.length).split('/')[0];
    if(entry!=='package.json'&&!coreWebShellEntries.includes(entry))return false;
  }
  return (files.includes(relative)||optional.includes(relative)||roots.some(root=>relative===root||relative.startsWith(root+'/')))
    &&isWebShellPublishableSource(relative)
    &&!(relative.startsWith('packages/')&&/(?:^|\/)dist(?:\/|$)/.test(relative));
}

export function coreWebEnvironment(environment=process.env) {
  // Only these declared inputs enter npm/build subprocesses. Credentials and
  // ambient VITE_/PUBLIC_ values cannot silently become compiled configuration.
  const result={PATH:path.dirname(process.execPath)+path.delimiter+(environment.PATH||''),
    HOME:environment.HOME,CI:'1',TZ:'UTC',LANG:'C',LC_ALL:'C',
    NPM_CONFIG_USERCONFIG:'/dev/null',NPM_CONFIG_GLOBALCONFIG:'/dev/null',
    NPM_CONFIG_REGISTRY:environment.NPM_CONFIG_REGISTRY||'https://registry.npmjs.org'};
  const registry=new URL(result.NPM_CONFIG_REGISTRY);
  need(['https:','http:'].includes(registry.protocol)&&!registry.username&&!registry.password
    &&!registry.search&&!registry.hash,'Credential-free declared npm registry required');
  if(environment.TMPDIR)result.TMPDIR=environment.TMPDIR;
  if(environment.NODE_OPTIONS) {
    const heap=environment.NODE_OPTIONS.match(/^--max-old-space-size=([0-9]+)$/);
    need(heap&&Number(heap[1])>=512&&Number(heap[1])<=16384,'Only an explicit bounded Web heap option is supported');
    result.NODE_OPTIONS=environment.NODE_OPTIONS;
  }
  if(environment.AURA_COMPOSITION_KEY!==undefined) {
    need(hash(environment.AURA_COMPOSITION_KEY),'Exact Web composition key required');
    result.AURA_COMPOSITION_KEY=environment.AURA_COMPOSITION_KEY;
  }
  return result;
}

export function captureCoreWebInputs(repoRoot) {
  need(path.isAbsolute(repoRoot)&&lstatSync(repoRoot).isDirectory(),'Absolute Core source directory required');
  const git=args=>execFileSync('git',args,{cwd:repoRoot,encoding:'utf8'}).trim();
  const commit=git(['rev-parse','HEAD']),repository=git(['remote','get-url','origin']);
  source({commit,repository});
  need(git(['status','--porcelain','--untracked-files=all'])==='','Clean Core source required');
  need(!['.pnpmfile.cjs','pnpmfile.cjs'].some(file=>existsSync(path.join(repoRoot,file))),
    'Custom pnpm hooks require an explicit build-input contract');
  if(existsSync(path.join(repoRoot,'.npmrc')))need(!/^\s*(?:global-)?pnpmfile\s*=/mi.test(
    readRegularWebFile(path.join(repoRoot,'.npmrc')).bytes.toString('utf8')),'Custom pnpm hooks require an explicit build-input contract');
  const tracked=new Set(execFileSync('git',['ls-files','-z'],{cwd:repoRoot,encoding:'utf8'}).split('\0').filter(Boolean));
  const entries=[];
  const walk=relative=>{
    if(!included(relative))return;
    const file=within(repoRoot,relative),stat=lstatSync(file);
    need(stat.isDirectory()||stat.isFile(),'Web input links and special files rejected');
    if(stat.isDirectory())for(const name of readdirSync(file).sort())walk(relative+'/'+name);
    else {
      need(tracked.has(relative),'Untracked or ignored Web build input: '+relative);
      const selected=readRegularWebFile(file);
      entries.push({path:relative,mode:selected.stat.mode&0o777,sha256:digest(selected.bytes)});
    }
  };
  for(const relative of [...roots,...files,...optional.filter(file=>existsSync(path.join(repoRoot,file)))])walk(relative);
  entries.sort((a,b)=>a.path<b.path?-1:a.path>b.path?1:0);
  need(required.every(file=>entries.some(entry=>entry.path===file)),'Complete Web recipe and dependency inputs required');
  need([...tracked].filter(included).every(file=>entries.some(entry=>entry.path===file)),'Tracked Web input missing from disk');
  const environment=coreWebEnvironment();
  const executable=environment.PATH.split(path.delimiter).map(directory=>path.join(directory,'pnpm')).find(existsSync);
  need(executable,'Declared pnpm toolchain required');
  const pnpm=execFileSync(executable,['--version'],{cwd:repoRoot,encoding:'utf8',env:environment}).trim();
  need(/^[0-9]+\.[0-9]+\.[0-9]+(?:[-+][0-9A-Za-z.-]+)?$/.test(pnpm),'Exact pnpm version required');
  const inputs={schemaVersion:1,profile:'core-web-component-v1',files:entries,
    toolchain:{node:process.version,nodeSha256:digest(readRegularWebFile(process.execPath).bytes),
      pnpm,pnpmEntrySha256:digest(readRegularWebFile(realpathSync(executable)).bytes),platform:process.platform,arch:process.arch},
    environment:Object.fromEntries(Object.entries(environment).filter(([key])=>!['PATH','HOME','TMPDIR'].includes(key)))};
  return {source:{repository,commit},inputs,inputDigest:digest(canonicalJson(inputs))};
}

function validateInputs(inputs) {
  exact(inputs,['schemaVersion','profile','files','toolchain','environment']);
  need(inputs.schemaVersion===1&&inputs.profile==='core-web-component-v1'
    &&Array.isArray(inputs.files)&&inputs.files.length>0&&inputs.files.length<=100000,'Complete Core Web inputs required');
  let previous='';
  for(const entry of inputs.files) {
    exact(entry,['path','mode','sha256']);within('/inputs',entry.path);
    need(included(entry.path)&&entry.path>previous&&Number.isInteger(entry.mode)&&entry.mode>=0
      &&entry.mode<=0o777&&hash(entry.sha256),'Sorted unique Web input denominator required');previous=entry.path;
  }
  need(required.every(file=>inputs.files.some(entry=>entry.path===file)),'Complete Web recipe and dependency inputs required');
  exact(inputs.toolchain,['node','nodeSha256','pnpm','pnpmEntrySha256','platform','arch']);
  need(hash(inputs.toolchain.nodeSha256)&&hash(inputs.toolchain.pnpmEntrySha256)
    &&Object.values(inputs.toolchain).every(value=>typeof value==='string'&&value),'Exact Web toolchain required');
  const expected=coreWebEnvironment(inputs.environment);
  const portable=Object.fromEntries(Object.entries(expected).filter(([key])=>!['PATH','HOME','TMPDIR'].includes(key)));
  need(canonicalJson(portable)===canonicalJson(inputs.environment),'Declared Web environment required');
}

export function createCoreWebComponent({root,catalog,before,after}) {
  need(canonicalJson(before)===canonicalJson(after),'Core Web inputs changed during build');
  validateInputs(before.inputs);source(before.source);
  const artifacts=catalog.artifacts.filter(artifact=>artifact.type==='npm');
  need(artifacts.length>0&&artifacts.every(artifact=>canonicalJson(artifact.source)===canonicalJson(before.source)),
    'Original native npm producer denominator required');
  const component={schemaVersion:1,source:before.source,inputs:before.inputs,inputDigest:before.inputDigest,
    web:{path:'web',digest:sha256Path(path.join(root,'web')),treeDigest:outputTreeDigest(root,'web')},artifacts};
  verifyCoreWebComponent({root,catalog,receipt:{source:before.source,webComponent:component}});
  return component;
}

export function verifyCoreWebComponent({root,catalog,receipt}) {
  if(receipt.webComponent===undefined) {
    need(receipt.webConsumption===undefined,'Web consumption requires original input proof');return null;
  }
  const component=receipt.webComponent;
  exact(component,['schemaVersion','source','inputs','inputDigest','web','artifacts']);
  source(component.source);validateInputs(component.inputs);
  need(component.source.repository===receipt.source.repository,'Web producer repository differs');
  need(component.schemaVersion===1&&component.inputDigest===digest(canonicalJson(component.inputs)),
    'Web input identity differs');
  exact(component.web,['path','digest','treeDigest']);
  need(component.web.path==='web'&&lstatSync(regularOutput(root,'web')).isDirectory()
    &&sha256Path(path.join(root,'web'))===component.web.digest
    &&outputTreeDigest(root,'web')===component.web.treeDigest,'Original Web output differs');
  const npm=catalog.artifacts.filter(artifact=>artifact.type==='npm');
  need(npm.length>0&&canonicalJson(npm)===canonicalJson(component.artifacts),'Web npm output denominator differs');
  for(const artifact of npm) {
    const file=regularOutput(root,artifact.localPath);
    need(canonicalJson(artifact.source)===canonicalJson(component.source)&&lstatSync(file).isFile()
      &&sha256Path(file)===artifact.digest,'Original npm output differs');
  }
  if(receipt.webConsumption===undefined) {
    need(canonicalJson(component.source)===canonicalJson(receipt.source),'Web producer differs without consumption proof');
  } else {
    const consumed=receipt.webConsumption;
    const managed=consumed.selectionSha256!==undefined||consumed.selection!==undefined;
    exact(consumed,['schemaVersion','source','lockIdentity','archiveSha256','datasetReceiptSha256','componentDigest',
      ...(managed?['selectionSha256','selection']:[])]);
    source(consumed.source);
    need(consumed.source.repository===receipt.source.repository&&consumed.schemaVersion===1
      &&hash(consumed.lockIdentity)&&/^[a-f0-9]{64}$/.test(consumed.archiveSha256)
      &&/^[a-f0-9]{64}$/.test(consumed.datasetReceiptSha256)
      &&consumed.componentDigest===digest(canonicalJson(component)),'Original Web consumption provenance differs');
    if(managed) {
      validateSelection(consumed.selection);
      need(consumed.selectionSha256===digest(JSON.stringify(consumed.selection,null,2)+'\n').slice(7)
        &&canonicalJson(consumed.selection.source)===canonicalJson(consumed.source)
        &&canonicalJson(consumed.selection.source)===canonicalJson(component.source)
        &&consumed.selection.lockIdentity===consumed.lockIdentity
        &&consumed.selection.archiveSha256===consumed.archiveSha256
        &&consumed.selection.componentDigest===consumed.componentDigest
        &&consumed.selection.inputDigest===component.inputDigest,'Frozen Web selection receipt differs');
    }
  }
  return component;
}

export function copyCoreWebComponent({root,destination,catalog,receipt,current,consumption,datasetReceiptSha256,selectionSha256,selection}) {
  const component=verifyCoreWebComponent({root,catalog,receipt});
  need(component,'Historical Core build has no Web input proof; no reuse');
  need(component.inputDigest===current.inputDigest&&canonicalJson(component.inputs)===canonicalJson(current.inputs),
    'Selected Web effective inputs differ; no build fallback');
  need(consumption.source.commit===receipt.source.commit&&consumption.lockIdentity===receipt.lockIdentity,
    'Original platform consumption differs');
  const paths=[component.web.path,...component.artifacts.map(artifact=>artifact.localPath)];
  need(new Set(paths).size===paths.length,'Duplicate Web output destination');
  need(lstatSync(destination).isDirectory(),'Regular Web destination required');
  for(const relative of paths) {
    const target=within(destination,relative);let parent=path.dirname(target);
    while(parent!==destination) {
      const stat=lstatSync(parent,{throwIfNoEntry:false});
      need(!stat||stat.isDirectory(),'Regular Web destination parents required');parent=path.dirname(parent);
    }
    need(!lstatSync(target,{throwIfNoEntry:false}),'Web output already exists');
  }
  for(const relative of paths)cpSync(within(root,relative),within(destination,relative),
    {recursive:true,force:false,errorOnExist:true});
  const webConsumption={schemaVersion:1,source:receipt.source,lockIdentity:receipt.lockIdentity,
    archiveSha256:consumption.archive.sha256,datasetReceiptSha256,componentDigest:digest(canonicalJson(component)),
    ...(selectionSha256!==undefined?{selectionSha256,selection}:{})};
  verifyCoreWebComponent({root:destination,catalog:{artifacts:component.artifacts},
    receipt:{source:current.source,webComponent:component,webConsumption}});
  return {webComponent:component,webConsumption};
}
