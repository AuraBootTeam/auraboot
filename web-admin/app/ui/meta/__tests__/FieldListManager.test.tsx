import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { ModelFieldBinding } from '~/types/model';
import { FieldListManager } from '../FieldListManager';

const { permissions } = vi.hoisted(() => ({ permissions: new Set<string>() }));
vi.mock('~/contexts/AuthContext', () => ({
  usePermissions: () => ({
    hasPermission: (code: string) => permissions.has(code),
    hasAllPermissions: (codes: string[]) => codes.every((code) => permissions.has(code)),
    hasAnyPermission: (codes: string[]) => codes.some((code) => permissions.has(code)),
    hasRole: () => false,
  }),
}));
vi.mock('~/utils/i18n', () => ({
  useSmartText: () => (text: string | Record<string, string>) =>
    typeof text === 'string' ? text : text['zh-CN'],
}));
vi.mock('../FieldSelectionDialog', () => ({ FieldSelectionDialog: () => null }));

const fields: ModelFieldBinding[] = [
  {
    id: '41',
    modelCode: 'model-code',
    required: false,
    pid: 'field-one',
    code: 'first',
    fieldCode: 'first',
    dataType: 'string',
    displayOrder: 1,
  },
  {
    id: '42',
    modelCode: 'model-code',
    required: false,
    pid: 'field-two',
    code: 'second',
    fieldCode: 'second',
    dataType: 'string',
    displayOrder: 2,
  },
];

function fixture() {
  const onFieldsReorder = vi.fn().mockResolvedValue(undefined);
  render(
    <FieldListManager
      fields={fields}
      modelPid="model-pid"
      modelCode="model-code"
      onFieldsReorder={onFieldsReorder}
      onFieldConfigure={vi.fn()}
      onFieldUnbind={vi.fn()}
      onFieldBound={vi.fn()}
    />,
  );
  return { onFieldsReorder };
}

describe('FieldListManager keyboard and permission boundaries', () => {
  beforeEach(() => {
    permissions.clear();
    vi.restoreAllMocks();
  });

  it('activates the real keyboard sensor and cancels without saving', async () => {
    permissions.add('meta.model.update');
    const { onFieldsReorder } = fixture();
    const handle = screen.getAllByTestId('model-field-reorder')[1];
    handle.focus();
    fireEvent.keyDown(handle, { key: ' ', code: 'Space' });
    await waitFor(() => expect(handle).toHaveAttribute('aria-pressed', 'true'));
    fireEvent.keyDown(handle, { key: 'Escape', code: 'Escape' });
    await waitFor(() => expect(handle).not.toHaveAttribute('aria-pressed'));
    expect(screen.getByRole('status')).toHaveTextContent('Dragging was cancelled');
    expect(onFieldsReorder).not.toHaveBeenCalled();
  });

  it('keeps fields readable and hides all write controls without model maintenance', () => {
    fixture();
    expect(screen.getByText('first')).toBeVisible();
    expect(screen.getByText('second')).toBeVisible();
    expect(screen.getByTestId('model-fields-guidance')).toHaveTextContent('字段配置仅供查看');
    for (const id of [
      'model-field-reorder',
      'model-field-configure',
      'model-field-unbind',
      'model-fields-add-button',
    ]) {
      expect(screen.queryByTestId(id)).toBeNull();
    }
  });
});
