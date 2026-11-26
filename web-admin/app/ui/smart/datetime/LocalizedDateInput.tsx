import { useId, useRef, useState, type FocusEvent, type InputHTMLAttributes, type Ref } from 'react';
import { CalendarDays } from 'lucide-react';
import { cn } from '~/utils/cn';

interface LocalizedDateInputProps
  extends Omit<InputHTMLAttributes<HTMLInputElement>, 'type' | 'value' | 'onChange'> {
  inputRef: Ref<HTMLInputElement>;
  pickerType: 'date' | 'datetime-local';
  value: string;
  onValueChange: (value: string) => void;
}

/** Chinese date editing with the native calendar and unchanged HTML date values. */
export function LocalizedDateInput({
  inputRef,
  pickerType,
  value,
  onValueChange,
  onBlur,
  min,
  max,
  step,
  disabled,
  readOnly,
  className,
  placeholder,
  'aria-describedby': describedBy,
  'aria-invalid': externalInvalid,
  ...inputProps
}: LocalizedDateInputProps) {
  const [draft, setDraft] = useState<{ source: string; text: string } | null>(null);
  const [touched, setTouched] = useState(false);
  const pickerRef = useRef<HTMLInputElement>(null);
  const errorId = useId();
  const invalidDraft = draft?.source === value ? draft.text : undefined;
  const invalid = invalidDraft !== undefined && !readOnly && !disabled;
  const showError = invalid && touched && !externalInvalid;
  const pattern = pickerType === 'date' ? 'YYYY-MM-DD' : 'YYYY-MM-DD HH:mm';
  const error = `请输入有效的日期${pickerType === 'date' ? '' : '和时间'}（${pattern}）。`;

  const update = (text: string) => {
    const picker = pickerRef.current;
    if (!picker) return;
    const candidate = text.trim().replace(' ', 'T');
    picker.value = candidate;
    const usable = !candidate || (
      Boolean(picker.value) &&
      !picker.validity.rangeUnderflow &&
      !picker.validity.rangeOverflow &&
      (step == null || !picker.validity.stepMismatch)
    );
    if (!usable) {
      picker.value = value;
      setDraft({ source: value, text });
      return;
    }
    setDraft(null);
    onValueChange(picker.value);
  };

  const openPicker = () => {
    const picker = pickerRef.current;
    if (!picker) return;
    try {
      picker.showPicker?.();
    } catch {
      // The text input remains available when the browser blocks native pickers.
    }
  };
  const blur = (event: FocusEvent<HTMLInputElement>) => {
    setTouched(true);
    onBlur?.(event);
  };

  return (
    <>
      <div className="relative">
        <input
          {...inputProps}
          ref={inputRef}
          type="text"
          disabled={disabled}
          readOnly={readOnly}
          value={invalid ? invalidDraft : value.replace('T', ' ')}
          placeholder={placeholder || pattern}
          onChange={(event) => update(event.currentTarget.value)}
          onBlur={blur}
          aria-invalid={externalInvalid || showError || undefined}
          aria-describedby={[describedBy, showError && errorId].filter(Boolean).join(' ') || undefined}
          data-temporal-error={invalid ? error : undefined}
          className={cn(className, 'pl-10')}
        />
        <input
          ref={pickerRef}
          aria-hidden="true"
          type={pickerType}
          min={min}
          max={max}
          step={step}
          tabIndex={-1}
          disabled={disabled || readOnly}
          value={value}
          onChange={(event) => update(event.currentTarget.value)}
          className="pointer-events-none absolute top-0 left-0 h-8 w-8 opacity-0"
        />
        <button
          type="button"
          aria-label="打开日历"
          disabled={disabled || readOnly}
          onClick={openPicker}
          className="text-text-3 hover:text-text focus-visible:shadow-focus absolute inset-y-0 left-0 grid w-10 place-items-center rounded-control disabled:opacity-50"
        >
          <CalendarDays aria-hidden="true" className="size-4" />
        </button>
      </div>
      {showError && <p id={errorId} className="text-status-red mt-1 text-xs">{error}</p>}
    </>
  );
}
