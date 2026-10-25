#!/usr/bin/env node
// Real offline Gradle publication contracts. Fixture libraries are never remote publications.
import assert from 'node:assert/strict';
import {readFile,writeFile,mkdir,symlink,unlink,stat} from 'node:fs/promises';
import {spawnSync} from 'node:child_process';
import {createHash} from 'node:crypto';
import path from 'node:path';
import {fileURLToPath} from 'node:url';

const args=process.argv.slice(2);
assert.equal(args.length,4,'usage: test-library-version.mjs --gradle <absolute-executable> --evidence <fresh-directory>');
assert.equal(args[0],'--gradle');assert.equal(args[2],'--evidence');
const [gradle,evidence]=[args[1],args[3]];
assert.ok(path.isAbsolute(gradle)&&path.isAbsolute(evidence));assert.ok((await stat(gradle)).isFile());
await mkdir(evidence,{mode:0o700});
const source=fileURLToPath(new URL('../../platform/gradle/library-version.gradle',import.meta.url));
const script=await readFile(source),sha=bytes=>createHash('sha256').update(bytes).digest('hex');
const publicationSource=fileURLToPath(new URL('../../platform/gradle/library-publication.gradle',import.meta.url));
const publicationScript=await readFile(publicationSource);
const fixture=path.join(evidence,'fixture'),platform=path.join(fixture,'platform');
await mkdir(platform,{recursive:true});await mkdir(path.join(platform,'gradle'));
await writeFile(path.join(platform,'gradle/library-version.gradle'),script,{flag:'wx'});
await writeFile(path.join(platform,'gradle/library-publication.gradle'),publicationScript,{flag:'wx'});
await writeFile(path.join(fixture,'VERSION'),'1.0.0\n',{flag:'wx'});
const library=path.join(fixture,'LIBRARY_VERSION');
await writeFile(library,'1.0.1-rc.1\n',{flag:'wx'});
await writeFile(path.join(platform,'settings.gradle'),"rootProject.name='auraboot-core'\ninclude 'platform-plugin-api', 'unused-provider', 'platform-agent', 'platform-app', 'platform-bom'\n",{flag:'wx'});
await writeFile(path.join(platform,'build.gradle'),`
plugins { id 'java-library'; id 'maven-publish' }
allprojects {
    apply plugin: name == 'platform-bom' ? 'java-platform' : 'java-library'
    apply plugin: 'maven-publish'
    group = 'com.auraboot'
    publishing {
        publications { mavenJava(MavenPublication) { from (project.name == 'platform-bom' ? components.javaPlatform : components.java) } }
        repositories { maven { name='Fixture'; url=rootProject.file('fixture-repository') } }
    }
}
apply from: 'gradle/library-version.gradle'
apply from: 'gradle/library-publication.gradle'
dependencies { api project(':platform-plugin-api') }
tasks.register('selectedVersions') {
    doLast {
        file('versions.json').text=groovy.json.JsonOutput.toJson(allprojects.collectEntries { [(it.name):it.version.toString()] })
    }
}
tasks.named('prepareLibraryCandidate') {
    doFirst {
        if (providers.gradleProperty('fixtureCorruptPublication').orNull == 'true') {
            def repository = new File(providers.gradleProperty('aura.library.output').get())
            new File(repository, 'com/auraboot/auraboot-core/' + version + '/auraboot-core-' + version + '.pom').append('tampered')
        }
    }
}
`,{flag:'wx'});
const api=path.join(platform,'platform-plugin-api');await mkdir(api);
await writeFile(path.join(api,'build.gradle'),'java { withSourcesJar(); withJavadocJar() }\n',{flag:'wx'});
for (const [name,body] of [['platform-agent',"dependencies { api project(':') }\n"], ['platform-app',"dependencies { api project(':'); runtimeOnly project(':platform-agent') }\n"], ['platform-bom',"dependencies { constraints { api project(':'); api project(':platform-agent'); api project(':platform-app'); api project(':platform-plugin-api') } }\n"]]) {
    await mkdir(path.join(platform,name));await writeFile(path.join(platform,name,'build.gradle'),body,{flag:'wx'});
}
await mkdir(path.join(platform,'unused-provider'));
await writeFile(path.join(platform,'unused-provider/build.gradle'),'// Deliberately outside the runtime closure.\n',{flag:'wx'});
for(const [project,name,body] of [[platform,'Core','public class Core { public Api value() { return new Api(); } }'],[api,'Api','public class Api {}']]) {
    const dir=path.join(project,'src/main/java');await mkdir(dir,{recursive:true});
    await writeFile(path.join(dir,name+'.java'),body,{flag:'wx'});
}
const cases=[];
function run(name,properties=[],expectedFailure,tasks) {
    const parameters=['--project-dir',platform,'--gradle-user-home',path.join(evidence,'gradle-home'),
        '--no-daemon','--offline','--console=plain',...properties,...(tasks||(expectedFailure?['selectedVersions']:['publish','selectedVersions']))];
    const child=spawnSync(gradle,parameters,{encoding:'utf8',timeout:180000,maxBuffer:8*1024*1024,
        env:{...process.env,GRADLE_OPTS:'',JAVA_OPTS:'',JAVA_TOOL_OPTIONS:'',GRADLE_USER_HOME:path.join(evidence,'gradle-home')}});
    const log=(child.stdout||'')+'\n'+(child.stderr||'');
    return writeFile(path.join(evidence,name+'.log'),log,{flag:'wx'}).then(async()=>{
        assert.ifError(child.error);assert.equal(child.signal,null,name+' terminated');
        if(expectedFailure) {assert.notEqual(child.status,0);assert.ok(log.includes(expectedFailure),name+' failed outside the expected guard');}
        else assert.equal(child.status,0,log);
        cases.push({name,status:'passed',exitCode:child.status,expectedRed:Boolean(expectedFailure),log:name+'.log',sha256:sha(log)});
    });
}
await run('default-application');
const versions=version=>Object.fromEntries(['auraboot-core','platform-plugin-api','unused-provider','platform-agent','platform-app','platform-bom'].map(name=>[name,version]));
assert.deepEqual(JSON.parse(await readFile(path.join(platform,'versions.json'))),versions('1.0.0'));
await run('explicit-preview',['-PauraPlatformVersion=2.0.0-preview.1']);
assert.deepEqual(JSON.parse(await readFile(path.join(platform,'versions.json'))),versions('2.0.0-preview.1'));
await run('ambiguous-version',['-PauraPlatformVersion=2.0.0','-Paura.library.release=true'],'cannot be combined');
await run('invalid-preview',['-PauraPlatformVersion=../escape'],'auraPlatformVersion must be');
await run('explicit-library',['-Paura.library.release=true']);
assert.deepEqual(JSON.parse(await readFile(path.join(platform,'versions.json'))),versions('1.0.1-rc.1'));
const repository=path.join(platform,'fixture-repository/com/auraboot');
for(const artifact of ['auraboot-core','platform-plugin-api']) {
    const stem=artifact+'-1.0.1-rc.1';
    const pom=await readFile(path.join(repository,artifact,'1.0.1-rc.1',stem+'.pom'),'utf8');
    const module=JSON.parse(await readFile(path.join(repository,artifact,'1.0.1-rc.1',stem+'.module')));
    assert.ok(pom.includes('<version>1.0.1-rc.1</version>'));
    assert.equal(module.component.version,'1.0.1-rc.1');
    const jar=await readFile(path.join(repository,artifact,'1.0.1-rc.1',stem+'.jar'));assert.ok(jar.length>0);
    if(artifact==='auraboot-core') {
        assert.match(pom,/<artifactId>platform-plugin-api<\/artifactId>\s*<version>1\.0\.1-rc\.1<\/version>/);
        for(const variant of module.variants)for(const dependency of variant.dependencies||[])
            if(dependency.module==='platform-plugin-api')assert.equal(dependency.version.requires,'1.0.1-rc.1');
    }
}
for(const mode of ['false',''])await run('invalid-mode-'+(mode||'empty'),['-Paura.library.release='+mode],'aura.library.release must be explicitly true or absent');
for(const [name,version] of [['snapshot','1.0.1-SNAPSHOT'],['range','[1.0,2.0)'],['dynamic','1.0.+'],['empty','']]) {
    await writeFile(library,version+'\n');
    await run('invalid-'+name,['-Paura.library.release=true'],'A literal immutable library version is required');
}
await unlink(library);
await run('missing-library-version',['-Paura.library.release=true'],'A regular LIBRARY_VERSION is required');
await symlink(path.join(fixture,'VERSION'),library);
await run('linked-library-version',['-Paura.library.release=true'],'A regular LIBRARY_VERSION is required');
await run('default-without-library-input');
assert.deepEqual(JSON.parse(await readFile(path.join(platform,'versions.json'))),versions('1.0.0'));
await unlink(library);await writeFile(library,'1.0.1-rc.1\n',{flag:'wx'});
const candidate=path.join(evidence,'candidate-repository');
await run('candidate-mode-required',['-Paura.library.output='+candidate],'Library output requires explicit library release mode',['prepareLibraryCandidate']);
await run('candidate-output-required',['-Paura.library.release=true'],'Explicit library release mode and fresh output are required',['prepareLibraryCandidate']);
await run('candidate-relative-denied',['-Paura.library.release=true','-Paura.library.output=relative-candidate'],
    'A fresh absolute library output is required',['prepareLibraryCandidate']);
