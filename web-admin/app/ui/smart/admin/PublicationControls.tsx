import { cloneElement, useEffect, useId, useState, type ReactElement, type ChangeEventHandler, type FormEventHandler } from 'react';
import { useI18n } from '~/contexts/I18nContext';

type Control = HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement;
export type LabeledControl = ReactElement<{ id?: string; 'aria-describedby'?: string; 'aria-invalid'?: boolean | 'true' | 'false';
  onInvalid?: FormEventHandler<Control>; onChange?: ChangeEventHandler<Control> }>;
export function PublicationField({ name, child, help }: { name: string; child: LabeledControl; help?: string }) {
  const id = useId();
  const { t, locale } = useI18n();
  const [validationProblem, setValidationProblem] = useState<'required' | 'invalid' | null>(null);
  useEffect(() => {
    if (!validationProblem) return;
    // A sibling value can change this control's native min/max constraints.
    const control = document.getElementById(id) as Control | null;
    if (control?.validity.valid) setValidationProblem(null);
  });
  const validationError = validationProblem ? t(`openPlatform.controls.${validationProblem}`, undefined,
    locale.startsWith('zh') ? (validationProblem === 'required' ? `请填写${name}。` : `请检查${name}的输入格式或范围。`)
      : (validationProblem === 'required' ? `Enter ${name}.` : `Check the format or range of ${name}.`)) : '';
  const describedBy = [child.props['aria-describedby'], help ? `${id}-help` : undefined,
    validationError ? `${id}-error` : undefined].filter(Boolean).join(' ') || undefined;
  const invalid: FormEventHandler<Control> = event => {
    child.props.onInvalid?.(event);
    const control = event.currentTarget;
    setValidationProblem(event.defaultPrevented ? null : control.validity.valueMissing ? 'required' : 'invalid');
    event.preventDefault();
    const first = control.form?.querySelector<Control>('input:invalid, select:invalid, textarea:invalid');
    (first || control).focus();
  };
  const changed: ChangeEventHandler<Control> = event => {
    child.props.onChange?.(event);
    if (event.currentTarget.validity.valid) setValidationProblem(null);
  };
  return <div className="space-y-1 text-sm text-text"><label htmlFor={id} className="block">{name}</label>
    {cloneElement(child, { id, 'aria-describedby': describedBy, 'aria-invalid': validationError ? true : child.props['aria-invalid'],
      onInvalid: invalid, onChange: changed })}
    {help && <p id={`${id}-help`} className="text-xs text-text-2">{help}</p>}
    {validationError && <p id={`${id}-error`} role="alert" className="text-sm text-danger">{validationError}</p>}</div>;
}
