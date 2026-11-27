/** Explicit installer navigation recipe; never writes business permissions or changes platform defaults. */
const need=(value,message)=>{if(!value)throw Error(message);};
const pid=value=>typeof value==='string'&&/^[0-9A-Z]{26}$/.test(value);
export function caseNavigationPlan(bundle) {
 const read=kind=>bundle.plugins.flatMap(plugin=>{
  const location=JSON.parse(plugin.files['plugin.json'].content).resourceDirs?.[kind];
  if(!location)return [];
  need(typeof location==='string'&&plugin.files[location],`Case ${kind} must have an explicit file`);
  const rows=JSON.parse(plugin.files[location].content);need(Array.isArray(rows),`Case ${kind} must be an array`);return rows;
 });
 const roles=read('roles'),menus=read('menus').filter(row=>row.type===1&&row.visible!==false);
 need(new Set(roles.map(row=>row.code)).size===roles.length,'Case roles must be unique');
 return roles.map(role=>{
  need(typeof role.code==='string'&&!['tenant_admin','tenant_member','platform_admin'].includes(role.code)
   &&Array.isArray(role.permissions)&&role.permissions.every(code=>typeof code==='string'),'Only declared case roles are assignable');
  // This explicit recipe derives a bounded navigation selection from the package declaration.
  // Unprotected leaves and unrelated installed menus are never implicitly selected.
  return {roleCode:role.code,menuCodes:[...new Set(menus.filter(menu=>typeof menu.permissionCode==='string'
   &&menu.permissionCode.length>0&&role.permissions.includes(menu.permissionCode)).map(menu=>menu.code))].sort()};
 });
}
export async function installCaseRoleNavigation(request,bundle) {
 const plan=caseNavigationPlan(bundle),roles=await request('/api/roles/all'),tree=await request('/api/menu/all');
 need(Array.isArray(roles)&&Array.isArray(tree),'Explicit role/menu inventory required');
 const flatten=rows=>rows.flatMap(row=>[row,...flatten(row.children||[])]),menus=flatten(tree),prepared=[];
 // Resolve every identity and current revision before the first write.
 for(const row of plan){
  const found=roles.filter(role=>role.code===row.roleCode);need(found.length===1&&pid(found[0].pid),'Case role is missing or ambiguous');
  const rolePid=found[0].pid,route='/api/navigation/roles/'+rolePid,current=await request(route);
  need(current.rolePid===rolePid&&/^[a-f0-9]{64}$/.test(current.revision)&&Array.isArray(current.selectedMenuPids)&&Array.isArray(current.menus),'Invalid current navigation selection');
  const selected=new Set(current.selectedMenuPids);
  for(const code of row.menuCodes){
   const matches=menus.filter(menu=>menu.code===code&&menu.type===1);need(matches.length===1&&pid(matches[0].pid),'Case menu is missing or ambiguous');
   const choice=current.menus.find(menu=>menu.pid===matches[0].pid);
   need(choice&&choice.enabled&&choice.businessAllowed&&!choice.directory,'Case menu target is not readable by its declared role');
   selected.add(matches[0].pid);
  }
  prepared.push({roleCode:row.roleCode,rolePid,route,expectedRevision:current.revision,selectedMenuPids:[...selected].sort()});
 }
 const receipts=[];
 for(const row of prepared){
  await request(row.route,{expectedRevision:row.expectedRevision,selectedMenuPids:row.selectedMenuPids},'PUT');
  const actual=await request(row.route);
  need(actual.rolePid===row.rolePid&&Array.isArray(actual.selectedMenuPids)
   &&JSON.stringify([...actual.selectedMenuPids].sort())===JSON.stringify(row.selectedMenuPids),'Case navigation readback differs from the explicit plan');
  receipts.push({roleCode:row.roleCode,rolePid:row.rolePid,menuPids:row.selectedMenuPids});
 }
 return {format:'auraboot.case-navigation.v1',roles:receipts,businessPermissionsChanged:false};
}
