import { afterEach, describe, expect, it, vi } from 'vitest';
import { resolvePromptUploadKey, uploadCommandFile } from '../promptUpload';

describe('resolvePromptUploadKey', () => {
  it('defaults to source_file_id when promptUpload is boolean true', () => {
    expect(resolvePromptUploadKey(true)).toBe('source_file_id');
  });

  it('uses the explicit string key when provided', () => {
    expect(resolvePromptUploadKey('bom_lib_source_file_id')).toBe('bom_lib_source_file_id');
  });

  it('falls back to source_file_id for blank/invalid values', () => {
    expect(resolvePromptUploadKey('   ')).toBe('source_file_id');
    expect(resolvePromptUploadKey(undefined)).toBe('source_file_id');
    expect(resolvePromptUploadKey(null)).toBe('source_file_id');
  });
});

describe('uploadCommandFile', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('posts the file as multipart and returns data.fileId', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ code: '0', data: { fileId: 'file-123' } }),
    });
    vi.stubGlobal('fetch', fetchMock);

    const file = new File(['x'], 'lib.xlsx');
    const id = await uploadCommandFile(file, 'tok-1');

    expect(id).toBe('file-123');
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe('/api/file/upload');
    expect(init.method).toBe('POST');
    expect(init.credentials).toBe('include');
    expect(init.headers.Authorization).toBe('Bearer tok-1');
    expect(init.body).toBeInstanceOf(FormData);
    expect((init.body as FormData).get('file')).toBe(file);
  });

  it('omits the Authorization header when no token is given', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ data: { fileId: 'f2' } }),
    });
    vi.stubGlobal('fetch', fetchMock);

    await uploadCommandFile(new File(['x'], 'a.xlsx'));
    expect(fetchMock.mock.calls[0][1].headers.Authorization).toBeUndefined();
  });

  it('throws on a non-2xx response', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 413, json: async () => ({}) }));
    await expect(uploadCommandFile(new File(['x'], 'a.xlsx'))).rejects.toThrow('413');
  });

  it('throws when the response carries no fileId', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: async () => ({ data: {} }) }));
    await expect(uploadCommandFile(new File(['x'], 'a.xlsx'))).rejects.toThrow('no fileId');
  });
});
