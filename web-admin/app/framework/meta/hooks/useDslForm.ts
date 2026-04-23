/**
 * useDslForm Hook — L1 SDK stable API for DSL form rendering
 *
 * This is the primary facade for rendering DSL-driven forms in any container
 * (BPM drawer, record edit, quick create modal, etc.).
 *
 * Consumers interact with useDslForm + DslFormRenderer only — never with
 * internal hooks like useSchemaLoader or useActionHandler directly.
 *
 * @example
 * ```tsx
 * const form = useDslForm({
 *   pageKey: 'order_new',
 *   initialValues: { status: 'draft' },
 *   onSubmit: async (payload) => { await saveOrder(payload.values); },
 * });
 *
 * return <DslFormRenderer {...form.rendererProps} />;
 * ```
 */

import { useState, useCallback, useRef, useMemo } from 'react';
import { useSchemaLoader, type UseSchemaLoaderOptions } from './useSchemaLoader';
import type { PageContentProps } from '~/framework/meta/profiles/types';

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

/** Permission level for a single field */
export type FieldPermission = 'editable' | 'readonly' | 'hidden';

/** Strategy for combining schema-level and caller-level field permissions */
export type PermissionMergeMode = 'merge' | 'override';

/** Input options for useDslForm */
export interface UseDslFormOptions {
  /** Page key to load schema for (e.g. "order_new") */
  pageKey?: string;
  /** Alternative: model table name + type will be combined into pageKey */
  tableName?: string;
  /** Record ID for edit mode (omit for create) */
  recordId?: string;
  /** Auth token override (uses session token if omitted) */
  token?: string;
  /** Initial field values to pre-populate the form */
  initialValues?: Record<string, any>;
  /** Per-field permission overrides */
  fieldPermissions?: Record<string, FieldPermission>;
  /**
   * How to combine caller fieldPermissions with schema-level permissions.
   * - "merge": caller can only tighten (editable→readonly OK, readonly→editable NO)
   * - "override": caller permissions fully replace schema permissions
   * @default "merge"
   */
  permissionMode?: PermissionMergeMode;
  /** Custom submit handler — replaces the default command-based submission */
  onSubmit?: (payload: FormSubmitPayload) => Promise<void>;
  /** DSL profile name override (defaults to schema.profile or "admin") */
  profile?: string;
  /**
   * When false, skip schema loading entirely.
   * Useful when the form binding is conditional (e.g. no formBinding on a BPM node).
   * @default true
   */
  enabled?: boolean;
}

/** Data passed to the onSubmit callback */
export interface FormSubmitPayload {
  /** Current form field values */
  values: Record<string, any>;
  /** The record ID (present in edit mode) */
  recordId?: string;
  /** The loaded DSL schema */
  schema: any;
  /** The resolved page key */
  pageKey: string;
}

/** Form-level state and methods returned by useDslForm */
export interface UseDslFormReturn {
  // --- Schema loading state ---
  /** The loaded DSL schema (null while loading or if disabled) */
  schema: any | null;
  /** Whether the schema is currently loading */
  loading: boolean;
  /** Schema loading error */
  error: Error | null;

  // --- Form state ---
  /** Current form field values */
  values: Record<string, any>;
  /** Per-field validation errors (fieldCode → error message) */
  errors: Record<string, string>;
  /** Whether any field has been modified since load/reset */
  dirty: boolean;
  /** Whether the form is currently submitting */
  submitting: boolean;

  // --- Form methods ---
  /** Set a single field value */
  setFieldValue: (field: string, value: any) => void;
  /** Get a single field value */
  getFieldValue: (field: string) => any;
  /** Set a validation error for a field */
  setFieldError: (field: string, message: string) => void;
  /** Clear a field error */
  clearFieldError: (field: string) => void;
  /** Submit the form */
  submit: () => Promise<void>;
  /** Reset to initial values and clear errors */
  reset: () => void;
  /** Reload the schema */
  reload: () => Promise<void>;

  // --- Renderer integration ---
  /** Props to spread onto DslFormRenderer / FormPageContent */
  rendererProps: PageContentProps;

  // --- Merged permissions ---
  /** Effective field permissions after merge */
  effectivePermissions: Record<string, FieldPermission>;

  // --- Meta ---
  /** Whether the hook is enabled (schema loading active) */
  enabled: boolean;
  /** Computed page key */
  pageKey: string;
}

// ---------------------------------------------------------------------------
// Permission merge utility
// ---------------------------------------------------------------------------

/**
 * Permission strictness order: hidden > readonly > editable.
 * In "merge" mode the caller can only tighten (move right), never loosen.
 * In "override" mode the caller value wins unconditionally.
 */
const PERMISSION_RANK: Record<FieldPermission, number> = {
  editable: 0,
  readonly: 1,
  hidden: 2,
};

/**
 * Merge two permission maps.
 *
 * @param schemaPerms - Permissions derived from the DSL schema
 * @param callerPerms - Permissions supplied by the hook consumer
 * @param mode - "merge" (tighten only) or "override" (caller wins)
 * @returns Effective permission map
 */
