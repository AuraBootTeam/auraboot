/**
 * useFieldAutoFill Hook
 *
 * When a REFERENCE field changes, automatically fetches related field values
 * from the source record and sets them on the form.
 *
 * Configuration is read from field.extension.autoFill. Example DSL config:
 *
 * ```json
 * {
 *   "code": "crm_opp_account_id",
 *   "fieldType": "reference",
 *   "extension": {
 *     "autoFill": {
 *       "trigger": "onChange",
 *       "source": {
 *         "modelCode": "crm_account",
 *         "recordIdField": "crm_opp_account_id"
 *       },
 *       "mappings": [
 *         { "sourceField": "crm_acc_industry", "targetField": "crm_opp_industry" },
 *         { "sourceField": "crm_acc_city",     "targetField": "crm_opp_city"     }
 *       ]
 *     }
 *   }
 * }
 * ```
 *
 * Only fills a target field when it is currently empty, to avoid overwriting
 * intentional user input.
 */

import { useEffect, useRef } from 'react';
import { get } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

/**
 * AutoFill configuration stored in field.extension.autoFill
 */
export interface AutoFillConfig {
  /** Trigger event — currently only 'onChange' is supported */
  trigger: 'onChange';
  /** Source record to look up */
  source: {
    /** Model code of the referenced entity, e.g. "crm_account" */
    modelCode: string;
    /** Field code whose value is the record ID to look up */
    recordIdField: string;
  };
  /** Field value mappings: sourceField on the referenced model → targetField on this form */
  mappings: Array<{
    sourceField: string;
    targetField: string;
  }>;
}

/**
 * Minimal field config shape expected by this hook.
 * Compatible with FieldConfig from ~/types/page-schema.ts and DSL field objects.
 */
export interface AutoFillFieldConfig {
  code: string;
  fieldType?: string;
  /** Extension properties — autoFill config lives here */
  extension?: {
    autoFill?: AutoFillConfig;
    [key: string]: unknown;
  };
  [key: string]: unknown;
}

// ---------------------------------------------------------------------------
// Hook
// ---------------------------------------------------------------------------

/**
 * useFieldAutoFill
 *
 * Monitors form values for changes to REFERENCE fields that have an autoFill
 * configuration. When such a field changes to a non-empty value, calls
 * GET /api/meta/auto-fill to retrieve the source record's field values and
 * populates the target fields (only if they are currently empty).
 *
 * @param fields       - All field configs for the current form
 * @param formValues   - Current snapshot of form values (keyed by field code)
 * @param setFormValue - Callback to set a single field value in the form
 */
export function useFieldAutoFill(
  fields: AutoFillFieldConfig[],
  formValues: Record<string, unknown>,
  setFormValue: (field: string, value: unknown) => void,
): void {
  // Track previous values to detect actual changes (avoid re-firing on unrelated renders)
  const prevValuesRef = useRef<Record<string, unknown>>({});

  useEffect(() => {
    const prevValues = prevValuesRef.current;

    // Find REFERENCE fields that have autoFill config and whose value has changed
    const triggerFields = fields.filter((f) => {
      if (!f.extension?.autoFill) return false;
      const cfg = f.extension.autoFill;
      if (cfg.trigger !== 'onChange') return false;
      const current = formValues[f.code];
      const prev = prevValues[f.code];
      return current !== prev && current != null && current !== '';
    });

    if (triggerFields.length === 0) {
      prevValuesRef.current = { ...formValues };
      return;
    }

    // Process each triggered field
    const processAutoFill = async () => {
      for (const field of triggerFields) {
        const cfg = field.extension!.autoFill!;
        const recordId = formValues[cfg.source.recordIdField];

        if (recordId == null || recordId === '') continue;

        const sourceFields = cfg.mappings.map((m) => m.sourceField).join(',');

        try {
          const params = new URLSearchParams({
            modelCode: cfg.source.modelCode,
            recordId: String(recordId),
            fields: sourceFields,
          });

          const result = await get<Record<string, unknown>>(
            `/api/meta/auto-fill?${params.toString()}`,
          );

          if (!ResultHelper.isSuccess(result) || !result.data) {
            continue;
          }

          const sourceValues = result.data;

          // Apply mappings — only fill target if currently empty
          for (const mapping of cfg.mappings) {
            const targetValue = formValues[mapping.targetField];
            const isEmpty = targetValue == null || targetValue === '';
            if (isEmpty && mapping.sourceField in sourceValues) {
              setFormValue(mapping.targetField, sourceValues[mapping.sourceField]);
            }
          }
        } catch (err) {
          // Non-fatal: log and continue so form remains usable
          console.warn(
            `[useFieldAutoFill] Failed to fetch auto-fill values for field ${field.code}:`,
            err,
          );
        }
      }
    };

    processAutoFill();

    // Update previous values snapshot after processing
    prevValuesRef.current = { ...formValues };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [formValues]);
  // Note: `fields` and `setFormValue` are intentionally omitted from the dep array.
  // `fields` is schema config (stable across renders) and including it would cause
  // infinite loops when the form re-renders. `setFormValue` should be memoized by
  // the caller (e.g. via useCallback) if referential stability is needed.
}

export default useFieldAutoFill;
