import test from 'node:test';
import assert from 'node:assert/strict';
import {caseNavigationPlan,installCaseRoleNavigation} from './case-role-navigation.mjs';
const id=n=>'01'+String(n).padStart(24,'0');
const bundle={plugins:[{files:{'plugin.json':{content:JSON.stringify({resourceDirs:{roles:'roles.json',menus:'menus.json'}})},
 'roles.json':{content:JSON.stringify([{code:'case_viewer',permissions:['case.read']}])},
 'menus.json':{content:JSON.stringify([{code:'case',type:1,permissionCode:'case.read'},{code:'write',type:1,permissionCode:'case.write'},{code:'unprotected',type:1}])}}}]};
test('recipe selects only explicitly declared readable leaves and rejects baseline roles',()=>{
 assert.deepEqual(caseNavigationPlan(bundle),[{roleCode:'case_viewer',menuCodes:['case']}]);
 const unsafe=structuredClone(bundle);unsafe.plugins[0].files['roles.json'].content=JSON.stringify([{code:'tenant_admin',permissions:['case.read']}]);
 assert.throws(()=>caseNavigationPlan(unsafe),/declared case roles/);
});
function fixture({businessAllowed=true,ambiguous=false,stale=false,wrongReadback=false}={}){
 const calls=[],revision='a'.repeat(64);let selected=[id(3)],writes=0;
 const request=async(route,body,method)=>{
  calls.push({route,body,method});
  if(route==='/api/roles/all')return [{code:'case_viewer',pid:id(1)},{code:'tenant_admin',pid:id(9)}];
  if(route==='/api/menu/all')return [{code:'root',children:[{code:'case',type:1,pid:id(2)},...(ambiguous?[{code:'case',type:1,pid:id(4)}]:[])]}];
  assert.equal(route,'/api/navigation/roles/'+id(1),'No business-permission writer is permitted');
  if(body){writes++;assert.equal(method,'PUT');assert.equal(body.expectedRevision,revision);if(stale)throw Error('stale revision');selected=body.selectedMenuPids;}
  return {rolePid:id(1),revision,selectedMenuPids:wrongReadback&&writes?[]:selected,menus:[{pid:id(2),enabled:true,businessAllowed,directory:false}]};
 };
 return {request,calls,get writes(){return writes;}};
}
test('navigation uses exact public identities, preserves other selections, and verifies durable readback',async()=>{
 const f=fixture(),result=await installCaseRoleNavigation(f.request,bundle);
 assert.equal(result.businessPermissionsChanged,false);assert.deepEqual(result.roles[0].menuPids,[id(2),id(3)]);assert.equal(f.writes,1);
 assert(!f.calls.some(row=>row.route.includes('permissions')||row.route.includes('user-roles')));
});
test('ambiguous and unreadable targets refuse before writes; conflicts and wrong readback never become success',async()=>{
 for(const options of [{businessAllowed:false},{ambiguous:true}]){const f=fixture(options);await assert.rejects(()=>installCaseRoleNavigation(f.request,bundle));assert.equal(f.writes,0);}
 for(const options of [{stale:true},{wrongReadback:true}]){const f=fixture(options);await assert.rejects(()=>installCaseRoleNavigation(f.request,bundle));assert.equal(f.writes,1);}
});
