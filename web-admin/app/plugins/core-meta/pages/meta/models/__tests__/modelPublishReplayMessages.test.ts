import { describe, expect, it } from 'vitest';
import { getReplayResultMessage, getReplayStatusLabel } from '../modelPublishReplayMessages';
import type { ModelPublishReplayResult } from '~/shared/services/modelService';

function decision(patch: Partial<ModelPublishReplayResult> = {}): ModelPublishReplayResult {
  return { step: { consumerType: 'DECISION_VERSION' }, status: 'EXECUTED', executed: true, message: 'Decision replay executed with status NOT_MATCHED.', matched: false, outputs: {}, errors: [], ...patch };
}
describe('model publish replay presentation preserves backend outcomes', () => {
  for (const locale of ['en-US', 'zh-CN']) {
    const en = locale === 'en-US';
    it(`${locale} real backend NOT_MATCHED is never rendered as matched`, () => {
      expect(getReplayResultMessage(decision(), locale)).toBe(en ? 'Decision review: not matched.' : '\u51b3\u7b56\u7248\u672c\u590d\u6838\u7ed3\u679c：\u672a\u547d\u4e2d。');
    });
    it(`${locale} structured false takes precedence over a stale MATCHED message`, () => {
      expect(getReplayResultMessage(decision({ message: 'Decision replay executed with status MATCHED.' }), locale)).toBe(en ? 'Decision review: not matched.' : '\u51b3\u7b56\u7248\u672c\u590d\u6838\u7ed3\u679c：\u672a\u547d\u4e2d。');
    });
    it(`${locale} structured true takes precedence over a stale NOT_MATCHED message`, () => {
      expect(getReplayResultMessage(decision({ matched: true }), locale)).toBe(en ? 'Decision review: matched.' : '\u51b3\u7b56\u7248\u672c\u590d\u6838\u7ed3\u679c：\u547d\u4e2d。');
    });
    it(`${locale} legacy missing matched uses the complete NOT_MATCHED token`, () => {
      expect(getReplayResultMessage(decision({ matched: undefined }), locale)).toBe(en ? 'Decision review: not matched.' : '\u51b3\u7b56\u7248\u672c\u590d\u6838\u7ed3\u679c：\u672a\u547d\u4e2d。');
    });
    it(`${locale} legacy MATCHED uses the complete token`, () => {
      expect(getReplayResultMessage(decision({ matched: undefined, message: 'Decision replay executed with status MATCHED.' }), locale)).toBe(en ? 'Decision review: matched.' : '\u51b3\u7b56\u7248\u672c\u590d\u6838\u7ed3\u679c：\u547d\u4e2d。');
    });
    it(`${locale} failed decision cannot claim execution or matching`, () => {
      const result = decision({ status: 'FAILED', executed: false, matched: true, message: 'Decision replay executed with status ERROR.', errors: ['evaluation failed'] });
      expect(getReplayResultMessage(result, locale)).toBe(en ? 'Decision review failed. Check the decision version and sample context.' : '\u51b3\u7b56\u7248\u672c\u590d\u6838\u5931\u8d25，\u8bf7\u68c0\u67e5\u51b3\u7b56\u7248\u672c\u4e0e\u6837\u672c\u4e0a\u4e0b\u6587。');
      expect(result.errors).toEqual(['evaluation failed']);
    });
    it(`${locale} explicitly unexecuted decision cannot claim success`, () => {
      expect(getReplayResultMessage(decision({ executed: false }), locale)).toBe(en ? 'Decision review has not executed.' : '\u51b3\u7b56\u7248\u672c\u590d\u6838\u5c1a\u672a\u6267\u884c。');
    });
    it(`${locale} READY cannot claim execution from a stale message`, () => {
      expect(getReplayResultMessage(decision({ status: 'READY' }), locale)).toBe(en ? 'Decision review has not executed.' : '\u51b3\u7b56\u7248\u672c\u590d\u6838\u5c1a\u672a\u6267\u884c。');
    });
    it(`${locale} status-like substrings cannot become a matched verdict`, () => {
      expect(getReplayResultMessage(decision({ matched: undefined, message: 'Decision replay executed with status NOT_MATCHED_FUTURE.' }), locale)).toBe(en ? 'Decision review executed.' : '\u51b3\u7b56\u7248\u672c\u590d\u6838\u5df2\u6267\u884c\u3002');
    });
    it(`${locale} structured decisions work without a legacy message`, () => {
      expect(getReplayResultMessage(decision({ message: undefined }), locale)).toBe(en ? 'Decision review: not matched.' : '\u51b3\u7b56\u7248\u672c\u590d\u6838\u7ed3\u679c\uff1a\u672a\u547d\u4e2d\u3002');
    });
    for (const [consumerType, status, patch, zh, english] of [
      ['PERMISSION_POLICY', 'NEEDS_SAMPLE_CONTEXT', {}, '\u8865\u5145\u6210\u5458 ID \u548c\u8bb0\u5f55\u6837\u672c\u540e，\u53ef\u6267\u884c\u6743\u9650\u7b56\u7565\u590d\u6838。', 'Provide a member ID and record sample to review the permission policy.'],
      ['PERMISSION_POLICY', 'EXECUTED', { message: 'ALLOW' }, '\u6743\u9650\u7b56\u7565\u590d\u6838\u7ed3\u679c：\u5141\u8bb8。', 'Permission policy review: allowed.'],
      ['PERMISSION_POLICY', 'EXECUTED', { message: 'DENY' }, '\u6743\u9650\u7b56\u7565\u590d\u6838\u7ed3\u679c：\u62d2\u7edd。', 'Permission policy review: denied.'],
      ['WORKFLOW_PROCESS', 'FAILED', { outputs: { failClosed: true, fallbackApplied: false } }, '\u5de5\u4f5c\u6d41\u5206\u6d3e\u89c4\u5219\u590d\u6838\u5931\u8d25：\u89c4\u5219\u6267\u884c\u5f02\u5e38，\u5df2\u5931\u8d25\u5173\u95ed，\u672a\u4f7f\u7528\u9759\u6001\u5ba1\u6279\u4eba\u515c\u5e95。', 'Workflow assignment review failed: the rule failed closed; no static approver fallback was used.'],
      ['WORKFLOW_PROCESS', 'FAILED', { executed: false, matched: false, outputs: { failClosed: true, fallbackApplied: true, candidateUserIds: [], candidateGroupIds: [] } }, "\u5de5\u4f5c\u6d41\u5206\u6d3e\u89c4\u5219\u590d\u6838\u5931\u8d25\uff1a\u89c4\u5219\u6267\u884c\u5f02\u5e38\uff0c\u5df2\u5931\u8d25\u5173\u95ed\uff0c\u672a\u4f7f\u7528\u9759\u6001\u5ba1\u6279\u4eba\u515c\u5e95\u3002\u51b3\u7b56\u6267\u884c\u62a5\u544a\u663e\u793a\u4f7f\u7528\u4e86\u515c\u5e95\uff0c\u8bf7\u68c0\u67e5\u51b3\u7b56\u914d\u7f6e\u3002", "Workflow assignment review failed: the rule failed closed; no static approver fallback was used. The decision report indicates a fallback was used. Check the decision configuration."],
      ['WORKFLOW_PROCESS', 'FAILED', { outputs: { fallbackApplied: true } }, '\u5de5\u4f5c\u6d41\u89c4\u5219\u590d\u6838\u5931\u8d25：\u62a5\u544a\u663e\u793a\u4f7f\u7528\u4e86\u515c\u5e95，\u8bf7\u68c0\u67e5\u89c4\u5219\u914d\u7f6e。', 'Workflow review failed: the report indicates a fallback was used. Check the rule configuration.'],
      ['WORKFLOW_PROCESS', 'EXECUTED', { matched: false, outputs: { candidateUserIds: [] } }, '\u5de5\u4f5c\u6d41\u5206\u6d3e\u89c4\u5219\u590d\u6838\u5df2\u6267\u884c：\u672a\u547d\u4e2d\u5019\u9009\u4eba\u89c4\u5219。', 'Workflow assignment review executed: no candidate rule matched.'],
      ['SLA_RULE', 'EXECUTED', { message: 'SLA NODE replay executed' }, 'SLA \u8282\u70b9\u590d\u6838\u5df2\u6267\u884c。', 'SLA node review executed.'],
      ['SLA_RULE', 'EXECUTED', { message: 'SLA RECORD replay executed' }, 'SLA \u8bb0\u5f55\u590d\u6838\u5df2\u6267\u884c。', 'SLA record review executed.'],
    ] as const) {
      it(`${locale} ${consumerType}/${status}/${english} retains the actual outcome`, () => {
        const result: ModelPublishReplayResult = { step: { consumerType }, status, ...patch };
        const before = JSON.stringify(result);
        expect(getReplayResultMessage(result, locale)).toBe(en ? english : zh);
        expect(JSON.stringify(result)).toBe(before);
      });
    }
    it(`${locale} localizes known statuses but preserves unknown service statuses`, () => {
      expect(getReplayStatusLabel('FAILED', locale)).toBe(en ? 'Failed' : '\u5931\u8d25');
      expect(getReplayStatusLabel('FUTURE_STATUS', locale)).toBe('FUTURE_STATUS');
      expect(getReplayStatusLabel(undefined, locale)).toBe(en ? 'Pending review' : '\u5f85\u590d\u6838');
    });
    it(`${locale} unknown consumers retain their message and empty reports stay empty`, () => {
      expect(getReplayResultMessage({ message: 'Provider-specific diagnostic' }, locale)).toBe('Provider-specific diagnostic');
      expect(getReplayResultMessage({}, locale)).toBeNull();
    });
  }
});
