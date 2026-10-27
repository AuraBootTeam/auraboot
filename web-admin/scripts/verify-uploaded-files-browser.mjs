/** Real Chromium/file/multipart fixture over the actual FormDialog; never a product-native gate. */
import fs from 'node:fs';
import path from 'node:path';
import http from 'node:http';
import assert from 'node:assert/strict';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { createRequire } from 'node:module';
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { chromium, expect } from '@playwright/test';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const require = createRequire(import.meta.url);
const { build } = await import(
  pathToFileURL(require.resolve('esbuild', { paths: [path.dirname(require.resolve('vite'))] })).href
);
const outIndex = process.argv.indexOf('--out');
assert.ok(outIndex > 0 && process.argv[outIndex + 1], 'Use --out /absolute/new-evidence-round');
const out = process.argv[outIndex + 1],
  mutant = process.argv.includes('--mutant');
assert.ok(path.isAbsolute(out) && !fs.existsSync(out));
fs.mkdirSync(out, { recursive: true });
const sha = (bytes) => createHash('sha256').update(bytes).digest('hex');
const A = '01J00000000000000000000001',
  B = '01J00000000000000000000002';
const fixtureImage = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jXioAAAAASUVORK5CYII=',
  'base64',
);
const receipt = {
  kind: 'hermetic-browser-multipart',
  sourceBase: execFileSync('git', ['rev-parse', 'HEAD'], { cwd: root, encoding: 'utf8' }).trim(),
  mutant,
  productRuntimeRequests: 0,
  productDatabaseWrites: 0,
  uploads: [],
  fixtureSubmissions: [],
  cases: [],
  status: 'running',
};
const record = () =>
  fs.writeFileSync(path.join(out, 'receipt.json'), JSON.stringify(receipt, null, 2) + '\n');
