import React from 'react';
import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { AuthoringConflictResolutionPanel } from '../AuthoringConflictResolutionPanel';
import type { StudioThreeWayMerge } from '../persistence/contextualAuthoringAdapter';

describe('AuthoringConflictResolutionPanel', () => {
  it('shows business labels without leaking stable IDs, property paths, or raw JSON', () => {
    const onResolve = vi.fn();
    render(
      <AuthoringConflictResolutionPanel
        merge={mergeFixture()}
        baseRevision={7}
        latestRevision={9}
        pending={false}
        onResolve={onResolve}
        onUseLatest={vi.fn()}
      />,
    );

    expect(screen.getByTestId('authoring-conflict-panel')).toHaveTextContent(
      'Base / Mine / Latest',
    );
    expect(screen.getByTestId('authoring-conflict-0')).toHaveTextContent('标题');
    expect(screen.getByTestId('authoring-conflict-0')).toHaveTextContent('表格');
    expect(screen.getByTestId('authoring-conflict-2')).toHaveTextContent('业务模型');
    expect(screen.getByTestId('authoring-conflict-3')).toHaveTextContent('3 个同级区块的排列');
    expect(screen.queryByText('/title')).not.toBeInTheDocument();
    expect(screen.queryByText('table-internal-id')).not.toBeInTheDocument();
    expect(screen.queryByText('table-a')).not.toBeInTheDocument();
    screen.getAllByTestId('authoring-conflict-value-base').forEach((value: HTMLElement) => {
      expect(value).not.toHaveTextContent(/[{}]/);
    });

    chooseResolution(0, '保留 Mine');
    chooseResolution(1, '保留 Latest');
    chooseResolution(2, '保留 Latest');
    chooseResolution(3, '保留 Mine');
    fireEvent.click(screen.getByTestId('authoring-conflict-apply'));

    expect(onResolve).toHaveBeenCalledWith({
      'PROPERTY:table-internal-id:/title': 'MINE',
      'PROPERTY:table-internal-id:/layout/span': 'LATEST',
      'PROPERTY:table-internal-id:/dataSource': 'LATEST',
      'ORDER:$page-root': 'MINE',
    });
  });
});

function chooseResolution(index: number, label: string): void {
  const value = label.endsWith('Mine') ? 'MINE' : 'LATEST';
  const conflict: HTMLElement = screen.getByTestId(`authoring-conflict-${index}`);
  const input = conflict.querySelector<HTMLInputElement>(`input[value="${value}"]`);
  expect(input).not.toBeNull();
  fireEvent.click(input!);
}

function mergeFixture(): StudioThreeWayMerge {
  return {
    autoMergedDocument: {
      schemaVersion: 3,
      kind: 'list',
      id: 'production-exceptions',
      blocks: [],
    },
    autoMergedChanges: 0,
    unsupported: [],
    conflicts: [
      {
        id: 'PROPERTY:table-internal-id:/title',
        kind: 'PROPERTY',
        blockId: 'table-internal-id',
        blockType: 'table',
        propertyPath: '/title',
        baseValue: '生产异常',
        mineValue: '我的生产异常',
        latestValue: '最新生产异常',
      },
      {
        id: 'PROPERTY:table-internal-id:/layout/span',
        kind: 'PROPERTY',
        blockId: 'table-internal-id',
        blockType: 'table',
        propertyPath: '/layout/span',
        baseValue: 12,
        mineValue: 10,
        latestValue: 8,
      },
      {
        id: 'PROPERTY:table-internal-id:/dataSource',
        kind: 'PROPERTY',
        blockId: 'table-internal-id',
        blockType: 'table',
        propertyPath: '/dataSource',
        baseValue: { model: '生产异常' },
        mineValue: { model: '质量异常' },
        latestValue: { model: '设备异常' },
      },
      {
        id: 'ORDER:$page-root',
        kind: 'ORDER',
        blockId: '$page-root',
        propertyPath: '/$structure/order',
        baseValue: ['table-a', 'table-b', 'table-c'],
        mineValue: ['table-b', 'table-a', 'table-c'],
        latestValue: ['table-a', 'table-c', 'table-b'],
      },
    ],
  };
}
