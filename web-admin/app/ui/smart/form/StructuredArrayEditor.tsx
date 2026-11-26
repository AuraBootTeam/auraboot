import { useSmartText } from '~/utils/i18n';
import { Button } from '~/ui/ui/button';
import type { FormFieldProps } from '~/framework/smart/schema/smart-components';
import Input from './Input';
import Select from './Select';
import {
  readStructuredArray,
  type StructuredArrayColumn,
  type StructuredArraySchema,
} from './structuredArrayValue';

interface Props extends FormFieldProps, StructuredArraySchema {
  readOnly?: boolean;
  'aria-label'?: string;
  'aria-describedby'?: string;
  'aria-invalid'?: boolean;
}

export default function StructuredArrayEditor({
  name, value, onChange, disabled, readOnly, context, columns, item, addLabel,
  'aria-label': label, 'aria-describedby': describedBy, 'aria-invalid': invalid,
}: Props) {
  const st = useSmartText();
  const rows = readStructuredArray(value);
  const locked = Boolean(disabled || readOnly);
  const fieldColumns = item ? [item] : columns ?? [];

  const text = (chinese: string, english: string) =>
    st({ 'zh-CN': chinese, en: english });

  if (!rows) {
    return <p id={name} role="alert">{text('无法读取已保存的列表。', 'The saved list could not be loaded.')}</p>;
  }

  const update = (index: number, column: StructuredArrayColumn, next: unknown) => {
    onChange?.(rows.map((row, i) => i !== index ? row : item ? next : {
      ...(row !== null && typeof row === 'object' && !Array.isArray(row) ? row : {}),
      [column.key]: next,
    }));
  };

  const move = (index: number, offset: number) => {
    const next = [...rows];
    [next[index], next[index + offset]] = [next[index + offset], next[index]];
    onChange?.(next);
  };

  const add = () => {
    const newRow = item ? item.defaultValue ?? '' : Object.fromEntries(
      fieldColumns.map((column) => [column.key, column.defaultValue ?? '']),
    );
    onChange?.([...rows, newRow]);
  };

  return (
    <div id={name} role="group" aria-label={label} aria-describedby={describedBy} aria-invalid={invalid}
      className="space-y-3" data-testid={`structured-array-${name}`}>
      {rows.map((row, index) => (
        <div key={index} className="border-border rounded-control border p-3"
          data-testid={`structured-array-${name}-row`}>
          <div className="flex flex-col gap-3 sm:flex-row">
            {fieldColumns.map((column) => {
              const columnValue = item ? row : (row as Record<string, unknown> | null)?.[column.key];
              const props = {
                name: `${name}.${index}.${column.key}`,
                label: st(column.label),
                value: columnValue == null ? '' : String(columnValue),
                disabled: locked,
                required: column.required,
                context,
                onChange: (next: unknown) => update(index, column, next),
              };
              const options = column.options?.map((option) => ({
                value: option.value,
                label: st(option.label),
              }));
              if (options && props.value && !options.some((option) => option.value === props.value)) {
                options.push({ value: props.value, label: props.value });
              }
              return (
                <div key={column.key} className="min-w-0 flex-1">
                  {column.dataSource || options ? (
                    <Select {...props} options={options} dataSource={column.dataSource} />
                  ) : <Input {...props} />}
                </div>
              );
            })}
          </div>
          {!locked && (
            <div className="mt-2 flex flex-wrap gap-2">
              {item && <>
                <Button type="button" variant="outline" size="sm" disabled={index === 0}
                  onClick={() => move(index, -1)}>{text('上移', 'Move up')}</Button>
                <Button type="button" variant="outline" size="sm" disabled={index === rows.length - 1}
                  onClick={() => move(index, 1)}>{text('下移', 'Move down')}</Button>
              </>}
              <Button type="button" variant="ghost" size="sm"
                onClick={() => onChange?.(rows.filter((_, i) => i !== index))}>
                {text('移除', 'Remove')}
              </Button>
            </div>
          )}
        </div>
      ))}
      {!locked && (
        <Button type="button" variant="outline" size="sm" onClick={add}>
          {addLabel ? st(addLabel) : text('添加一项', 'Add an item')}
        </Button>
      )}
    </div>
  );
}
