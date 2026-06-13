/**
 * DslFormFillContext — exposes form mutation API to DSL block renderers.
 *
 * Block renderers normally only see {@code runtime} via their props; field
 * values live in a private hook (useDslForm). For blocks that need to mutate
 * field values (e.g. an AI fill banner that turns NL into a field map), this
 * Context provides a stable handle without coupling the block to useDslForm
 * internals.
 *
 * P1' minimum surface: applyFields(map). P2' may extend with getValue /
 * getValues / validate. Provider lives inside DslFormRenderer; consumers call
 * useDslFormFill() and tolerate a no-op when used outside a form.
 */
import React, { createContext, useContext, useMemo } from 'react';
import { partitionFieldsByLock } from './aiLockedFields';

export interface DslFormFillApi {
  /**
   * Apply a partial map of field code → value to the form. Field codes marked
   * AI-locked (see {@link DslFormFillApi.lockedFields}) are skipped — an AI fill
   * must never overwrite a locked field.
   */
  applyFields: (fields: Record<string, unknown>) => void;
  /**
   * Field codes the form has marked AI-locked. Consumers (e.g. the ai-fill
   * banner) forward these to the backend so the server skips them too.
   */
  lockedFields: string[];
}

const NOOP_API: DslFormFillApi = {
  applyFields: () => {
    if (process.env.NODE_ENV === 'development') {
      // eslint-disable-next-line no-console
      console.warn('[DslFormFill] applyFields called outside a DslFormRenderer; noop.');
    }
  },
  lockedFields: [],
};

const DslFormFillContext = createContext<DslFormFillApi>(NOOP_API);

export interface DslFormFillProviderProps {
  setFieldValue: ((field: string, value: unknown) => void) | undefined;
  /** Field codes marked AI-locked; applyFields skips these. */
  lockedFields?: string[];
  children: React.ReactNode;
}

export function DslFormFillProvider({
  setFieldValue,
  lockedFields,
  children,
}: DslFormFillProviderProps) {
  // Serialize the locked set so the memo only recomputes when its contents change.
  const lockedKey = (lockedFields ?? []).join(' ');
  const api = useMemo<DslFormFillApi>(() => {
    const locked = lockedKey ? lockedKey.split(' ') : [];
    if (!setFieldValue) return { ...NOOP_API, lockedFields: locked };
    return {
      lockedFields: locked,
      applyFields: (fields) => {
        const { applied } = partitionFieldsByLock(fields, locked);
        Object.entries(applied).forEach(([fieldCode, value]) => {
          setFieldValue(fieldCode, value);
        });
      },
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [setFieldValue, lockedKey]);

  return <DslFormFillContext.Provider value={api}>{children}</DslFormFillContext.Provider>;
}

export function useDslFormFill(): DslFormFillApi {
  return useContext(DslFormFillContext);
}
