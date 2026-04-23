import { describe, expect, it } from 'vitest';

import {
  deriveTaskDetailModelSegment,
  resolveTaskDetailPath,
} from '../TaskCenter';

describe('TaskCenter detail navigation helpers', () => {
  it('derives a model segment from detail and form page refs', () => {
    expect(deriveTaskDetailModelSegment('wd_leave_request_detail')).toBe('wd_leave_request');
    expect(deriveTaskDetailModelSegment('wd_leave_request_form')).toBe('wd_leave_request');
    expect(deriveTaskDetailModelSegment('custom_page')).toBeNull();
  });

  it('prefers the business detail page when formRef and businessKey are present', () => {
    expect(
      resolveTaskDetailPath({
        formRef: 'wd_leave_request_detail',
        businessKey: '01KPTKWGVWGPYWMJ811XJ35HRV',
        processInstanceId: 'pi-1',
        processDefinitionKey: 'wd_leave_approval',
      }),
    ).toBe('/p/wd_leave_request/view/01KPTKWGVWGPYWMJ811XJ35HRV');
  });

  it('falls back to process-status when no business detail page can be derived', () => {
    expect(
      resolveTaskDetailPath({
        formRef: null,
        businessKey: null,
        processInstanceId: 'pi-1',
        processDefinitionKey: 'demo_process',
      }),
    ).toBe('/bpm/process-status?processInstanceId=pi-1');
  });

  it('uses businessKey/processKey fallback when only business identifiers exist', () => {
    expect(
      resolveTaskDetailPath({
        formRef: null,
        businessKey: 'BIZ-42',
        processInstanceId: null,
        processDefinitionKey: 'demo_process',
      }),
    ).toBe('/bpm/process-status?businessKey=BIZ-42&processKey=demo_process');
  });
});