await run('candidate-source-output-denied',['-Paura.library.release=true','-Paura.library.output='+path.join(platform,'forbidden-candidate')],
    'Library output must be outside the source project',['prepareLibraryCandidate']);
await run('native-library-candidate',['-Paura.library.release=true','-Paura.library.output='+candidate],undefined,['prepareLibraryCandidate']);
const inventory=JSON.parse(await readFile(path.join(candidate,'library-publication-inventory.json')));
assert.equal(inventory.kind,'native-library-candidate');assert.equal(inventory.applicationBuild,'did_not_run');
assert.equal(inventory.remotePublication,'did_not_run');assert.equal(inventory.releaseQualification,false);
assert.deepEqual(inventory.publications.map(item=>item.coordinate.artifact).sort(),['auraboot-core','platform-plugin-api']);
for(const publication of inventory.publications) {
    assert.equal(publication.coordinate.version,'1.0.1-rc.1');
    assert.equal(publication.files.length,publication.coordinate.artifact==='platform-plugin-api'?5:3);
    for(const item of publication.files)assert.equal(sha(await readFile(path.join(candidate,item.path))),item.sha256);
    const primary=publication.files.find(item=>path.basename(item.path)===publication.coordinate.artifact+'-1.0.1-rc.1.jar');
    assert.equal(primary.sha256,publication.compiledJar.sha256);
}
await run('candidate-overwrite-denied',['-Paura.library.release=true','-Paura.library.output='+candidate],
    'A fresh absolute library output is required',['prepareLibraryCandidate']);
