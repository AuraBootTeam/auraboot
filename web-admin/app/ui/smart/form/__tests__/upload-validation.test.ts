/**
 * Pure file-validation logic for Upload — returns a structured rejection
 * (i18n code + params) instead of a hardcoded English string, so the component
 * can render localized inline errors (standard §4: inline validation, not toast).
 */
import { describe, it, expect } from 'vitest';
import { validateUploadFile } from '~/ui/smart/form/Upload';

const file = (name: string, size: number, type = ''): File => ({ name, size, type }) as File;

describe('validateUploadFile', () => {
  it('accepts a file within size + type constraints', () => {
    expect(
      validateUploadFile(file('a.png', 1024, 'image/png'), { maxSize: 10, accept: '' }),
    ).toBeNull();
  });

  it('rejects oversize with a maxSize code + the limit param', () => {
    const r = validateUploadFile(file('big.zip', 20 * 1024 * 1024), { maxSize: 10, accept: '' });
    expect(r).toEqual({ code: 'maxSize', params: { max: 10 } });
  });

  it('rejects a disallowed extension with a fileType code + accept param', () => {
    const r = validateUploadFile(file('a.exe', 100, 'application/octet-stream'), {
      maxSize: 10,
      accept: '.png,.jpg',
    });
    expect(r).toEqual({ code: 'fileType', params: { accept: '.png,.jpg' } });
  });

  it('accepts an allowed extension', () => {
    expect(
      validateUploadFile(file('a.PNG', 100, ''), { maxSize: 10, accept: '.png,.jpg' }),
    ).toBeNull();
  });

  it('accepts a wildcard mime (image/*)', () => {
    expect(
      validateUploadFile(file('a.png', 100, 'image/png'), { maxSize: 10, accept: 'image/*' }),
    ).toBeNull();
  });

  it('no constraints → always accepts', () => {
    expect(validateUploadFile(file('x', 999999999), { maxSize: 0, accept: '' })).toBeNull();
  });
});
