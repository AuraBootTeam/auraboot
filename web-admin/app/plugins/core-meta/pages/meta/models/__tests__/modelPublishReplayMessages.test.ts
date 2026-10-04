import { getMigrationPlanMessage, getHistoricalPolicyMessage, getPolicyHeading } from '../modelPublishPolicyMessages';
import type { ModelPublishGovernance } from '~/shared/services/modelService';
import { describe, expect, it } from 'vitest';
import { getReplayResultMessage, getReplayStatusLabel, getReplayErrorMessage } from '../modelPublishReplayMessages';
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
    for (const [error, consumerType, failClosed, zh, english] of [["BPM_RULE_BINDING_FAIL_CLOSED", "WORKFLOW_PROCESS", true, "\u89c4\u5219\u7ed1\u5b9a\u5df2\u5931\u8d25\u5173\u95ed\uff0c\u672a\u8fd4\u56de\u5019\u9009\u5ba1\u6279\u4eba\u6216\u5019\u9009\u5ba1\u6279\u7ec4", "The rule binding failed closed; no candidate approvers or approval groups were returned."], ["WORKFLOW_RULE_BINDING_FAIL_CLOSED", "WORKFLOW_PROCESS", true, "\u89c4\u5219\u7ed1\u5b9a\u5df2\u5931\u8d25\u5173\u95ed\uff0c\u672a\u8fd4\u56de\u5019\u9009\u5ba1\u6279\u4eba\u6216\u5019\u9009\u5ba1\u6279\u7ec4", "The rule binding failed closed; no candidate approvers or approval groups were returned."], ["BPM_RULE_BINDING_FAIL_CLOSED", "WORKFLOW_PROCESS", false, "\u89c4\u5219\u7ed1\u5b9a\u5df2\u5931\u8d25\u5173\u95ed", "The rule binding failed closed."], ["DECISION_EVALUATION_FAILED", "DECISION_VERSION", false, "\u51b3\u7b56\u6267\u884c\u5931\u8d25", "Decision execution failed."], ["provider failure", "WORKFLOW_PROCESS", true, "\u51b3\u7b56\u6267\u884c\u5931\u8d25\uff0c\u8bf7\u68c0\u67e5\u7ed1\u5b9a\u7684\u51b3\u7b56\u7248\u672c\u3001\u8f93\u5165\u6620\u5c04\u548c\u515c\u5e95\u7b56\u7565", "Decision execution failed. Check the bound decision version, input mapping and fallback policy."], ["provider failure", "DECISION_VERSION", false, "provider failure", "provider failure"], ["WORKFLOW_RULE_BINDING_FAIL_CLOSED", "WORKFLOW_PROCESS", false, "\u89c4\u5219\u7ed1\u5b9a\u5df2\u5931\u8d25\u5173\u95ed", "The rule binding failed closed."]] as const) {
      it(`${locale} ${error}/${consumerType}/${failClosed} preserves the error outcome`, () => {
        const result: ModelPublishReplayResult = { step: { consumerType }, status: 'FAILED', executed: false, outputs: { failClosed }, errors: [error] };
        const before = JSON.stringify(result);
        expect(getReplayErrorMessage(error, result, locale)).toBe(en ? english : zh);
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

describe('publish policy locale contract', () => {
  const cases = [["NO_SCHEMA_MIGRATION", "\u65e0\u9700\u8fc1\u79fb\u7269\u7406\u8868\u7ed3\u6784\u3002\u82e5\u4ec5\u5b57\u6bb5\u5143\u6570\u636e\u53d1\u751f\u53d8\u5316\uff0c\u8bf7\u91cd\u5efa\u89c4\u5219\u4e2d\u5fc3\u4f7f\u7528\u7d22\u5f15\u3002", "No physical schema migration is required. Rebuild the Rule Center usage index if field metadata changed without DDL."], ["CREATE_TABLE", "\u542f\u7528\u8fd0\u884c\u65f6\u5199\u5165\u524d\uff0c\u521b\u5efa\u7269\u7406\u8868\u53ca\u751f\u6210\u7684\u7d22\u5f15\u3002", "Create the physical table and generated indexes before enabling runtime writes."], ["ADD_COLUMN", "\u5c06\u89c4\u5219\u5207\u6362\u5230\u65b0\u5b57\u6bb5\u524d\uff0c\u56de\u586b\u65b0\u589e\u5217\u6216\u8bbe\u7f6e\u9ed8\u8ba4\u503c\u3002", "Backfill new columns or define defaults before routing rules to the new field."], ["ALTER_COLUMN_TYPE", "\u53d1\u5e03\u524d\u9a8c\u8bc1\u6570\u636e\u7c7b\u578b\u8f6c\u6362\uff0c\u5e76\u4f7f\u7528\u4ee3\u8868\u6027\u8bb0\u5f55\u56de\u653e\u53d7\u5f71\u54cd\u7684\u89c4\u5219\u3002", "Validate data casts and replay affected rules against representative records before promotion."], ["DROP_COLUMN", "\u5220\u9664\u5217\u524d\uff0c\u505c\u7528\u6216\u8fc1\u79fb\u6240\u6709\u53d7\u5f71\u54cd\u7684\u89c4\u5219\u6d88\u8d39\u65b9\u3002", "Retire or migrate every affected rule consumer before removing the column."], ["NULLABILITY", "\u53d1\u5e03\u524d\u68c0\u67e5\u5df2\u6709\u8bb0\u5f55\u662f\u5426\u6ee1\u8db3\u5fc5\u586b\u548c\u53ef\u7a7a\u6027\u53d8\u66f4\u3002", "Check existing rows against required/nullability changes before publish."], ["REPLAY_CONSUMERS", "\u786e\u8ba4\u89c4\u5219\u4e2d\u5fc3\u5f71\u54cd\u8303\u56f4\uff0c\u5e76\u91cd\u65b0\u53d1\u5e03\u6216\u56de\u653e\u53d7\u5f71\u54cd\u7684\u5de5\u4f5c\u6d41\u3001SLA\u3001\u81ea\u52a8\u5316\u3001\u4e8b\u4ef6\u7b56\u7565\u53ca\u51b3\u7b56\u7248\u672c\u3002", "Confirm Rule Center blast radius and republish or replay affected BPM, SLA, Automation, EventPolicy and decision versions."], ["REVIEW_DDL", "\u590d\u6838\u751f\u6210\u7684 DDL\uff0c\u5e76\u5728\u53d1\u5e03\u540e\u9a8c\u8bc1\u8868\u7ed3\u6784\u540c\u6b65\u3002", "Review generated DDL and run a post-publish schema sync smoke."]] as const;
  for (const locale of ['zh-CN', 'en-US']) {
    for (const [code, zh, en] of cases) {
      it(locale + ' ' + code + ' expresses the server migration step', () => {
        const value: ModelPublishGovernance = { modelCode: 'sample', migrationPlanSteps: [code], migrationPlan: 'Legacy server text' };
        const before = JSON.stringify(value);
        expect(getMigrationPlanMessage(value, locale)).toBe(locale === 'en-US' ? en : zh);
        expect(JSON.stringify(value)).toBe(before);
      });
    }
    it(locale + ' preserves ordered steps without dropping consumer replay', () => {
      const value = { modelCode: 'sample', migrationPlanSteps: ['CREATE_TABLE', 'NULLABILITY', 'REPLAY_CONSUMERS'] };
      const expected = [cases[1], cases[5], cases[6]].map((row) => row[locale === 'en-US' ? 2 : 1]).join(' ');
      expect(getMigrationPlanMessage(value, locale)).toBe(expected);
    });
    it(locale + ' preserves unknown and legacy server descriptions', () => {
      expect(getMigrationPlanMessage({ modelCode: 'sample', migrationPlanSteps: ['CREATE_TABLE', 'FUTURE_STEP'], migrationPlan: 'Future policy' }, locale)).toBe('Future policy');
      expect(getMigrationPlanMessage({ modelCode: 'sample', migrationPlan: 'Legacy policy' }, locale)).toBe('Legacy policy');
      expect(getMigrationPlanMessage({ modelCode: 'sample', migrationPlanSteps: ['FUTURE_STEP'] }, locale)).toBe('FUTURE_STEP');
      expect(getHistoricalPolicyMessage({ modelCode: 'sample', historicalVersionPolicyCode: 'FUTURE', historicalVersionPolicy: 'Future history' }, locale)).toBe('Future history');
      expect(getHistoricalPolicyMessage({ modelCode: 'sample', historicalVersionPolicy: 'Legacy history' }, locale)).toBe('Legacy history');
      expect(getHistoricalPolicyMessage({ modelCode: 'sample' }, locale)).toBe('');
    });
    it(locale + ' labels both policy sections', () => {
      expect(getPolicyHeading('migrationHeading', locale)).toBe(locale === 'en-US' ? 'Migration plan' : '\u8fc1\u79fb\u8ba1\u5212');
      expect(getPolicyHeading('historyHeading', locale)).toBe(locale === 'en-US' ? 'Historical version policy' : '\u5386\u53f2\u7248\u672c\u7b56\u7565');
    });
  }
  it('distinguishes initial publish from version compatibility in both locales', () => {
    const initial = { modelCode: 'sample', historicalVersionPolicyCode: 'INITIAL_PUBLISH' };
    const latest = { modelCode: 'sample', historicalVersionPolicyCode: 'LATEST_COMPATIBLE' };
    expect(getHistoricalPolicyMessage(initial, 'en-US')).toBe('Initial publish: no historical published model version exists. Rule consumers should bind to this published schema after publish.');
    expect(getHistoricalPolicyMessage(latest, 'en-US')).toBe('Latest-compatible policy: publishing this draft makes it the current model metadata. Existing published rule, BPM, SLA, Automation and EventPolicy versions keep their own versioned assets, but consumers using latest model fields must be replayed and republished after acknowledgement.');
    expect(getHistoricalPolicyMessage(initial, 'zh-CN')).toMatch(/^\u9996\u6b21\u53d1\u5e03/);
    expect(getHistoricalPolicyMessage(latest, 'zh-CN')).toMatch(/^\u91c7\u7528\u6700\u65b0\u517c\u5bb9\u7b56\u7565/);
    expect(getHistoricalPolicyMessage(latest, 'zh-CN')).toContain('\u56de\u653e\u5e76\u91cd\u65b0\u53d1\u5e03');
  });
});
