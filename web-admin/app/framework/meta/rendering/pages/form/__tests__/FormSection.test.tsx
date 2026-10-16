import React from 'react';
import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { FormSection } from '../FormSection';

describe('DSL FormSection', () => {
  it('keeps input state when a user collapses and reopens an optional section', () => {
    render(<FormSection title="Notes" collapsible><input aria-label="Note" defaultValue="initial" /></FormSection>);
    const input = screen.getByLabelText('Note') as HTMLInputElement;
    fireEvent.change(input, { target: { value: 'retained' } });
    const details = input.closest('details')!;
    details.open = false;
    fireEvent(details, new Event('toggle'));
    expect(input.value).toBe('retained');
    details.open = true;
    fireEvent(details, new Event('toggle'));
    expect(screen.getByLabelText('Note')).toHaveValue('retained');
  });

  it('opens a default-collapsed section when validation reports an error', () => {
    const { rerender } = render(<FormSection title="Required" collapsible defaultCollapsed><input aria-label="Name" /></FormSection>);
    expect(screen.getByLabelText('Name').closest('details')).not.toHaveAttribute('open');
    rerender(<FormSection title="Required" collapsible defaultCollapsed hasError><input aria-label="Name" /></FormSection>);
    expect(screen.getByLabelText('Name').closest('details')).toHaveAttribute('open');
  });

  it('keeps ordinary sections visible even if a stray collapsed default is supplied', () => {
    render(<FormSection title="Basics" description="Identify the record" defaultCollapsed><input aria-label="Visible" /></FormSection>);
    expect(screen.getByLabelText('Visible')).toBeVisible();
    expect(screen.getByText('Identify the record')).toBeVisible();
    expect(document.querySelector('details')).toBeNull();
  });
});