record();
let browser, server;
try {
  const bundle = await build({
    stdin: {
      contents: `
    import React from 'react'; import {createRoot} from 'react-dom/client';
    import FormDialog from './app/framework/meta/runtime/actions/FormDialog';
    createRoot(document.getElementById('root')).render(<FormDialog />);
    document.getElementById('open').onclick = () => window.dispatchEvent(new CustomEvent('dialog:form', {detail: {
      title:'通用附件输入测试', submitLabel:'保存记录', fields:[
        {field:'note',label:'处理说明',type:'textarea',required:true},
        {field:'filePids',label:'现场附件',type:'uploaded-files',uploadedFiles:{maxCount:10,accept:'image/*,.pdf'}}],
      defaults:{note:'受控浏览器夹具，不是产品记录'},
      onSubmit: async payload => {const response=await fetch('/fixture/record',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(payload)}); if(!response.ok) throw Error('fixture submit failed'); document.getElementById('saved').textContent='已收到一次表单提交';}
    }}));`,
      resolveDir: root,
      loader: 'tsx',
    },
    bundle: true,
    write: false,
    format: 'esm',
    platform: 'browser',
    metafile: true,
    alias: { '~': path.join(root, 'app') },
    define: { 'process.env.NODE_ENV': '"test"' },
    plugins: [
      {
        name: 'explicit-hermetic-contexts',
        setup(builder) {
          builder.onResolve(
            { filter: /contexts\/(I18nContext|TimezoneContext|AuthContext|ToastContext)$/ },
            (args) => ({ path: args.path, namespace: 'fixture-context' }),
          );
          builder.onLoad({ filter: /.*/, namespace: 'fixture-context' }, () => ({
            loader: 'js',
            contents: `export const useI18n=()=>({locale:'zh-CN',t:key=>key}); export const useTimezone=()=>({timezone:'UTC'}); export const useAuth=()=>({user:null}); export const useToastContext=()=>({showErrorToast:()=>{}});`,
          }));
          if (mutant)
            builder.onLoad({ filter: /StagedFileAttachmentField\.tsx$/ }, (args) => {
              const original = fs.readFileSync(args.path, 'utf8');
              const needle = 'pendingCount > 0 || failedCount > 0';
              assert.ok(original.includes(needle));
              return { contents: original.replace(needle, 'pendingCount > 0'), loader: 'tsx' };
            });
        },
      },
    ],
    logLevel: 'silent',
  });
  const js = bundle.outputFiles[0].contents;
  fs.writeFileSync(path.join(out, 'fixture.bundle.js'), js);
  receipt.bundleSha256 = sha(js);
  receipt.contextStubs = ['locale/timezone, no authenticated product account, toast'];
  receipt.sourceFilesSha256 = Object.fromEntries(
    Object.keys(bundle.metafile.inputs)
      .filter(
        (file) =>
          !file.startsWith('fixture-context:') &&
          !file.startsWith('<') &&
          !file.includes('node_modules'),
      )
      .map((file) => {
        const absolute = path.resolve(root, file);
        return [file, sha(fs.readFileSync(absolute))];
      }),
  );
  let uploadNumber = 0;
  server = http.createServer(async (request, response) => {
    try {
      if (request.method === 'GET' && request.url === '/') {
        response.setHeader('Content-Type', 'text/html; charset=utf-8');
        response.end(
          '<!doctype html><html lang="zh-CN"><meta charset="utf-8"><style>body{font:16px sans-serif}button{margin:8px;padding:8px}section,li{margin:8px} [role=dialog]{background:#fff;padding:24px;max-width:700px} .sr-only{position:absolute;width:1px;height:1px;overflow:hidden} [role=alert]{color:#a00}</style><button id="open">打开夹具表单</button><p id="saved"></p><div id="root"></div><script type="module" src="/fixture.js"></script></html>',
        );
        return;
      }
      if (request.method === 'GET' && request.url === '/fixture.js') {
        response.setHeader('Content-Type', 'text/javascript');
        response.end(js);
        return;
      }
      const parts = [];
      for await (const chunk of request) parts.push(chunk);
      const bytes = Buffer.concat(parts);
      response.setHeader('Content-Type', 'application/json');
      if (request.method === 'POST' && request.url === '/api/file/upload') {
        uploadNumber++;
        assert.match(request.headers['content-type'], /^multipart\/form-data; boundary=/);
        assert.ok(
          bytes.includes(fixtureImage),
          'Actual browser multipart must retain the fixture image bytes',
        );
        const header = bytes.subarray(0, bytes.indexOf('\r\n\r\n')).toString('utf8');
        const name = /filename="([^"]+)"/.exec(header)?.[1];
        assert.ok(name);
        const status = uploadNumber === 2 ? 503 : 200;
        receipt.uploads.push({
          number: uploadNumber,
          filename: name,
          status,
          contentType: request.headers['content-type'],
          multipartBytes: bytes.length,
          uploadedContentSha256: sha(fixtureImage),
          hasAuthorizationHeader: Boolean(request.headers.authorization),
        });
        response.statusCode = status;
        response.end(
          JSON.stringify(
            status === 200
              ? { code: '0', data: { fileId: uploadNumber === 1 ? A : B } }
              : { code: '503', message: 'controlled partial upload failure' },
          ),
        );
        return;
      }
      if (request.method === 'POST' && request.url === '/fixture/record') {
        const payload = JSON.parse(bytes);
        receipt.fixtureSubmissions.push(payload);
        assert.deepEqual(payload.filePids, [A, B]);
        assert.deepEqual(Object.keys(payload).sort(), ['filePids', 'note']);
        response.end(JSON.stringify({ code: '0' }));
        return;
      }
      response.statusCode = 404;
      response.end('{}');
    } catch (error) {
      receipt.serverFailure = String(error);
      response.statusCode = 500;
      response.end('{}');
    }
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const base = 'http://127.0.0.1:' + server.address().port;
  browser = await chromium.launch({ headless: true });
  const page = await browser.newPage({ viewport: { width: 1100, height: 1000 } });
  const pageErrors = [];
  page.on('pageerror', (error) => pageErrors.push(String(error)));
  page.on('request', (request) =>
    assert.ok(
      request.url().startsWith(base + '/'),
      'No product or external runtime request is permitted',
    ),
  );
  await page.goto(base);
  await page.getByRole('button', { name: '打开夹具表单' }).click();
  await page.getByTestId('attachment-file-input').setInputFiles([
    { name: '现场甲.png', mimeType: 'image/png', buffer: fixtureImage },
    { name: '现场乙.png', mimeType: 'image/png', buffer: fixtureImage },
  ]);
  await expect(page.getByText('上传失败，文件仍保留', { exact: true })).toBeVisible();
  await expect(page.getByText('现场甲.png', { exact: true })).toBeVisible();
  await expect(page.getByText('现场乙.png', { exact: true })).toBeVisible();
  await expect(page.getByTestId('form-dialog-submit')).toBeDisabled();
  assert.equal(receipt.uploads.length, 2);
  assert.equal(receipt.fixtureSubmissions.length, 0);
  await page.screenshot({ path: path.join(out, 'partial-failure.png'), fullPage: true });
  receipt.cases.push({ id: 'partial-failure-blocks-submit', passed: true });
  await page.getByRole('button', { name: '重试上传', exact: true }).click();
  await expect(page.getByTestId('form-dialog-submit')).toBeEnabled();
  await expect(page.getByText('上传失败，文件仍保留', { exact: true })).toHaveCount(0);
  assert.equal(receipt.uploads.length, 3);
  assert.equal(receipt.uploads[1].filename, receipt.uploads[2].filename);
  await page.screenshot({ path: path.join(out, 'retry-retains-both.png'), fullPage: true });
  await page.getByRole('button', { name: '保存记录', exact: true }).click();
  await expect(page.locator('#saved')).toHaveText('已收到一次表单提交');
  assert.equal(receipt.fixtureSubmissions.length, 1);
  assert.deepEqual(pageErrors, []);
  assert.ok(!receipt.serverFailure);
  receipt.cases.push({ id: 'explicit-retry-keeps-success-and-one-PID-array-submit', passed: true });
  receipt.status = 'passed';
} catch (error) {
  receipt.status = 'failed';
  receipt.failure = String(error);
  process.exitCode = 1;
} finally {
  await browser?.close();
  if (server) await new Promise((resolve) => server.close(resolve));
  record();
}
console.log(
  JSON.stringify({
    status: receipt.status,
    mutant,
    cases: receipt.cases,
    receipt: path.join(out, 'receipt.json'),
  }),
);
