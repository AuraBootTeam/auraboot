/** Hermetic Chromium check of content-tab dispatch with explicit fixture leaf renderers. */
import fs from 'node:fs';
import path from 'node:path';
import http from 'node:http';
import assert from 'node:assert/strict';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { createRequire } from 'node:module';
import { createHash } from 'node:crypto';
import { chromium, expect } from '@playwright/test';
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const require = createRequire(import.meta.url);
const { build } = await import(
  pathToFileURL(require.resolve('esbuild', { paths: [path.dirname(require.resolve('vite'))] })).href
);
const out = process.argv[process.argv.indexOf('--out') + 1];
const mutant = process.argv.includes('--mutant');
assert.ok(out && path.isAbsolute(out) && !fs.existsSync(out), 'Use --out /absolute/new-round');
fs.mkdirSync(out, { recursive: true });
const receipt = {
  kind: 'hermetic-content-tabs-browser',
  mutant,
  requests: [],
  productRuntimeRequests: 0,
  productDatabaseWrites: 0,
  status: 'running',
};
let browser, server;
try {
  const bundle = await build({
    stdin: {
      resolveDir: root,
      loader: 'tsx',
      contents: `
import React from 'react'; import {createRoot} from 'react-dom/client'; import {MemoryRouter} from 'react-router';
import {ProfileProvider,BlockRenderer} from '@auraboot/runtime-kernel';
import {TabsBlockRenderer} from './app/framework/meta/rendering/blocks/TabsBlockRenderer';
const FixtureLeaf=({block,runtime})=><section data-testid={block.id}>{runtime.getDataSourceManager().getData(block.dataSource).map(row=><p key={row.pid}>{row.name}</p>)}</section>;
import {collectListMiscBlocks} from './app/framework/meta/rendering/pages/list/listSchemaPolicy';
import {DataSourceManager} from './app/framework/meta/runtime/data-pipeline/DataSourceManager';
import {createExpressionContext} from './app/framework/meta/runtime/expression/context';
const pid='01ARZ3NDEKTSV4RRFFQ69G5FAV';
const context=createExpressionContext({locale:'en',t:key=>key});
const manager=new DataSourceManager(context);
manager.register('cards',{type:'static',data:[{pid,name:'Fixture berth',row_version:2}],modelCode:'berth_model'});
manager.register('anchorages',{type:'static',data:[{pid,name:'Fixture anchorage',row_version:2}],modelCode:'anchorage_model'});
manager.register('maintenance',{type:'static',data:[{pid,name:'Fixture maintenance',row_version:2}],modelCode:'maintenance_model'});
await Promise.all(['cards','anchorages','maintenance'].map(id=>manager.fetch(id)));
const rowTable=(id,source)=>({id,blockType:'table',dataSource:source,columns:[{field:'name',label:'Name'}],rowActions:source==='maintenance'?[{code:'adjust',label:'Adjust maintenance',action:{type:'command',command:'maintenance_model:adjust',inputFields:[{field:'note',label:'Note',type:'text'}]}}]:[]});
const tabs={id:'content',blockType:'tabs',tabs:[
{key:'berths',label:'Berths',blocks:[{id:'cards',blockType:'card-grid',dataSource:'cards',cardGrid:{titleField:'name'}}]},
{key:'anchorages',label:'Anchorages',blocks:[rowTable('anchorage-table','anchorages')]},
{key:'maintenance',label:'Maintenance',blocks:[rowTable('maintenance-table','maintenance')]},
{key:'forbidden',label:'Forbidden',permissionCode:'private.read',blocks:[rowTable('private-table','maintenance')]}
]};
const schema={pageKey:'fixture',modelCode:'parent_model',extension:{skipListData:true},blocks:[tabs]};
const runtime={getContext:()=>context,getSchema:()=>schema,getDataSourceManager:()=>manager,getEvaluator:()=>({evaluateCondition:()=>true,evaluateTemplate:v=>v,evaluateObject:v=>v}),getStateManager:()=>({getContext:()=>context,updateState:()=>{}}),getScopeId:()=>'fixture',getFlowRunner:()=>null};

const profile={blockRenderers:new Map([['tabs',TabsBlockRenderer],['table',FixtureLeaf],['card-grid',FixtureLeaf]])};
createRoot(document.getElementById('root')).render(<MemoryRouter><ProfileProvider value={profile}>{collectListMiscBlocks(schema,true).map(block=><BlockRenderer key={block.id} block={block} runtime={runtime}/>)}</ProfileProvider></MemoryRouter>);
`,
    },
    bundle: true,
    write: false,
    platform: 'browser',
    format: 'esm',
    metafile: true,
    loader: { '.css': 'empty' },
    alias: { '~': path.join(root, 'app') },
    define: { 'process.env.NODE_ENV': '"test"' },
    plugins: [
      {
        name: 'explicit-fixture-boundaries',
        setup(builder) {
          builder.onResolve({ filter: /^@auraboot\/runtime-kernel$/ }, (args) => ({
            path: args.path,
            namespace: 'kernel',
          }));
          builder.onLoad({ filter: /.*/, namespace: 'kernel' }, () => ({
            loader: 'js',
            resolveDir: root,
            contents: `export {BlockRenderer} from './packages/runtime-kernel/rendering/BlockRenderer';export {ProfileProvider} from './packages/runtime-kernel/profiles/ProfileContext';`,
          }));
          builder.onResolve(
            { filter: /contexts\/(I18nContext|TimezoneContext|AuthContext|ToastContext)$/ },
            (args) => ({ path: args.path, namespace: 'contexts' }),
          );
          builder.onLoad({ filter: /.*/, namespace: 'contexts' }, () => ({
            loader: 'js',
            contents: `export const useI18n=()=>({locale:'en',t:key=>key});export const useTimezone=()=>({timezone:'UTC',formats:{}});export const useAuth=()=>({user:null,token:'fixture',hasPermission:code=>code!=='private.read'});export const useToastContext=()=>({showErrorToast:()=>{},showSuccessToast:()=>{},showWarningToast:()=>{},showInfoToast:()=>{}});`,
          }));
          builder.onResolve({ filter: /shared\/services\/http-client$/ }, (args) => ({
            path: args.path,
            namespace: 'transport',
          }));
          builder.onLoad({ filter: /.*/, namespace: 'transport' }, () => ({
            loader: 'js',
            contents: `export const post=(url,params)=>fetchResult(url,{method:"post",params});export async function fetchResult(url,options={}){const response=await fetch(url,{method:(options.method||'GET').toUpperCase(),headers:{'Content-Type':'application/json'},...(options.params?{body:JSON.stringify(options.params)}:{})});return {...await response.json(),httpStatus:response.status};}`,
          }));
          if (mutant)
            builder.onLoad({ filter: /\/listSchemaPolicy\.ts$/ }, (args) => ({
              loader: 'ts',
              contents: fs
                .readFileSync(args.path, 'utf8')
                .replace(
                  'if (isContentTabsBlock(block)) return true;',
                  'if (isContentTabsBlock(block)) return false;',
                ),
            }));
        },
      },
    ],
    logLevel: 'silent',
  });
  receipt.sourceSha256 = Object.fromEntries(
    Object.keys(bundle.metafile.inputs)
      .filter((p) => fs.existsSync(path.resolve(root, p)))
      .map((p) => [
        p,
        createHash('sha256')
          .update(fs.readFileSync(path.resolve(root, p)))
          .digest('hex'),
      ]),
  );
  const js = bundle.outputFiles[0].text;
  server = http.createServer(async (req, res) => {
    if (req.url === '/') {
      res.setHeader('Content-Type', 'text/html');
      res.end(
        '<!doctype html><meta charset="utf-8"><style>body{font:16px system-ui;padding:24px}button{padding:12px;margin:6px}table{margin:20px;border-collapse:collapse}td,th{padding:12px;border:1px solid #ddd}</style><h1>Content tabs: isolated renderer fixture</h1><div id="root"></div><script type="module" src="/fixture.js"></script>',
      );
      return;
    }
    if (req.url === '/fixture.js') {
      res.setHeader('Content-Type', 'text/javascript');
      res.end(js);
      return;
    }
    const chunks = [];
    for await (const chunk of req) chunks.push(chunk);
    const body = Buffer.concat(chunks).toString();
    receipt.requests.push({
      method: req.method,
      url: req.url,
      ...(body ? { payload: JSON.parse(body) } : {}),
    });
    res.setHeader('Content-Type', 'application/json');
    if (req.url.startsWith('/api/dynamic/maintenance_model/'))
      res.end(
        JSON.stringify({
          code: '0',
          data: { pid: '01ARZ3NDEKTSV4RRFFQ69G5FAV', row_version: 4, name: 'Fresh maintenance' },
        }),
      );
    else if (req.url === '/api/meta/commands/execute/maintenance_model:adjust') {
      res.statusCode = 409;
      res.end(JSON.stringify({ code: '41001', message: 'Version conflict' }));
    } else {
      res.statusCode = 404;
      res.end(JSON.stringify({ code: '404', message: 'Unknown fixture target' }));
    }
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  browser = await chromium.launch({ headless: true });
  const page = await browser.newPage({ viewport: { width: 1000, height: 700 } });
  receipt.browserErrors = [];
  page.on('pageerror', (error) => receipt.browserErrors.push(String(error)));
  await page.goto('http://127.0.0.1:' + server.address().port);
  await expect(page.getByText('Fixture berth', { exact: true })).toBeVisible();
  await expect(page.getByRole('tab')).toHaveCount(3);
  await page.screenshot({ path: path.join(out, 'berth-cards.png') });
  await page.getByRole('tab', { name: 'Anchorages', exact: true }).click();
  await expect(page.getByText('Fixture anchorage', { exact: true })).toBeVisible();
  await expect(page.getByText('Fixture berth', { exact: true })).toHaveCount(0);
  await page.getByRole('tab', { name: 'Maintenance', exact: true }).click();
  await expect(page.getByText('Fixture maintenance', { exact: true })).toBeVisible();
  await page.screenshot({ path: path.join(out, 'maintenance-table.png') });
  assert.equal(receipt.requests.length, 0);
  assert.deepEqual(receipt.browserErrors, []);
  receipt.status = 'passed';
  receipt.scope =
    'Actual list content classification, TabsBlockRenderer, kernel dispatch, permission filtering and DataSourceManager; explicit fixture leaf renderers, not actual Table/Card or native product';
} catch (error) {
  receipt.status = 'failed';
  receipt.error = String(error);
  receipt.buildErrors = error.errors?.map((e) => e.text);
  process.exitCode = 1;
} finally {
  await browser?.close();
  await new Promise((resolve) => (server ? server.close(resolve) : resolve()));
  fs.writeFileSync(path.join(out, 'receipt.json'), JSON.stringify(receipt, null, 2) + '\n');
}
