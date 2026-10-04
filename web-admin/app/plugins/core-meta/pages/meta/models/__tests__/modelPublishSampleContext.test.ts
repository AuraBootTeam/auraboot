import { describe, expect, it } from 'vitest';
import { buildPermissionReplayContext as permission, buildSlaNodeReplayContext as sla, buildWorkflowReplayContext as workflow, getPublishText } from '../modelPublishSampleContext';

const p = { memberId: ' 0012 ', permissionCode: ' POLICY ', recordPid: ' record-1 ', recordJson: '{"enabled":false,"amount":0,"nested":{"items":[1,2]}}' };
const s = { processInstanceId: ' instance-1 ', tenantId: ' 0034 ', taskId: ' task-1 ', processKey: ' process-1 ', recordJson: '{"amount":0}' };
const w = { processInstanceId: ' instance-1 ', processKey: ' process-1 ', recordPid: ' record-1 ', recordJson: '{"enabled":false}' };

describe('publish samples preserve request context across locales', () => {
  for (const locale of ['en-US', 'zh-CN']) {
    const en = locale === 'en-US';
    it(locale + ' preserves permission IDs, nested data and falsy values without changing the form', () => {
      const before = JSON.stringify(p);
      expect(permission(p, locale)).toEqual({ permission: { memberId: '0012', permissionCode: 'POLICY' },
        record: { pid: 'record-1', data: { enabled: false, amount: 0, nested: { items: [1, 2] } } } });
      expect(JSON.stringify(p)).toBe(before);
    });
    it(locale + ' omits blank optional permission fields and treats blank JSON as an empty object', () => {
      expect(permission({ ...p, permissionCode: ' ', recordPid: ' ', recordJson: ' ' }, locale))
        .toEqual({ permission: { memberId: '0012' }, record: { data: {} } });
    });
    it(locale + ' retains large member IDs as strings', () => {
      expect(permission({ ...p, memberId: '900719925474099312345' }, locale).permission.memberId)
        .toBe('900719925474099312345');
    });
    it(locale + ' preserves SLA IDs and falsy data without changing the form', () => {
      const before = JSON.stringify(s);
      expect(sla(s, locale)).toEqual({ workflow: { processInstanceId: 'instance-1', tenantId: '0034', taskId: 'task-1', processKey: 'process-1' }, record: { data: { amount: 0 } } });
      expect(JSON.stringify(s)).toBe(before);
    });
    it(locale + ' omits blank optional SLA values', () => {
      expect(sla({ ...s, taskId: ' ', processKey: ' ', recordJson: '' }, locale)).toEqual({
        workflow: { processInstanceId: 'instance-1', tenantId: '0034' }, record: { data: {} } });
    });
    it(locale + ' preserves workflow record and process context without changing the form', () => {
      const before = JSON.stringify(w);
      expect(workflow(w, locale)).toEqual({ workflow: { processInstanceId: 'instance-1', processKey: 'process-1' },
        record: { pid: 'record-1', data: { enabled: false } } });
      expect(JSON.stringify(w)).toBe(before);
    });
    it(locale + ' omits the workflow envelope when no workflow sample is supplied', () => {
      expect(workflow({ processInstanceId: ' ', processKey: '', recordPid: ' ', recordJson: ' ' }, locale))
        .toEqual({ record: { data: {} } });
    });
    it(locale + ' retains a process-key-only workflow sample', () => {
      expect(workflow({ ...w, processInstanceId: '' }, locale).workflow).toEqual({ processKey: 'process-1' });
    });
    for (const value of ['', ' ', '0', '000', '-1', '1.2', '1e3', 'abc']) {
      it(locale + ' rejects permission member ' + JSON.stringify(value) + ' before parsing JSON', () => {
        expect(() => permission({ ...p, memberId: value, recordJson: '{' }, locale))
          .toThrow(en ? 'Enter a valid permission member ID' : '请填写有效的权限成员 ID');
      });
      it(locale + ' rejects SLA tenant ' + JSON.stringify(value) + ' before parsing JSON', () => {
        expect(() => sla({ ...s, tenantId: value, recordJson: '{' }, locale))
          .toThrow(en ? 'Enter a valid tenant ID' : '请填写有效的租户 ID');
      });
    }
    it(locale + ' requires an SLA instance before validating tenant and JSON', () => {
      expect(() => sla({ ...s, processInstanceId: ' ', tenantId: '', recordJson: '{' }, locale))
        .toThrow(en ? 'Enter a process instance ID' : '请填写流程实例 ID');
    });
    for (const [kind, build, form, invalid, object] of [
      ['permission', permission, p, ['Record data must be a valid JSON object', '记录数据必须是有效 JSON 对象'], ['Record data must be a JSON object', '记录数据必须是 JSON 对象']],
      ['sla', sla, s, ['SLA record data must be a valid JSON object', 'SLA 记录数据必须是有效 JSON 对象'], ['SLA record data must be a JSON object', 'SLA 记录数据必须是 JSON 对象']],
      ['workflow', workflow, w, ['Workflow record data must be a valid JSON object', '工作流记录数据必须是有效 JSON 对象'], ['Workflow record data must be a JSON object', '工作流记录数据必须是 JSON 对象']],
    ] as const) {
      it(locale + ' ' + kind + ' rejects malformed JSON with localized feedback', () => {
        expect(() => build({ ...form, recordJson: '{' } as never, locale)).toThrow(invalid[en ? 0 : 1]);
      });
      for (const raw of ['null', '[]', 'true', '0', '"text"']) {
        it(locale + ' ' + kind + ' rejects non-object JSON ' + raw, () => {
          expect(() => build({ ...form, recordJson: raw } as never, locale)).toThrow(object[en ? 0 : 1]);
        });
      }
    }
    it(locale + ' resolves empty DDL and submit/error feedback', () => {
      expect(getPublishText('noDdl', locale)).toBe(en ? 'No DDL statements' : '无 DDL 语句');
      expect(getPublishText('confirm', locale)).toBe(en ? 'Confirm publication' : '确认发布');
      expect(getPublishText('previewFailed', locale)).toBe(en ? 'Failed to load the DDL preview' : '获取DDL预览失败');
    });
  }
  it('resolves the same invalid form using the current locale without changing values', () => {
    const form = { ...p, memberId: '' };
    expect(() => permission(form, 'en-US')).toThrow('Enter a valid permission member ID');
    expect(() => permission(form, 'zh-CN')).toThrow('请填写有效的权限成员 ID');
    expect(form.memberId).toBe('');
  });
});
