import { describe, expect, it } from 'vitest';
import {
  factCatalogToFieldOptions,
  modelFieldsToFieldOptions,
} from '../factCatalogAdapter';

describe('factCatalogAdapter', () => {
  it('maps low-code facts into condition field options without losing dict/reference/source metadata', () => {
    const fields = factCatalogToFieldOptions({
      entities: [
        {
          entityCode: 'wd_leave_request',
          modelCode: 'wd_leave_request',
          label: '请假申请',
          sourceType: 'sqlView',
          sourceRef: 'select * from mt_wd_leave_request',
          facts: [
            {
              factKey: 'wd_leave_request.wd_req_type',
              scope: 'record',
              path: 'record.data.wd_req_type',
              label: '请假类型',
              dataType: 'dict',
              dictCode: 'wd_leave_type',
              allowedValues: [
                { value: 'annual', label: '年假' },
                { value: 'sick', label: '病假' },
              ],
              operators: ['EQ', 'IN'],
              required: true,
              visible: true,
              editable: false,
              permission: 'wd.leave.view',
              masked: false,
            },
            {
              factKey: 'wd_leave_request.approver',
              scope: 'record',
              path: 'record.data.approverPid',
              label: '审批人',
              dataType: 'reference',
              reference: {
                targetEntity: 'user',
                valueField: 'pid',
                displayField: 'name',
              },
            },
          ],
        },
        {
          entityCode: 'shared_context',
          label: '共享上下文',
          facts: [
            {
              scope: 'actor',
              path: 'userId',
              label: '当前用户',
              dataType: 'string',
            },
          ],
        },
      ],
    });

    const leaveType = fields.find((field) => field.path === 'data.wd_req_type');
    expect(leaveType).toMatchObject({
      scope: 'record',
      label: '请假类型',
      dataType: 'dict',
      modelCode: 'wd_leave_request',
      modelName: '请假申请',
      options: ['annual', 'sick'],
      valueLabels: { annual: '年假', sick: '病假' },
      operators: ['EQ', 'IN'],
      dictCode: 'wd_leave_type',
      required: true,
      visible: true,
      editable: false,
      permission: 'wd.leave.view',
      masked: false,
      sourceType: 'sqlView',
      sourceRef: 'select * from mt_wd_leave_request',
      factKey: 'wd_leave_request.wd_req_type',
    });

    const approver = fields.find((field) => field.path === 'data.approverPid');
    expect(approver).toMatchObject({
      scope: 'record',
      label: '审批人',
      dataType: 'user',
      reference: {
        targetEntity: 'user',
        valueField: 'pid',
        displayField: 'name',
      },
    });

    expect(fields).toContainEqual(
      expect.objectContaining({
        scope: 'actor',
        path: 'userId',
        label: '当前用户',
        dataType: 'string',
      }),
    );
  });

  it('keeps model field compatibility for legacy endpoints', () => {
    expect(
      modelFieldsToFieldOptions([
        {
          entityCode: 'wd_leave_request',
          modelCode: 'wd_leave_request',
          modelName: '请假申请',
          path: 'record.data.wd_req_days',
          label: '请假天数',
          dataType: 'decimal',
        },
      ]),
    ).toEqual([
      expect.objectContaining({
        scope: 'record',
        path: 'data.wd_req_days',
        label: '请假天数',
        dataType: 'decimal',
        modelCode: 'wd_leave_request',
        modelName: '请假申请',
      }),
    ]);
  });
});
