import { DynamicField } from '~/routes/_shared/dynamic-route-utils';
import type { FieldConfig } from '~/framework/meta/schemas/types';
import type { RecordData } from './detailPageTypes';

/**
 * FallbackDetailView - fallback for schemas without structured blocks
 */
export function FallbackDetailView({
  schema,
  recordData,
  locale,
}: {
  schema: any;
  recordData: RecordData;
  locale: string;
}) {
  // Try to extract fields from any block
  const fields: FieldConfig[] = [];
  if (schema?.blocks) {
    for (const block of schema.blocks) {
      if (block.fields) {
        fields.push(...block.fields);
      }
    }
  }

  if (fields.length === 0) return null;

  return (
    <div className="grid grid-cols-1 gap-6 md:grid-cols-2">
      {fields.map((field: FieldConfig) => (
        <div
          key={field.field}
          data-testid={`form-field-${field.field}`}
          className={field.span === 2 ? 'md:col-span-2' : ''}
        >
          <DynamicField
            field={field}
            value={recordData ? recordData[field.field] : undefined}
            displayValue={recordData ? recordData[`${field.field}_display`] : undefined}
            onChange={() => {}}
            readOnly={true}
            locale={locale}
          />
        </div>
      ))}
    </div>
  );
}
