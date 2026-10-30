/** Hermetic Chromium check of actual runtime-bound inspector, stage and action components. */
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
  kind: 'hermetic-runtime-context-browser',
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
import React from 'react'; import {createRoot} from 'react-dom/client';
import {SchemaRuntime} from './app/framework/meta/runtime/schema-runtime';
import {DataSourceManager} from './app/framework/meta/runtime/data-pipeline/DataSourceManager';
import {createExpressionContext} from './app/framework/meta/runtime/expression/context';
import {RecordInspectorBlockRenderer} from './app/framework/meta/rendering/blocks/RecordInspectorBlockRenderer';
import {StageRailBlockRenderer} from './app/framework/meta/rendering/blocks/StageRailBlockRenderer';
import {WorkbenchActionBarBlockRenderer} from './app/framework/meta/rendering/blocks/WorkbenchActionBarBlockRenderer';
const manager=new DataSourceManager(createExpressionContext({}));
manager.register('orderDetail',{type:'api',autoFetch:false,adaptor:'raw',method:'post',endpoint:'/api/meta/commands/execute/example:detail',params:{targetRecordPid:'\${record.pid}',payload:{}}});
const runtime=new SchemaRuntime({dataSourceManager:manager,scopeId:'fixture',schema:{id:'fixture',kind:'detail',title:'Fixture',blocks:[],layout:{type:'grid',cols:1}},globalState:{locale:'en',theme:'light',t:key=>key},initialContext:{record:{pid:'A',state:'assigned'}},skipDataSourceRegistration:true});
const context='\${data.orderDetail.data.workOrder}';
createRoot(document.getElementById('root')).render(<>
<RecordInspectorBlockRenderer runtime={runtime} block={{id:'inspector',blockType:'record-inspector',context,fields:[{field:'name',label:'Order name'}]}}/>
<StageRailBlockRenderer runtime={runtime} block={{id:'stages',blockType:'stage-rail',context,stageField:'state',stages:[{value:'new',label:'New'},{value:'assigned',label:'Assigned'}]}}/>
<WorkbenchActionBarBlockRenderer runtime={runtime} block={{id:'actions',blockType:'workbench-action-bar',actions:[{code:'repair',label:'Record repair',visibleWhen:"data.orderDetail.data.workOrder.state == 'assigned'"}]}}/>
</>);
document.getElementById('load').onclick=()=>manager.reload('orderDetail');
document.getElementById('switch').onclick=()=>{runtime.getStateManager().updateScope('fixture',{record:{pid:'B',state:'assigned'}});void manager.reload('orderDetail');};
document.getElementById('deny').onclick=()=>{runtime.getStateManager().updateState('fixture','denied',true);manager.getConfig('orderDetail').params={targetRecordPid:'B',payload:{denied:true}};void manager.reload('orderDetail');};

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
            builder.onLoad({ filter: /\/schema-runtime\.ts$/ }, (args) => ({
              loader: 'ts',
              contents: fs
                .readFileSync(args.path, 'utf8')
                .replace(
                  'return this.dataSourceManager.getRuntimeContext?.(ctx, this.stateManager, this.scopeId) ?? ctx;',
                  'return ctx;',
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
        '<!doctype html><meta charset="utf-8"><style>body{font:16px system-ui;padding:24px}button{padding:12px;margin:6px}table{margin:20px;border-collapse:collapse}td,th{padding:12px;border:1px solid #ddd}</style><h1>Runtime context: isolated component fixture</h1><button id="load">Load order A</button><button id="switch">Switch to B</button><button id="deny">Revoke fixture READ</button><div id="root"></div><script type="module" src="/fixture.js"></script>',
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
    if (req.url === '/api/meta/commands/execute/example:detail') {
      const input = JSON.parse(body);
      if (input.payload.denied) {
        res.statusCode = 403;
        res.end(JSON.stringify({ code: '403', message: 'Fixture READ revoked' }));
      } else
        res.end(
          JSON.stringify({
            code: '0',
            data: {
              phaseReached: 'completed',
              data: { workOrder: { name: 'Order ' + input.targetRecordPid, state: 'assigned' } },
            },
          }),
        );
    } else {
      res.statusCode = 404;
      res.end('{}');
    }
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  browser = await chromium.launch({ headless: true });
  const page = await browser.newPage({ viewport: { width: 1000, height: 700 } });
  receipt.browserErrors = [];
  page.on('pageerror', (error) => receipt.browserErrors.push(String(error)));
  await page.goto('http://127.0.0.1:' + server.address().port);
  await expect(page.getByTestId('record-inspector-empty')).toBeVisible();
  await page.getByRole('button', { name: 'Load order A', exact: true }).click();
  await expect(page.getByText('Order A', { exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Record repair', exact: true })).toBeVisible();
  await expect(page.getByText('Assigned', { exact: true })).toHaveCount(2);
  await page.screenshot({ path: path.join(out, 'loaded-source-context.png') });
  await page.getByRole('button', { name: 'Switch to B', exact: true }).click();
  await expect(page.getByText('Order B', { exact: true })).toBeVisible();
  await expect(page.getByText('Order A', { exact: true })).toHaveCount(0);
  await page.getByRole('button', { name: 'Revoke fixture READ', exact: true }).click();
  await expect(page.getByTestId('record-inspector-empty')).toBeVisible();
  await expect(page.getByText('Order B', { exact: true })).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Record repair', exact: true })).toHaveCount(0);
  await expect(page.getByText('Assigned', { exact: true })).toHaveCount(1);
  await page.screenshot({ path: path.join(out, 'revoked-source-context.png') });
  assert.equal(receipt.requests.length, 3);
  assert.deepEqual(
    receipt.requests.map((request) => request.payload.targetRecordPid),
    ['A', 'B', 'B'],
  );
  assert.deepEqual(receipt.browserErrors, []);
  receipt.status = 'passed';
  receipt.scope =
    'Actual SchemaRuntime/DMS/inspector/stage/action components with real fixture HTTP; auth/timezone/transport adapters and minimal CSS are explicit fixtures, not native product acceptance';
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
