import { useEffect, useId, useRef, useState } from 'react';
import { Check, ChevronDown } from 'lucide-react';
import { Popover, PopoverAnchor, PopoverContent } from '~/ui/ui/popover';
import { FieldActionButton } from '~/ui/ui/field-action-button';
import { fieldControlBase, fieldFocusStyles } from '~/ui/ui/field-styles';
import { useSmartText } from '~/utils/i18n';
import { cn } from '~/utils/cn';

interface Props {
  name: string;
  label?: string;
  value: string;
  options: Array<{ label: any; value: any; disabled?: boolean }>;
  placeholder: string;
  disabled?: boolean;
  loading: boolean;
  error: string | null;
  invalid?: boolean;
  required?: boolean;
  clearable: boolean;
  clearLabel: string;
  className?: string;
  onOpen: () => void;
  onChange: (value: string) => void;
  onBlur: () => void;
  onClear: () => void;
  canCreateNew: boolean;
  createLabel: React.ReactNode;
  onCreateNew?: () => void;
}

/** Editable single choice: query text never becomes the submitted field value. */
export function EditableSelect(props: Props) {
  const st = useSmartText();
  const id = useId();
  const input = useRef<HTMLInputElement>(null);
  const anchor = useRef<HTMLDivElement>(null);
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState('');
  const [active, setActive] = useState(-1);
  const expanded = open && !props.disabled;
  const selected = props.options.find((option) => String(option.value) === props.value);
  const needle = query.trim().toLocaleLowerCase();
  const options = props.options.filter(
    (option) =>
      !needle ||
      String(option.label ?? '')
        .toLocaleLowerCase()
        .includes(needle) ||
      String(option.value ?? '')
        .toLocaleLowerCase()
        .includes(needle),
  );
  const choices = [
    ...options.map((option) => ({
      value: String(option.value),
      label: option.label,
      disabled: option.disabled,
      create: false,
    })),
    ...(props.canCreateNew
      ? [{ value: '', label: props.createLabel, disabled: false, create: true }]
      : []),
  ];
  const activeChoice = choices[active];

  useEffect(() => {
    if (props.disabled) {
      setOpen(false);
      setQuery('');
      setActive(-1);
    }
  }, [props.disabled]);
  useEffect(() => {
    if (active >= 0)
      document.getElementById(`${id}-option-${active}`)?.scrollIntoView?.({ block: 'nearest' });
  }, [active, id]);

  useEffect(() => {
    setActive(-1);
  }, [props.options, props.loading, props.error]);

  function changeOpen(next: boolean) {
    if (props.disabled) return;
    if (next && !open) props.onOpen();
    setOpen(next);
    setQuery('');
    setActive(-1);
  }
  function choose(index: number) {
    const option = choices[index];
    if (!option || option.disabled || props.loading || props.error) return;
    changeOpen(false);
    if (option.create) props.onCreateNew?.();
    else props.onChange(option.value);
  }
  function move(direction: number) {
    if (!expanded) changeOpen(true);
    for (let step = 1; step <= choices.length; step++) {
      const start = expanded && active >= 0 ? active : direction > 0 ? -1 : 0;
      const index = (start + direction * step + choices.length) % choices.length;
      if (!choices[index].disabled) {
        setActive(index);
        break;
      }
    }
  }

  return (
    <Popover open={expanded} onOpenChange={changeOpen}>
      <PopoverAnchor asChild>
        <div ref={anchor} className="relative">
          <input
            ref={input}
            id={props.name}
            type="text"
            role="combobox"
            autoComplete="off"
            aria-label={props.label || undefined}
            aria-autocomplete="list"
            aria-expanded={expanded}
            aria-controls={expanded ? `${id}-list` : undefined}
            aria-activedescendant={
              expanded && activeChoice && !props.loading && !props.error
                ? `${id}-option-${active}`
                : undefined
            }
            aria-required={props.required}
            aria-invalid={props.invalid || undefined}
            aria-busy={props.loading}
            data-testid={`select-trigger-${props.name}`}
            disabled={props.disabled}
            value={expanded ? query : String(selected?.label ?? '')}
            placeholder={expanded && selected ? String(selected.label) : props.placeholder}
            className={cn(
              fieldControlBase,
              fieldFocusStyles,
              'bg-panel text-text placeholder:text-text-3 w-full pr-16 disabled:cursor-not-allowed disabled:opacity-50',
              props.invalid && 'border-red-500 focus:ring-red-500',
              props.className,
            )}
            onClick={() => {
              if (!expanded) changeOpen(true);
            }}
            onChange={(event) => {
              if (!expanded) changeOpen(true);
              setQuery(event.target.value);
              setActive(-1);
            }}
            onBlur={() => {
              changeOpen(false);
              props.onBlur();
            }}
            onKeyDown={(event) => {
              if (event.nativeEvent.isComposing || event.keyCode === 229) return;
              if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
                event.preventDefault();
                move(event.key === 'ArrowDown' ? 1 : -1);
              } else if (event.key === 'Enter' && expanded) {
                event.preventDefault();
                choose(active);
              } else if (event.key === 'Escape' && expanded) {
                event.preventDefault();
                event.stopPropagation();
                changeOpen(false);
              } else if (event.key === 'Tab') changeOpen(false);
            }}
          />
          {props.clearable && props.value && !props.disabled && (
            <FieldActionButton
              type="button"
              iconOnly
              aria-label={props.clearLabel}
              className="absolute top-1/2 right-7 -translate-y-1/2"
              onMouseDown={(event) => event.preventDefault()}
              onClick={() => {
                props.onClear();
                setQuery('');
                setActive(-1);
                input.current?.focus();
              }}
            >
              <span aria-hidden="true">×</span>
            </FieldActionButton>
          )}
          <button
            type="button"
            tabIndex={-1}
            aria-label={st({ 'zh-CN': '展开选项', en: 'Open options' })}
            disabled={props.disabled}
            className="text-text-3 absolute inset-y-0 right-0 flex w-8 items-center justify-center disabled:opacity-50"
            onMouseDown={(event) => event.preventDefault()}
            onClick={() => {
              input.current?.focus();
              changeOpen(!expanded);
            }}
          >
            <ChevronDown className="h-4 w-4" aria-hidden="true" />
          </button>
        </div>
      </PopoverAnchor>
      <PopoverContent
        align="start"
        role="presentation"
        className="!w-[var(--radix-popover-trigger-width)] !p-1"
        onOpenAutoFocus={(event) => event.preventDefault()}
        onCloseAutoFocus={(event) => event.preventDefault()}
        onInteractOutside={(event) => {
          if (anchor.current?.contains(event.target as Node)) event.preventDefault();
        }}
      >
        <div
          id={`${id}-list`}
          role="listbox"
          aria-label={props.label}
          className="max-h-60 overflow-y-auto"
        >
          {props.loading || props.error ? (
            <div role="status" className="text-text-3 px-3 py-2 text-sm">
              {props.error
                ? st({
                    'zh-CN': '加载失败，请关闭后重新打开',
                    en: 'Unable to load. Close and reopen to retry.',
                  })
                : st({ 'zh-CN': '加载中…', en: 'Loading…' })}
            </div>
          ) : (
            <>
              {options.length === 0 && (
                <div role="status" className="text-text-3 px-3 py-2 text-sm">
                  {st({ 'zh-CN': '无匹配结果', en: 'No results' })}
                </div>
              )}
              {choices.map((option, index) => (
                <div
                  key={option.create ? 'create' : option.value}
                  id={`${id}-option-${index}`}
                  role="option"
                  data-value={option.create ? undefined : option.value}
                  aria-selected={!option.create && option.value === props.value}
                  aria-disabled={option.disabled || undefined}
                  data-testid={option.create ? `select-create-new-${props.name}` : undefined}
                  className={cn(
                    'relative cursor-pointer rounded-sm py-2 pr-8 pl-3 text-sm break-words',
                    active === index && 'bg-hover',
                    option.create && 'text-accent font-medium',
                    option.disabled && 'cursor-not-allowed opacity-50',
                  )}
                  onMouseDown={(event) => event.preventDefault()}
                  onPointerMove={() => {
                    if (!option.disabled) setActive(index);
                  }}
                  onClick={() => choose(index)}
                >
                  {option.label}
                  {!option.create && option.value === props.value && (
                    <Check aria-hidden="true" className="absolute top-2 right-2 h-4 w-4" />
                  )}
                </div>
              ))}
            </>
          )}
        </div>
      </PopoverContent>
    </Popover>
  );
}
