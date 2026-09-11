// @vitest-environment node
import { Readable } from 'node:stream';
import { afterEach, describe, expect, it, vi } from 'vitest';

vi.mock('~/shared/services/session', () => ({ sessionStorage: { getSession: vi.fn() } }));
vi.mock('../../utils/logger', () => ({ default: { info: vi.fn(), warn: vi.fn(), error: vi.fn(), debug: vi.fn() } }));
import router from '../upload';

async function upload(bytes: number) {
  const boundary = 'upload-boundary';
  const req = Readable.from([
    Buffer.from(`--${boundary}\r\nContent-Disposition: form-data; name="file"; filename="limit.zip"\r\nContent-Type: application/zip\r\n\r\n`),
    Buffer.alloc(bytes),
    Buffer.from(`\r\n--${boundary}--\r\n`),
  ]) as any;
  req.headers = { authorization: 'Bearer fixture', 'content-type': `multipart/form-data; boundary=${boundary}` };
  req.setTimeout = vi.fn();
  const handler = router.stack.find((layer: any) => layer.route?.path === '/file/upload')!.route.stack[0].handle;
  return new Promise<{ status: number; body: any }>((resolve, reject) => {
    const res = {
      headersSent: false,
      statusCode: 200,
      status(code: number) { this.statusCode = code; return this; },
      json(body: any) { this.headersSent = true; resolve({ status: this.statusCode, body }); return this; },
    };
    Promise.resolve(handler(req, res, reject)).catch(reject);
  });
}

afterEach(() => vi.unstubAllGlobals());

describe('SmartUpload multipart boundary', () => {
  it('forwards an exact 100 MB file without truncating its bytes', async () => {
    const forward = vi.fn(async (_url, options) => {
      expect(options.body.get('file').size).toBe(100 * 1024 * 1024);
      return new Response(JSON.stringify({ code: '0', data: { fileId: 'stored' } }), { headers: { 'content-type': 'application/json' } });
    });
    vi.stubGlobal('fetch', forward);
    expect(await upload(100 * 1024 * 1024)).toEqual({ status: 200, body: { code: '0', data: { fileId: 'stored' } } });
    expect(forward).toHaveBeenCalledTimes(1);
  });

  it('rejects a file one byte above 100 MB without forwarding a truncated file', async () => {
    const forward = vi.fn();
    vi.stubGlobal('fetch', forward);
    const result = await upload(100 * 1024 * 1024 + 1);
    expect(result.status).toBe(413);
    expect(result.body.success).toBe(false);
    expect(forward).not.toHaveBeenCalled();
  });
});
