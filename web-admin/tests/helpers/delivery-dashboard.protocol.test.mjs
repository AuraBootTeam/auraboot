import test, {before, after} from 'node:test';
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {pathToFileURL} from 'node:url';
const root=process.env.AURA_CORE_PROJECT_ROOT;
assert.ok(root);
const require=createRequire(root+'/web-admin/package.json');
const {chromium}=require('@playwright/test');
const {dashboardRow}=await import(pathToFileURL(process.env.AURA_DASHBOARD_HELPER_FILE||root+'/web-admin/tests/helpers/delivery-dashboard.ts'));
let browser;
before(async()=>{browser=await chromium.launch({headless:true});});
after(async()=>{await browser?.close();});
// Browser protocol fixtures exercise the helper; they are not real-stack product evidence.
test('a visible empty-state cell is not a ready pagination boundary',async()=>{
 const page=await browser.newPage();
 try{
  await page.setContent('<section id="block"><table><tbody><tr><td>Loading data</td></tr></tbody></table></section>');
  await page.evaluate(()=>{setTimeout(()=>{document.querySelector('tbody').innerHTML='<tr><td>Owned acceptance</td><td>Approved</td></tr>';},200);});
  const row=await dashboardRow(page.locator('#block'),'Owned acceptance');
  assert.match(await row.innerText(),/Approved/);
 }finally{await page.close();}
});
test('real configured pagination is followed before claiming a target row',async()=>{
 const page=await browser.newPage();
 try{
  await page.setContent('<section id="block"><table><tbody><tr><td>Other acceptance</td></tr></tbody></table><button>下一页</button></section>');
  await page.locator('button').evaluate(button=>{button.addEventListener('click',()=>{document.querySelector('tbody').innerHTML='<tr><td>Owned acceptance</td><td>Approved</td></tr>';button.disabled=true;});});
  const row=await dashboardRow(page.locator('#block'),'Owned acceptance');assert.match(await row.innerText(),/Approved/);
  assert.equal(await page.locator('button').isDisabled(),true);
 }finally{await page.close();}
});
test('a genuinely missing single-page row still fails',async()=>{
 const page=await browser.newPage();try{
  await page.setContent('<section id="block"><table><tbody><tr><td>Other acceptance</td></tr></tbody></table></section>');
  await assert.rejects(()=>dashboardRow(page.locator('#block'),'Owned acceptance'));
 }finally{await page.close();}
});
test('duplicate owned rows cannot be mistaken for one business result',async()=>{
 const page=await browser.newPage();try{
  await page.setContent('<section id="block"><table><tbody><tr><td>Owned acceptance</td></tr><tr><td>Owned acceptance</td></tr></tbody></table></section>');
  await assert.rejects(()=>dashboardRow(page.locator('#block'),'Owned acceptance'));
 }finally{await page.close();}
});
