import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { InputField, SelectField, TextareaField } from '../FormField';

describe('FormField required contract', () => {
  it('passes required semantics to native form controls', () => {
    const onChange = vi.fn();
    render(
      <>
        <InputField label="学校名称" name="school" value="" onChange={onChange} required />
        <SelectField
          label="学段"
          name="stage"
          value=""
          onChange={onChange}
          options={[{ value: '', label: '请选择' }]}
          required
        />
        <TextareaField label="说明" name="note" value="" onChange={onChange} required />
      </>,
    );

    expect(screen.getByRole('textbox', { name: /学校名称/ })).toBeRequired();
    expect(screen.getByRole('combobox', { name: /学段/ })).toBeRequired();
    expect(screen.getByRole('textbox', { name: /说明/ })).toBeRequired();
  });
});
