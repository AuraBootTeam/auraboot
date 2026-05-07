import { forwardRef, useImperativeHandle, useRef, useState } from 'react';
import { useI18n } from '~/contexts/I18nContext';

export interface InputAreaHandle {
  focus: () => void;
}

export interface InputAreaProps {
  onSubmit: (text: string) => void;
  disabled?: boolean;
}

export const InputArea = forwardRef<InputAreaHandle, InputAreaProps>(function InputArea(
  { onSubmit, disabled = false },
  ref,
) {
  const { t } = useI18n();
  const [value, setValue] = useState('');
  const taRef = useRef<HTMLTextAreaElement | null>(null);

  useImperativeHandle(ref, () => ({
    focus: () => taRef.current?.focus(),
  }));

  const placeholder = t(
    'aurabot.shell.input.placeholder',
    undefined,
    'Ask AuraBot, press Cmd+Enter to send',
  );
  const sendLabel = t('aurabot.shell.input.send', undefined, 'Send');

  const trimmed = value.trim();
  const canSend = !disabled && trimmed.length > 0;

  const submit = () => {
    if (!canSend) return;
    onSubmit(trimmed);
    setValue('');
  };

  return (
    <div className="border-t border-gray-200 bg-white px-3 py-2 dark:border-gray-700 dark:bg-gray-900">
      <div className="flex items-end gap-2">
        <textarea
          ref={taRef}
          value={value}
          onChange={(e) => setValue(e.target.value)}
          onKeyDown={(e) => {
            // Cmd/Ctrl + Enter sends. Plain Enter inserts newline so multi-line
            // prompts stay easy to type.
            if ((e.metaKey || e.ctrlKey) && e.key === 'Enter') {
              e.preventDefault();
              submit();
            }
          }}
          placeholder={placeholder}
          disabled={disabled}
          aria-label={placeholder}
          data-aurabot-input
          rows={2}
          className="min-h-[44px] flex-1 resize-none rounded-md border border-gray-300 bg-white px-3 py-2 text-sm text-gray-900 outline-none placeholder:text-gray-400 focus:border-blue-500 disabled:cursor-not-allowed disabled:opacity-50 dark:border-gray-600 dark:bg-gray-800 dark:text-gray-100 dark:placeholder:text-gray-500"
        />
        <button
          type="button"
          onClick={submit}
          disabled={!canSend}
          data-aurabot-send
          className="rounded-md bg-blue-600 px-3 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:cursor-not-allowed disabled:opacity-50"
        >
          {sendLabel}
        </button>
      </div>
    </div>
  );
});
