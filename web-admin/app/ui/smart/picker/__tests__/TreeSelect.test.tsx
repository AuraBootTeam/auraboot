import React from 'react';
import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { fieldInputHeightStyles, fieldSizeStyles } from '~/ui/ui/field-styles';
import { TreeSelect } from '../TreeSelect';

vi.mock('react-router', async (importOriginal) => ({
  ...(await importOriginal<typeof import('react-router')>()),
  useActionData: () => undefined,
}));

const treeData = [{ key: 'supply', value: 'supply', label: '供应链协同' }];

describe('TreeSelect sizing', () => {
  it('uses the standard form density before and after selecting an option', () => {
    const onChange = vi.fn();
    render(
      <TreeSelect name="domain" placeholder="请选择业务领域" treeData={treeData} onChange={onChange} />,
    );

    const trigger = screen.getByTestId('tree-select-trigger');
    const standardClasses = [fieldInputHeightStyles.medium, ...fieldSizeStyles.medium.split(' ')];
    expect(trigger).toHaveClass(...standardClasses);
    expect(trigger).toHaveTextContent('请选择业务领域');

    fireEvent.click(trigger);
    fireEvent.click(screen.getByText('供应链协同'));

    expect(onChange).toHaveBeenCalledWith('supply', treeData);
    expect(trigger).toHaveClass(...standardClasses);
    expect(trigger).toHaveTextContent('供应链协同');
  });

  it.each([
    ['small', 'text-sm', 'min-h-[2rem]'],
    ['large', 'text-lg', 'min-h-[3rem]'],
  ] as const)('preserves the explicit %s size', (size, font, height) => {
    render(<TreeSelect name="domain" size={size} treeData={treeData} />);
    expect(screen.getByTestId('tree-select-trigger')).toHaveClass(font, height);
  });
});