await run('candidate-metadata-tampering',['-Paura.library.release=true','-Paura.library.output='+path.join(evidence,'tampered-candidate'),'-PfixtureCorruptPublication=true'],
    'Published metadata/artifact differs from native output',['prepareLibraryCandidate']);
const expanded=path.join(evidence,'application-library-candidate');
await run('explicit-application-roots',['-Paura.library.release=true','-Paura.library.roots=:platform-app,:platform-bom','-Paura.library.output='+expanded],undefined,['prepareLibraryCandidate']);
const appInventory=JSON.parse(await readFile(path.join(expanded,'library-publication-inventory.json')));
assert.equal(appInventory.schemaVersion,2);assert.deepEqual(appInventory.publicationRoots,[':platform-app',':platform-bom']);
assert.deepEqual(appInventory.publications.map(row=>row.project).sort(),[':',':platform-agent',':platform-app',':platform-bom',':platform-plugin-api']);
const bom=appInventory.publications.find(row=>row.packaging==='pom');assert.equal(bom.project,':platform-bom');assert.equal(bom.compiledJar,null);
assert.deepEqual(bom.files.map(row=>path.extname(row.path)).sort(),['.module','.pom']);
for (const roots of [':missing',':platform-app,:platform-app','']) await run('invalid-roots-'+cases.length,
 ['-Paura.library.release=true','-Paura.library.roots='+roots,'-Paura.library.output='+path.join(evidence,'refused-'+cases.length)],
 roots===':missing'?'Unknown native publication root':'Explicit unique native publication roots required',['prepareLibraryCandidate']);
assert.equal(sha(await readFile(source)),sha(script),'native version recipe changed during execution');
assert.equal(sha(await readFile(publicationSource)),sha(publicationScript),'native publication recipe changed during execution');
const result={schemaVersion:1,status:'passed',scope:'isolated-gradle-publication-contract',recipeSha256:sha(script),
    publicationRecipeSha256:sha(publicationScript),cases,executed:cases.length,passed:cases.length,failed:0,skipped:0,releaseQualification:false,remotePublication:'did_not_run'};
await writeFile(path.join(evidence,'result.json'),JSON.stringify(result,null,2)+'\n',{flag:'wx'});
console.log(JSON.stringify(result,null,2));
