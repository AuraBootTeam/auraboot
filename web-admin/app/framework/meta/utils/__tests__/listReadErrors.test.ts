import { describe, expect, it } from 'vitest';
import { resolveListReadErrorMessage } from '../listReadErrors';
import { resolveErrorLocale, rootT } from '~/error/root-error-view';

const translate = (key: string) => key === 'common.loadDataError' ? 'Public load fallback' : key;

describe('resolveListReadErrorMessage', () => {
  it.each(['zh-CN', 'en-US', 'ja-JP', 'ko-KR'])('uses typed forbidden feedback in %s instead of server diagnostics', locale => {
    const result = resolveListReadErrorMessage({code: '403', httpStatus: 403, desc: 'Access forbidden',
      context: {detail: 'private tenant/model permission'}}, locale, translate);
    expect(result).toBe(rootT('forbidden', resolveErrorLocale(locale)));
    expect(result).not.toMatch(/Access forbidden|private|tenant\/model/);
  });
  it('prioritizes actual HTTP403 over a business envelope code', () => {
    expect(resolveListReadErrorMessage({code: '35000', httpStatus: 403,
      desc: 'Bad parameter', context: {exception: 'BusinessException', detail: 'private policy'}}, 'zh-CN', translate))
      .toBe(rootT('forbidden', 'zh-CN'));
  });
  it.each([{code: '403'}, {status: 403}, {statusCode: 403}, {response: {status: 403}}])('handles structured exception or legacy status %j', status => {
    const error = Object.assign(new Error('HTTP Error: private forbidden text'), status);
    expect(resolveListReadErrorMessage(error, 'zh-CN', translate)).toBe(rootT('forbidden', 'zh-CN'));
  });
  it('does not turn a real400 into forbidden because its envelope code says403', () => {
    expect(resolveListReadErrorMessage({code: '403', httpStatus: 400, desc: 'Public validation feedback'}, 'zh-CN', translate))
      .toBe('Public validation feedback');
  });
  it('handles a nonJSON403 transport fallback without exposing HTTP diagnostics', () => {
    expect(resolveListReadErrorMessage({code: '403', httpStatus: 403, desc: 'HTTP Error: 403 Forbidden', context: null}, 'zh-CN', translate))
      .toBe(rootT('forbidden', 'zh-CN'));
  });
  it('does not infer forbidden from English text without a typed status', () => {
    expect(resolveListReadErrorMessage(new Error('Access forbidden'), 'en-US', translate)).toBe('Access forbidden');
  });
  it('preserves the existing localized network recovery copy', () => {
    expect(resolveListReadErrorMessage({code: 'network_error', desc: 'Network error: Failed to fetch'}, 'zh-CN', translate))
      .toBe('网络连接失败，请检查连接后重试。');
  });
  it('keeps public business validation detail from an explicit400 envelope', () => {
    expect(resolveListReadErrorMessage({code: '35000', httpStatus: 400, desc: 'Bad parameter',
      context: {exception: 'BusinessException', detail: '请先选择采购申请明细。'}}, 'zh-CN', translate))
      .toBe('请先选择采购申请明细。');
  });
  it('does not promote diagnostic context for system errors', () => {
    expect(resolveListReadErrorMessage({code: 'SYSTEM_ERROR', httpStatus: 500, desc: 'private SQL connection detail',
      context: {exception: 'SQLException', detail: 'private database configuration'}}, 'zh-CN', translate))
      .not.toContain('private');
  });
});
