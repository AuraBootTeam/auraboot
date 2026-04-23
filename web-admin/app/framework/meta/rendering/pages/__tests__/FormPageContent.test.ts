import { describe, expect, it } from 'vitest';
import {
  normalizeCommandPayloadValue,
  normalizePayloadValue,
  parseValidationSummaryMessages,
} from '../FormPageContent';
import { buildRequiredFieldMessage } from '~/framework/meta/utils/validationMessages';

describe('normalizePayloadValue', () => {
  it('serializes file fields to persisted JSON and drops non-done upload entries', () => {
    const value = normalizePayloadValue(
      [
        {
          uid: 'done-1',
          name: 'ok.txt',
          status: 'done',
          size: 12,
          type: 'text/plain',
          url: '/api/file/download/f1',
          response: { fileId: 'f1' },
        },
        {
          uid: 'error-1',
          name: 'bad.txt',
          status: 'error',
          size: 8,
          type: 'text/plain',
        },
      ],
      'file',
    );

    expect(value).toBe(
      JSON.stringify([
        {
          name: 'ok.txt',
          url: '/api/file/download/f1',
          size: 12,
          type: 'text/plain',
          fileId: 'f1',
        },
      ]),
    );
  });

  it('returns null for file fields when no successfully uploaded files remain', () => {
    const value = normalizePayloadValue(
      [
        {
          uid: 'error-1',
          name: 'bad.txt',
          status: 'error',
        },
      ],
      'file',
    );

    expect(value).toBeNull();
  });

  it('keeps stringified memberpicker values as strings for non-json fields', () => {
    const value = normalizePayloadValue('["u1","u2"]', 'reference');
    expect(value).toBe('["u1","u2"]');
  });

  it('still parses JSON strings for json fields', () => {
    const value = normalizePayloadValue('{"enabled":true}', 'json');
    expect(value).toEqual({ enabled: true });
  });

  it('serializes parsed json objects back to strings for command payloads', () => {
    const value = normalizeCommandPayloadValue(
      [
        {
          name: 'audit-attachment.pdf',
          url: '/files/audit-attachment.pdf',
        },
      ],
      'json',
    );
    expect(value).toBe('[{"name":"audit-attachment.pdf","url":"/files/audit-attachment.pdf"}]');
  });

  it('keeps stringified json values stable for command payloads', () => {
    const value = normalizeCommandPayloadValue('{"enabled":true}', 'json');
    expect(value).toBe('{"enabled":true}');
  });

  it('builds zh-CN required messages based on field type', () => {
    expect(
      buildRequiredFieldMessage('申请人', {
        dataType: 'reference',
        component: 'memberpicker',
        locale: 'zh-CN',
      }),
    ).toBe(
      '请选择申请人',
    );
    expect(
      buildRequiredFieldMessage('附件', {
        dataType: 'file',
        component: 'upload',
        locale: 'zh-CN',
      }),
    ).toBe('请上传附件');
    expect(
      buildRequiredFieldMessage('请假原因', {
        dataType: 'text',
        component: 'textarea',
        locale: 'zh-CN',
      }),
    ).toBe(
      '请填写请假原因',
    );
    expect(
      buildRequiredFieldMessage('Applicant', {
        dataType: 'reference',
        component: 'memberpicker',
        locale: 'en-US',
        t: (key) => (key === 'common.validation.required' ? 'is required' : key),
      }),
    ).toBe('Applicant is required');
  });

  it('splits backend validation summaries into distinct messages', () => {
    expect(
      parseValidationSummaryMessages('结束日期不能早于开始日期; 请完善开始/结束日期与时段，系统才能计算请假天数'),
    ).toEqual(['结束日期不能早于开始日期', '请完善开始/结束日期与时段，系统才能计算请假天数']);
  });
});