export function mergePermissions(
  schemaPerms: Record<string, FieldPermission>,
  callerPerms: Record<string, FieldPermission>,
  mode: PermissionMergeMode = 'merge',
): Record<string, FieldPermission> {
  if (mode === 'override') {
    return { ...schemaPerms, ...callerPerms };
  }

  // Merge mode: caller can only tighten
  const result = { ...schemaPerms };
  for (const [field, callerPerm] of Object.entries(callerPerms)) {
    const schemaPerm = result[field] ?? 'editable';
    const schemaRank = PERMISSION_RANK[schemaPerm];
    const callerRank = PERMISSION_RANK[callerPerm];
    // Only apply if caller is stricter (higher rank)
    result[field] = callerRank >= schemaRank ? callerPerm : schemaPerm;
  }
  return result;
}

// ---------------------------------------------------------------------------
// Hook implementation
// ---------------------------------------------------------------------------

export function useDslForm(options: UseDslFormOptions): UseDslFormReturn {
  const {
    pageKey: pageKeyOpt,
    tableName,
    recordId,
    token,
    initialValues = {},
    fieldPermissions: callerPermissions = {},
    permissionMode = 'merge',
    onSubmit,
    enabled = true,
  } = options;

  // --- Compute page key ---
  const pageKey = useMemo(() => {
    if (pageKeyOpt) return pageKeyOpt;
    if (tableName) {
      const type = recordId ? 'detail' : 'new';
      return `${tableName}_${type}`;
    }
    return '';
  }, [pageKeyOpt, tableName, recordId]);

  // --- Schema loading (skipped when disabled or no pageKey) ---
  const schemaLoaderOpts: UseSchemaLoaderOptions = useMemo(
    () => (enabled && pageKey ? { pageKey, token } : { pageKey: '__disabled__', token }),
    [enabled, pageKey, token],
  );

  const {
    schema: rawSchema,
    loading: schemaLoading,
    error: schemaError,
    reload,
  } = useSchemaLoader(schemaLoaderOpts);

  // When disabled, override loading/error to idle state
  const schema = enabled ? rawSchema : null;
  const loading = enabled ? schemaLoading : false;
  const error = enabled ? schemaError : null;

  // --- Form state ---
  const [values, setValues] = useState<Record<string, any>>(() => ({ ...initialValues }));
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [dirty, setDirty] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const initialValuesRef = useRef(initialValues);

  // --- Effective permissions ---
  const effectivePermissions = useMemo(() => {
    // Extract schema-level field permissions if available
    const schemaPerms: Record<string, FieldPermission> = {};
    const schemaFields = (schema as any)?.fields as any[] | undefined;
    if (schemaFields) {
      for (const field of schemaFields) {
        if (field.readonly || field.feature?.readonly) {
          schemaPerms[field.fieldCode || field.code] = 'readonly';
        }
        if (field.hidden) {
          schemaPerms[field.fieldCode || field.code] = 'hidden';
        }
      }
    }
    return mergePermissions(schemaPerms, callerPermissions, permissionMode);
  }, [schema, callerPermissions, permissionMode]);

  // --- Form methods ---
  const setFieldValue = useCallback((field: string, value: any) => {
    setValues((prev) => ({ ...prev, [field]: value }));
    setDirty(true);
    // Clear error on edit
    setErrors((prev) => {
      if (!prev[field]) return prev;
      const next = { ...prev };
      delete next[field];
      return next;
    });
  }, []);

  const getFieldValue = useCallback((field: string) => values[field], [values]);

  const setFieldError = useCallback((field: string, message: string) => {
    setErrors((prev) => ({ ...prev, [field]: message }));
  }, []);

  const clearFieldError = useCallback((field: string) => {
    setErrors((prev) => {
      if (!prev[field]) return prev;
      const next = { ...prev };
      delete next[field];
      return next;
    });
  }, []);

  const reset = useCallback(() => {
    setValues({ ...initialValuesRef.current });
    setErrors({});
    setDirty(false);
    setSubmitting(false);
  }, []);

  const submit = useCallback(async () => {
    if (submitting) return;
    if (!onSubmit) {
      console.warn('[useDslForm] No onSubmit handler provided');
      return;
    }

    setSubmitting(true);
    try {
      await onSubmit({
        values,
        recordId,
        schema,
        pageKey,
      });
    } finally {
      setSubmitting(false);
    }
  }, [submitting, onSubmit, values, recordId, schema, pageKey]);

  // --- Renderer props ---
  const rendererProps: PageContentProps = useMemo(
    () => ({
      schema: schema ?? {},
      tableName: tableName || (schema?.modelCode as string) || '',
      recordId,
      token,
      initialValues: Object.keys(initialValues).length > 0 ? initialValues : undefined,
      fieldPermissions:
        Object.keys(effectivePermissions).length > 0 ? effectivePermissions : undefined,
      onSubmitOverride: onSubmit
        ? async (data: Record<string, any>) => {
            await onSubmit({
              values: data,
              recordId,
              schema,
              pageKey,
            });
          }
        : undefined,
    }),
    [schema, tableName, recordId, token, initialValues, effectivePermissions, onSubmit, pageKey],
  );

  return {
    // Schema
    schema,
    loading,
    error,

    // Form state
    values,
    errors,
    dirty,
    submitting,

    // Methods
    setFieldValue,
    getFieldValue,
    setFieldError,
    clearFieldError,
    submit,
    reset,
    reload,

    // Renderer
    rendererProps,

    // Permissions
    effectivePermissions,

    // Meta
    enabled,
    pageKey,
  };
}
