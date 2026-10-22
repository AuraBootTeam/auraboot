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
const fixture=path.join(evidence,'fixture'),platform=path.join(fixture,'platform');
await mkdir(platform,{recursive:true});await mkdir(path.join(platform,'gradle'));
await writeFile(path.join(platform,'gradle/library-version.gradle'),script,{flag:'wx'});
await writeFile(path.join(fixture,'VERSION'),'1.0.0\n',{flag:'wx'});
const library=path.join(fixture,'LIBRARY_VERSION');
await writeFile(library,'1.0.1-rc.1\n',{flag:'wx'});
await writeFile(path.join(platform,'settings.gradle'),"rootProject.name='auraboot-core'\ninclude 'platform-plugin-api'\n",{flag:'wx'});
await writeFile(path.join(platform,'build.gradle'),`
plugins { id 'java-library'; id 'maven-publish' }
allprojects {
    apply plugin: 'java-library'
    apply plugin: 'maven-publish'
    group = 'com.auraboot'
    publishing {
        publications { mavenJava(MavenPublication) { from components.java } }
        repositories { maven { name='Fixture'; url=rootProject.file('fixture-repository') } }
    }
}
apply from: 'gradle/library-version.gradle'
dependencies { api project(':platform-plugin-api') }
tasks.register('selectedVersions') {
    doLast {
        file('versions.json').text=groovy.json.JsonOutput.toJson(allprojects.collectEntries { [(it.name):it.version.toString()] })
    }
}
`,{flag:'wx'});
const api=path.join(platform,'platform-plugin-api');await mkdir(api);
await writeFile(path.join(api,'build.gradle'),'// Inherits the selected native project version.\n',{flag:'wx'});
for(const [project,name,body] of [[platform,'Core','public class Core { public Api value() { return new Api(); } }'],[api,'Api','public class Api {}']]) {
    const dir=path.join(project,'src/main/java');await mkdir(dir,{recursive:true});
    await writeFile(path.join(dir,name+'.java'),body,{flag:'wx'});
}
const cases=[];
function run(name,properties=[],expectedFailure) {
    const parameters=['--project-dir',platform,'--gradle-user-home',path.join(evidence,'gradle-home'),
        '--no-daemon','--offline','--console=plain',...properties,...(expectedFailure?['selectedVersions']:['publish','selectedVersions'])];
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
assert.deepEqual(JSON.parse(await readFile(path.join(platform,'versions.json'))),{'auraboot-core':'1.0.0','platform-plugin-api':'1.0.0'});
await run('explicit-library',['-Paura.library.release=true']);
assert.deepEqual(JSON.parse(await readFile(path.join(platform,'versions.json'))),{'auraboot-core':'1.0.1-rc.1','platform-plugin-api':'1.0.1-rc.1'});
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
assert.deepEqual(JSON.parse(await readFile(path.join(platform,'versions.json'))),{'auraboot-core':'1.0.0','platform-plugin-api':'1.0.0'});
assert.equal(sha(await readFile(source)),sha(script),'native version recipe changed during execution');
const result={schemaVersion:1,status:'passed',scope:'isolated-gradle-publication-contract',recipeSha256:sha(script),
    cases,executed:cases.length,passed:cases.length,failed:0,skipped:0,releaseQualification:false,remotePublication:'did_not_run'};
await writeFile(path.join(evidence,'result.json'),JSON.stringify(result,null,2)+'\n',{flag:'wx'});
console.log(JSON.stringify(result,null,2));
