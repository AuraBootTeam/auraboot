import { describe, expect, it } from 'vitest';
import type { AuthoringRoleStructurePreview } from '~/framework/meta/authoring/types';
import { DESIGNER_I18N, resolveDesignerText } from '~/shared/designer';
import type { PageSchemaV3 } from '../types';
import {
  createRoleStructurePermissionEvaluator,
  roleStructurePreviewRuntimeServices,
  sanitizeRoleStructurePreviewDocument,
  summarizeRoleStructureDecisions,
} from '../preview/roleStructurePreview';

const preview: AuthoringRoleStructurePreview = {
  mode: 'STRUCTURE',
  pagePid: 'page-1',
  targetRole: { rolePid: 'role-1', roleCode: 'operator', roleName: 'Operator' },
  actorIntersectionApplied: true,
  businessDataIncluded: false,
  exportAllowed: false,
  businessActionsAllowed: false,
  decisions: [
    {
      nodeType: 'FIELD',
      nodeId: 'public-field',
      label: 'Public',
      permissionCode: 'Customer.Public.Read',
      allowed: true,
      visible: true,
      writable: false,
      reason: 'ALLOW',
    },
    {
      nodeType: 'FIELD',
      nodeId: 'secret-field',
      label: 'Secret',
      permissionCode: 'customer.secret.read',
      allowed: false,
      visible: false,
      writable: false,
      reason: 'ACTOR_SCOPE_LIMIT',
    },
  ],
};

describe('role structure preview boundary', () => {
  it('removes embedded record values while preserving the page structure', () => {
    const document: PageSchemaV3 = {
      schemaVersion: 3,
      kind: 'list',
      id: 'customer-list',
      blocks: [
        {
          id: 'table',
          blockType: 'table',
          dataSource: { model: 'customer' },
          props: {
            label: 'Customers',
            rows: [{ pid: 'real-record', secret: 'must-not-render' }],
            defaultValue: 'real-record',
          },
          blocks: [{ id: 'name', blockType: 'column', field: 'name' }],
        },
      ],
    };

    const sanitized = sanitizeRoleStructurePreviewDocument(document);

    expect(sanitized.blocks[0].props).toEqual({ label: 'Customers' });
    expect(sanitized.blocks[0].dataSource).toEqual({ model: 'customer' });
    expect(JSON.stringify(sanitized)).not.toContain('real-record');
    expect(JSON.stringify(sanitized)).not.toContain('must-not-render');
  });

  it('uses the backend intersection and fails closed for unreferenced permissions', () => {
    const evaluate = createRoleStructurePermissionEvaluator(preview);

    expect(evaluate('customer.public.read')).toBe(true);
    expect(evaluate('CUSTOMER.SECRET.READ')).toBe(false);
    expect(evaluate('unreferenced.permission')).toBe(false);
    expect(summarizeRoleStructureDecisions(preview.decisions)).toEqual([
      { nodeType: 'FIELD', allowed: 1, total: 2 },
    ]);
  });

  it('never loads records or executes actions through the structure runtime', async () => {
    const block = { id: 'action', blockType: 'action' } as const;

    await expect(roleStructurePreviewRuntimeServices.loadWidgetData?.(block)).resolves.toBeNull();
    await expect(roleStructurePreviewRuntimeServices.loadPickerOptions?.(block)).resolves.toEqual([]);
    await expect(roleStructurePreviewRuntimeServices.loadHelperBlockData?.(block)).resolves.toBeNull();
    await expect(roleStructurePreviewRuntimeServices.executeAction?.(block)).rejects.toMatchObject({
      code: 'ROLE_STRUCTURE_PREVIEW_ACTION_DISABLED',
      kind: 'permission',
    });
  });

  it('keeps the governed preview contract bilingual', () => {
    expect(
      resolveDesignerText(DESIGNER_I18N.unified.rolePreview.title, 'zh-CN', {
        role: '操作员',
      }),
    ).toBe('操作员 · 权限结构预览');
    expect(
      resolveDesignerText(DESIGNER_I18N.unified.rolePreview.noTargetData, 'en-US'),
    ).toBe('No target-role business data is read');
    expect(resolveDesignerText(DESIGNER_I18N.unified.rolePreview.actionsOff, 'en-US')).toBe(
      'Business actions disabled',
    );
  });
});
