import { test, expect, type Page, type TestInfo, type APIResponse, type Response } from '@playwright/test';
import fs from 'node:fs/promises';
import { randomUUID } from 'node:crypto';
import { provisionInternalApiUser } from '../../helpers/internal-api-owned-user';
import { createResponseShapeValidator } from '../../../../scripts/internal-api/response-shape.mjs';
const document = JSON.parse(await fs.readFile(new URL('../../../../contracts/internal-web-admin/openapi.json',import.meta.url),'utf8'));
const catalog = JSON.parse(await fs.readFile(new URL('../../../../scripts/internal-api/catalog.json',import.meta.url),'utf8'));
const validate = createResponseShapeValidator(document);
async function capture(id: string,page: Page,info: TestInfo) {
  const scene=catalog.scenarios.find((s:{id:string})=>s.id===id);
  expect(scene?.test).toBe(info.title);
  const path=info.outputPath(scene.screenshot);
  await page.screenshot({path,fullPage:true,animations:'disabled'});
  await info.attach(id,{path,contentType:'image/png'});
  info.annotations.push({type:'internal-api-scenario',description:id});
}

test('generated role memberships preserve public PID selection assignment reload and removal',async({browser,baseURL},info)=>{
  test.setTimeout(150_000);
  if(!baseURL) throw new Error('A managed runtime is required');
  const owner=await provisionInternalApiUser(browser,baseURL);
  let target: Awaited<ReturnType<typeof provisionInternalApiUser>> | undefined;
  const anonymous=await browser.newContext({baseURL,storageState:{cookies:[],origins:[]}});
  const code=`ic_role_${randomUUID().replaceAll('-','').slice(0,16)}`;
  const rows: object[]=[],denials: object[]=[],cleanupErrors:unknown[]=[],pageErrors:string[]=[];
  const writes: object[]=[],membershipProofs: object[]=[];
  const {page,context,origin}=owner;
  const headers={Origin:origin};
  let rolePid:string|undefined,journeyError:unknown;
  page.on('pageerror',e=>pageErrors.push(e.message));
  async function checked(response: APIResponse|Response,method:string,path:string) {
    const payload=await response.json();
    const shape=validate({method,path,status:response.status(),contentType:response.headers()['content-type'],payload});
    rows.push({method,path,status:response.status(),shape});
    expect(response.status()).toBe(200);expect(payload.code).toBe('0');expect(shape.errors).toEqual([]);
    return payload.data;
  }
  const responseFor=(suffix:string,method='GET')=>page.waitForResponse(r=>new URL(r.url()).pathname===`/api/roles/${rolePid}/members${suffix}`&&r.request().method()===method);
  async function selectRole() {
    await expect(page.getByTestId('permission-page')).toBeVisible();
    await page.getByTestId('role-search-input').fill(code);
    await page.getByTestId(`role-item-${code}`).click();
    const loaded=responseFor('');
    await page.getByTestId('permission-right-tab-members').click();
    const result=await checked(await loaded,'get','/api/roles/{rolePid}/members');
    expect(result.page).toBe(1);expect(result.pageSize).toBe(20);expect(result).not.toHaveProperty('pageNum');
    await expect(page.getByText('Manage membership and access periods for this role.',{exact:true})).toBeVisible();
    await expect(page.getByTestId('permission-right-tab-audit')).toHaveText(/^Audit$/);
    return result;
  }
  try {
    target=await provisionInternalApiUser(browser,baseURL,null);
    const created=await context.request.post('/api/roles',{headers,data:{code,name:`Contract role ${code.slice(-8)}`,type:'custom',description:'Owned internal API contract fixture'}});
    const payload=await created.json();if(typeof payload.data?.pid==='string')rolePid=payload.data.pid;
    expect(created.status()).toBe(200);expect(payload.code).toBe('0');expect(rolePid).toBeTruthy();
    const menu=page.locator('nav').getByRole('link',{name:/^Roles$|^角色$/});
    if(!(await menu.isVisible())) await page.locator('nav').getByRole('button',{name:/Organization|组织管理/i}).click();
    await menu.click();
    expect((await selectRole()).records).toEqual([]);
    await expect(page.getByTestId('role-member-empty')).toBeVisible();
    await capture('ROLEMEM-01',page,info);
    await page.getByTestId('role-member-add-btn').click();
    await page.getByTestId('add-member-tab-list').click();
    const candidateRead=page.waitForResponse(r=>new URL(r.url()).pathname===`/api/roles/${rolePid}/members/candidates`
      && r.request().method()==='GET' && new URL(r.url()).searchParams.get('keyword')===target!.username);
    await page.getByTestId('add-member-list-search').fill(target.username);
    const candidates=await checked(await candidateRead,'get','/api/roles/{rolePid}/members/candidates');
    const candidate=page.getByTestId(`candidate-row-${target.memberPid}`);
    await expect(candidate).toBeVisible();
    expect(candidates).toHaveLength(1);expect(candidates[0]).toMatchObject({memberPid:target.memberPid,email:null,departmentName:null,positionName:null,assignedAt:null,effectiveDate:null,expiryDate:null});
    expect(Object.keys(candidates[0]).sort()).toEqual(['assignedAt','departmentName','effectiveDate','email','expiryDate','memberId','memberPid','positionName','userName']);
    expect(Number.isSafeInteger(candidates[0].memberId)).toBe(false);
    membershipProofs.push({phase:'candidate',memberPids:candidates.map((m:{memberPid:string})=>m.memberPid),assignedAt:candidates[0].assignedAt});
    expect(await candidate.innerText()).not.toContain(target.memberPid);
    await candidate.locator('input[type="checkbox"]').check();
    await capture('ROLEMEM-02',page,info);
    const assigned=responseFor('/assign','POST');
    await page.getByTestId('add-member-confirm').click();
    const assignedResponse=await assigned;
    const assignBody=assignedResponse.request().postDataJSON();
    writes.push({method:'post',path:'/api/roles/{rolePid}/members/assign',body:assignBody});
    expect(assignBody).toEqual({memberPids:[target.memberPid]});
    await checked(assignedResponse,'post','/api/roles/{rolePid}/members/assign');
    await expect(page.getByTestId('add-member-dialog')).not.toBeVisible();
    await expect(page.getByTestId(`role-member-row-${target.memberPid}`)).toBeVisible();
    await page.reload();
    const persisted=await selectRole();expect(persisted.total).toBe(1);expect(persisted.records).toHaveLength(1);
    expect(persisted.records[0]).toMatchObject({memberPid:target.memberPid,email:null,effectiveDate:null,expiryDate:null});
    expect(typeof persisted.records[0].assignedAt).toBe('string');
    membershipProofs.push({phase:'assigned-reload',memberPids:persisted.records.map((m:{memberPid:string})=>m.memberPid),total:persisted.total});
    await expect(page.getByTestId(`role-member-row-${target.memberPid}`)).toContainText(target.username);
    await capture('ROLEMEM-03',page,info);
    await page.getByTestId(`role-member-remove-${target.memberPid}`).click();
    const removed=responseFor('/remove','POST');
    await page.getByTestId('confirm-ok').click();
    const removedResponse=await removed;const removeBody=removedResponse.request().postDataJSON();
    writes.push({method:'post',path:'/api/roles/{rolePid}/members/remove',body:removeBody});
    expect(removeBody).toEqual([target.memberPid]);
    await checked(removedResponse,'post','/api/roles/{rolePid}/members/remove');
    await expect(page.getByTestId('role-member-empty')).toBeVisible();
    await page.reload();const removedPage=await selectRole();expect(removedPage.records).toEqual([]);
    membershipProofs.push({phase:'removed-reload',memberPids:removedPage.records.map((m:{memberPid:string})=>m.memberPid),total:removedPage.total});
    await capture('ROLEMEM-04',page,info);
    for(const [label,client,status] of [['anonymous',anonymous,401],['no-role',target.context,403]] as const){
      for(const [method,suffix,body] of [['get','',undefined],['get','/candidates',undefined],['post','/assign',{memberPids:[target.memberPid]}],['post','/remove',[target.memberPid]]] as const){
        const response=await client.request.fetch(`/api/roles/${rolePid}/members${suffix}`,{method,data:body,headers});
        denials.push({actor:label,method,suffix,status:response.status()});expect(response.status()).toBe(status);
      }
    }
    const afterDenials=await checked(await context.request.get(`/api/roles/${rolePid}/members`),'get','/api/roles/{rolePid}/members');
    expect(afterDenials.records).toEqual([]);
    membershipProofs.push({phase:'after-denials',memberPids:afterDenials.records.map((m:{memberPid:string})=>m.memberPid),total:afterDenials.total});
  }catch(error){journeyError=error;}
  finally{
    try{
      const all=await context.request.get('/api/roles/all');expect(all.status()).toBe(200);
      const payload=await all.json();expect(payload.code).toBe('0');
      const owned=payload.data.filter((r:{code:string})=>r.code===code);expect(owned.length).toBeLessThanOrEqual(1);
      if(owned.length){if(rolePid)expect(owned[0].pid).toBe(rolePid);rolePid=owned[0].pid;
        if(target){const removed=await context.request.post(`/api/roles/${rolePid}/members/remove`,{headers,data:[target.memberPid]});expect(removed.status()).toBe(200);expect((await removed.json()).code).toBe('0');}
        const deleted=await context.request.delete(`/api/roles/${rolePid}`,{headers});expect(deleted.status()).toBe(200);expect((await deleted.json()).code).toBe('0');
        const after=await context.request.get('/api/roles/all');expect(after.status()).toBe(200);expect((await after.json()).data.some((r:{code:string;pid:string})=>r.code===code||r.pid===rolePid)).toBe(false);
      }
    }catch(error){cleanupErrors.push(error);}
    for(const dispose of [()=>anonymous.close(),()=>target?.dispose(),()=>owner.dispose()])try{await dispose();}catch(error){cleanupErrors.push(error);}
    await info.attach('role-member-browser-observations',{body:JSON.stringify({rows,denials,writes,membershipProofs,expectedMemberPid:target?.memberPid,pageErrors,sessionCleanup:[owner.sessionCleanup,...(target?[target.sessionCleanup]:[])],
      claim:'four member operations through the actual role menu, permanent assignment persistence and owned removal; cross-tenant and time-window UI remain pending',
      journeyError:journeyError?String(journeyError):null,cleanupErrors:cleanupErrors.map(String)}),contentType:'application/json'});
  }
  if(journeyError||cleanupErrors.length)throw new AggregateError([...(journeyError?[journeyError]:[]),...cleanupErrors],'Role member journey or cleanup failed');
});
