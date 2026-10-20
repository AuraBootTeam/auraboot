import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { FieldCreationForm } from '../FieldCreationForm';
const text = (value: { en: string }) => value.en;
const ensureLoaded = vi.fn();
const getEnumOptions = () => [
  { value: 'string', label: 'String' },
  { value: 'integer', label: 'Integer' },
];
vi.mock('~/utils/i18n', () => ({ useSmartText: () => text }));
vi.mock('~/contexts/DslRegistryContext', () => ({
  useDslRegistry: () => ({ ensureLoaded, getEnumOptions }),
}));
vi.mock('~/framework/smart/components/formula/FormulaEditor', () => ({
  FormulaEditor: () => null,
}));
function setup() {
  const onChange = vi.fn();
  const onValidationChange = vi.fn();
  render(
    <FieldCreationForm
      modelPid="test-model"
      modelCode="launch_followup"
      onChange={onChange}
      onValidationChange={onValidationChange}
    />,
  );
  return { onChange, onValidationChange };
}
describe('FieldCreationForm business labels', () => {
  it('requires a readable label separately from the technical code', () => {
    const { onChange, onValidationChange } = setup();
    fireEvent.change(screen.getByPlaceholderText('例如: user_name, email, age'), {
      target: { value: 'customer_name' },
    });
    expect(onValidationChange).toHaveBeenLastCalledWith(false);
    expect(onChange).not.toHaveBeenCalled();
    expect(screen.getByRole('alert')).toHaveTextContent('Enter a business display name');
  });
  it('emits the actual backend uiSchema.label contract and trims label whitespace', () => {
    const { onChange, onValidationChange } = setup();
    fireEvent.change(screen.getByTestId('field-create-display-name'), {
      target: { value: ' Customer name ' },
    });
    fireEvent.change(screen.getByPlaceholderText('例如: user_name, email, age'), {
      target: { value: 'customer_name' },
    });
    expect(onValidationChange).toHaveBeenLastCalledWith(true);
    expect(onChange).toHaveBeenLastCalledWith(
      expect.objectContaining({
        code: 'customer_name',
        uiSchema: { label: 'Customer name' },
        modelPid: 'test-model',
      }),
    );
  });
  it('rejects illegal codes and whitespace labels without producing a valid payload', () => {
    const { onChange, onValidationChange } = setup();
    fireEvent.change(screen.getByTestId('field-create-display-name'), {
      target: { value: 'Customer name' },
    });
    fireEvent.change(screen.getByPlaceholderText('例如: user_name, email, age'), {
      target: { value: 'bad-code' },
    });
    expect(onValidationChange).toHaveBeenLastCalledWith(false);
    expect(onChange).not.toHaveBeenCalled();
    fireEvent.change(screen.getByTestId('field-create-display-name'), { target: { value: '   ' } });
    fireEvent.change(screen.getByPlaceholderText('例如: user_name, email, age'), {
      target: { value: 'valid_code' },
    });
    expect(onValidationChange).toHaveBeenLastCalledWith(false);
    expect(onChange).not.toHaveBeenCalled();
  });
});
