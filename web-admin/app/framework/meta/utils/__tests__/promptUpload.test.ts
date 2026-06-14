import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  resolvePromptUploadAccept,
  resolvePromptUploadFilenameKey,
  resolvePromptUploadKey,
  uploadCommandFile,
} from '../promptUpload';

describe('resolvePromptUploadKey', () => {
  it('defaults to source_file_id when promptUpload is boolean true', () => {
    expect(resolvePromptUploadKey(true)).toBe('source_file_id');
  });

  it('uses the explicit string key when provided', () => {
    expect(resolvePromptUploadKey('bom_lib_source_file_id')).toBe('bom_lib_source_file_id');
  });

  it('uses the object key when promptUpload is configured with metadata', () => {
    expect(resolvePromptUploadKey({ key: 'corrected_bom_file_id', accept: '.xlsx,.xls,.csv' })).toBe(
      'corrected_bom_file_id',
    );
  });

  it('falls back to source_file_id for blank/invalid values', () => {
    expect(resolvePromptUploadKey('   ')).toBe('source_file_id');
    expect(resolvePromptUploadKey(undefined)).toBe('source_file_id');
    expect(resolvePromptUploadKey(null)).toBe('source_file_id');
  });
});

describe('resolvePromptUploadFilenameKey', () => {
  it('derives a filename key from the default promptUpload file id key', () => {
    expect(resolvePromptUploadFilenameKey(true)).toBe('source_filename');
  });

  it('derives a filename key from explicit snake_case file id keys', () => {
    expect(resolvePromptUploadFilenameKey('corrected_bom_file_id')).toBe('corrected_bom_filename');
    expect(resolvePromptUploadFilenameKey('process_rule_file_id')).toBe('process_rule_filename');
  });

  it('falls back to appending _filename for non-standard keys', () => {
    expect(resolvePromptUploadFilenameKey('attachment')).toBe('attachment_filename');
  });
});

describe('resolvePromptUploadAccept', () => {
  it('uses object accept metadata when configured', () => {
    expect(resolvePromptUploadAccept({ key: 'gerber_file_id', accept: '.zip,.rar,.gbr,.drl' })).toBe(
      '.zip,.rar,.gbr,.drl',
    );
  });

  it('falls back to spreadsheet uploads for legacy promptUpload values', () => {
    expect(resolvePromptUploadAccept('corrected_bom_file_id')).toBe('.xlsx,.xls,.csv');
    expect(resolvePromptUploadAccept(true)).toBe('.xlsx,.xls,.csv');
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
