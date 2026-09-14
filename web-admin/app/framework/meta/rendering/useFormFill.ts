import { useCallback, useMemo, useRef, useState } from 'react';
import {
  applyFormFill,
  undoFormFill,
  type FormFillField,
  type FormFillResult,
  type FormFillTarget,
} from './formFill';

export function useFormFill(
  identity: string,
  modelCode: string,
  fields: FormFillField[],
  values: Record<string, unknown>,
  setValues: (values: Record<string, unknown>) => void,
  ready: boolean,
) {
  const latest = useRef({ identity, fields, values, setValues, ready });
  latest.current = { identity, fields, values, setValues, ready };
  const [receipt, setReceipt] = useState<{ identity: string; result: FormFillResult } | null>(null);
  const target = useMemo<FormFillTarget>(() => {
    const targetId = crypto.randomUUID();
    return {
      snapshot: () => ({
        targetId,
        modelCode,
        fields: latest.current.ready ? latest.current.fields : [],
        currentDate: new Intl.DateTimeFormat('en-CA', {
          year: 'numeric',
          month: '2-digit',
          day: '2-digit',
        }).format(new Date()),
        timeZone: Intl.DateTimeFormat().resolvedOptions().timeZone,
      }),
      apply: (patch, reviews) => {
        const current = latest.current;
        if (current.identity !== identity) throw new Error('ai.fill.target_changed');
        const result = applyFormFill(
          current.values,
          current.ready ? current.fields : [],
          patch,
          reviews,
        );
        current.values = result.values;
        current.setValues(result.values);
        setReceipt((previous) => ({
          identity,
          result: {
            ...result,
            applied: {
              ...(previous?.identity === identity ? previous.result.applied : {}),
              ...result.applied,
            },
          },
        }));
        return result;
      },
    };
  }, [identity, modelCode]);
  const undo = useCallback(() => {
    if (!receipt || receipt.identity !== identity) return;
    const next = undoFormFill(latest.current.values, receipt.result.applied);
    latest.current.values = next;
    latest.current.setValues(next);
    setReceipt(null);
  }, [receipt, identity]);
  return { target, receipt: receipt?.identity === identity ? receipt.result : null, undo };
}
