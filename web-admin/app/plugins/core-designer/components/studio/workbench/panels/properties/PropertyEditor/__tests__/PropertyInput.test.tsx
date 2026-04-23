import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { PropertyInput } from '../PropertyInput';

describe('PropertyInput', () => {
  it('renders the shared icon picker when property type is icon', () => {
    render(
      <PropertyInput
        property={{ key: 'icon', type: 'icon', label: '图标' }}
        value="success"
        onChange={vi.fn()}
      />,
    );

    expect(screen.getByRole('button', { name: '成功' })).toBeInTheDocument();
    expect(screen.getByText('成功')).toBeInTheDocument();
  });
});
