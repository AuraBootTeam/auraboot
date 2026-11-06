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
  const [receipt, setReceipt] = useState<{
    identity: string;
    result: FormFillResult;
    placement?: 'form' | 'chat';
  } | null>(null);
  const receiptRef = useRef(receipt);
  const undo = useCallback(() => {
    const current = latest.current;
    const previous = receiptRef.current;
    if (!previous || previous.identity !== current.identity) return;
    const next = undoFormFill(current.values, previous.result.applied);
    current.values = next;
    current.setValues(next);
    receiptRef.current = null;
    setReceipt(null);
  }, []);
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
      apply: (patch, reviews, placement = 'form') => {
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
        const previous = receiptRef.current;
        const nextReceipt = {
          identity,
          placement,
          result: {
            ...result,
            applied: {
              ...(placement !== 'chat' && previous?.identity === identity
                ? previous.result.applied
                : {}),
              ...result.applied,
            },
          },
        };
        receiptRef.current = nextReceipt;
        setReceipt(nextReceipt);
        return result;
      },
      undo,
    };
  }, [identity, modelCode, undo]);
  return {
    target,
    receipt: receipt?.identity === identity && receipt.placement !== 'chat' ? receipt.result : null,
    undo,
  };
}
