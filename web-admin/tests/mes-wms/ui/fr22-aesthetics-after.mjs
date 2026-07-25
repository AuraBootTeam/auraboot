import { chromium } from '@playwright/test';
const BASE='http://127.0.0.1:5163';
const OUT=new URL('.',import.meta.url).pathname;
const b=await chromium.launch();
const p=await(await b.newContext({ignoreHTTPSErrors:true,locale:'zh-CN',viewport:{width:1440,height:900}})).newPage();
const es='input[type="email"], input[name="email"], input[autocomplete="username"]';
let s=false;
for(let a=0;a<5&&!s;a++){await p.goto(`${BASE}/`,{waitUntil:'load',timeout:25000}).catch(()=>{});await p.waitForTimeout(2000);s=await p.waitForSelector(es,{state:'visible',timeout:8000}).then(()=>1).catch(()=>0);}
await p.fill(es,'admin@auraboot.com');await p.fill('input[type="password"]','Test2026x');
await Promise.all([p.waitForTimeout(2500),p.click('button[type="submit"]')]);await p.waitForTimeout(1500);
await p.goto(`${BASE}/p/c/mfg_shift_handover_workbench`,{waitUntil:'load',timeout:25000}).catch(()=>{});
await p.waitForTimeout(3500);
await p.screenshot({path:`${OUT}/fr22-aesthetics-after.png`,fullPage:true});
// dump the handover-time cell text + filter labels to confirm formatting
const bodyText=(await p.locator('main').first().innerText().catch(()=>''))||'';
const rawIso=/\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}/.test(bodyText);
console.log('raw ISO timestamp still visible in page:', rawIso);
console.log('has 工位 filter label:', /工位/.test(bodyText));
console.log('has 交接状态 filter label:', /交接状态/.test(bodyText));
// sample the time column text
const m=bodyText.match(/交接时间[\s\S]{0,400}/);
console.log('near 交接时间:', (m?m[0]:'').replace(/\s+/g,' ').slice(0,200));
await b.close();
