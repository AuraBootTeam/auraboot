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

export interface DslFormFillApi {
  /** Apply a partial map of field code → value to the form. */
  applyFields: (fields: Record<string, unknown>) => void;
}

const NOOP_API: DslFormFillApi = {
  applyFields: () => {
    if (process.env.NODE_ENV === 'development') {
      // eslint-disable-next-line no-console
      console.warn('[DslFormFill] applyFields called outside a DslFormRenderer; noop.');
    }
  },
};

const DslFormFillContext = createContext<DslFormFillApi>(NOOP_API);

export interface DslFormFillProviderProps {
  setFieldValue: ((field: string, value: unknown) => void) | undefined;
  children: React.ReactNode;
}

export function DslFormFillProvider({ setFieldValue, children }: DslFormFillProviderProps) {
  const api = useMemo<DslFormFillApi>(() => {
    if (!setFieldValue) return NOOP_API;
    return {
      applyFields: (fields) => {
        Object.entries(fields).forEach(([fieldCode, value]) => {
          setFieldValue(fieldCode, value);
        });
      },
    };
  }, [setFieldValue]);

  return <DslFormFillContext.Provider value={api}>{children}</DslFormFillContext.Provider>;
}

export function useDslFormFill(): DslFormFillApi {
  return useContext(DslFormFillContext);
}
