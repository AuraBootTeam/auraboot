import { describe, expect, it } from 'vitest';
import { classifyBackendError, toolErrorFromBackend } from '../../src/mcp/errors.js';

const apiResp = (status: number, message: string) => ({
  ok: false,
  status,
  data: null,
  message,
});

describe('classifyBackendError', () => {
  it('401 → session_expired', () => {
    expect(classifyBackendError(apiResp(401, 'expired')).kind).toBe('session_expired');
  });

  it('403 → permission_denied + suggestion mentions admin', () => {
    const c = classifyBackendError(apiResp(403, 'no role'));
    expect(c.kind).toBe('permission_denied');
    expect(c.suggestion).toMatch(/admin/i);
  });

  it('404 → not_found + suggestion to use query_*', () => {
    const c = classifyBackendError(apiResp(404, 'missing'));
    expect(c.kind).toBe('not_found');
    expect(c.suggestion).toMatch(/query_/);
  });

  it('409 → conflict regardless of message', () => {
    expect(classifyBackendError(apiResp(409, 'whatever')).kind).toBe('conflict');
  });

  it('200 + Chinese 已存在 → conflict (envelope-style failure)', () => {
    expect(classifyBackendError(apiResp(200, '模型编码已存在: crm_lead')).kind).toBe('conflict');
  });

  it('200 + English "already exists" → conflict', () => {
    expect(classifyBackendError(apiResp(200, 'pageKey already exists')).kind).toBe('conflict');
  });

  it('200 + "duplicate" → conflict', () => {
    expect(classifyBackendError(apiResp(200, 'Duplicate entry')).kind).toBe('conflict');
  });

  it('422 → validation', () => {
    expect(classifyBackendError(apiResp(422, 'bad field')).kind).toBe('validation');
  });

  it('500 → server_error', () => {
    expect(classifyBackendError(apiResp(500, 'crash')).kind).toBe('server_error');
  });

  it('502 → server_error', () => {
    expect(classifyBackendError(apiResp(502, 'gateway')).kind).toBe('server_error');
  });

  it('uncategorised 4xx → backend_error', () => {
    expect(classifyBackendError(apiResp(418, "I'm a teapot")).kind).toBe('backend_error');
  });

  it('falls back to "Status N" when message missing', () => {
    const c = classifyBackendError({ ok: false, status: 422, data: null });
    expect(c.message).toBe('Status 422');
  });
});

describe('toolErrorFromBackend', () => {
  it('returns isError=true with JSON envelope containing kind / status / error', () => {
    const result = toolErrorFromBackend(apiResp(409, 'pageKey 已存在'));
    expect(result.isError).toBe(true);
    const body = JSON.parse(result.content[0].text);
    expect(body.kind).toBe('conflict');
    expect(body.status).toBe(409);
    expect(body.error).toBe('pageKey 已存在');
    expect(body.suggestion).toMatch(/Rename/i);
  });

  it('omits suggestion when classifier produced none', () => {
    const result = toolErrorFromBackend(apiResp(418, 'teapot'));
    const body = JSON.parse(result.content[0].text);
    expect(body.suggestion).toBeUndefined();
    expect(body.kind).toBe('backend_error');
  });

  it('includes step field when caller passes one (multi-step tool)', () => {
    const result = toolErrorFromBackend(apiResp(409, 'dup'), { step: 'create_command' });
    const body = JSON.parse(result.content[0].text);
    expect(body.step).toBe('create_command');
    expect(body.kind).toBe('conflict');
  });
});
