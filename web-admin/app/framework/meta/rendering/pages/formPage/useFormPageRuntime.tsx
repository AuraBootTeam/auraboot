import { useMemo, useEffect } from 'react';
import { useUser, usePermissions } from '~/contexts/AuthContext';
import { usePageRuntime } from '~/framework/meta/rendering/pages/hooks/usePageRuntime';
import { createExpressionContext } from '~/framework/meta/runtime/expression/context';
import { fetchResult } from '~/shared/services/http-client';
import { useModelCapabilities } from '~/shared/hooks/useModelCapabilities';
import type { useFormPageState } from './useFormPageState';

type UseFormPageRuntimeArgs = Pick<
  ReturnType<typeof useFormPageState>,
  | 'schema'
  | 'isEditMode'
  | 'searchParams'
  | 'token'
  | 'formData'
  | 'recordPid'
  | 'tableName'
  | 'dirtyFieldsRef'
  | 'setFormData'
>;

export function useFormPageRuntime({
  schema,
  isEditMode,
  searchParams,
  token,
  formData,
  recordPid,
  tableName,
  dirtyFieldsRef,
  setFormData,
}: UseFormPageRuntimeArgs) {
  // Flat field-name set from schema (form-section blocks).
  // Used for generic URL aliasing (e.g. ?modelCode=xxx → model_code) and for
  // deciding whether kind × capability validation applies to this form.
  const schemaFieldNames = useMemo(() => {
    const names = new Set<string>();
    const blocks = (schema as any)?.blocks ?? [];
    for (const b of blocks) {
      if (b?.blockType === 'form-section' && Array.isArray(b.fields)) {
        for (const f of b.fields) {
          if (f?.field) names.add(String(f.field));
        }
      }
    }
    return names;
  }, [schema]);

  // URL-based default values for create mode (e.g. ?dv.crm_qt_opportunity_id=01xxx)
  // Also supports a short form aliasing commonly-used query params to DB columns
  // when the form has a matching field:
  //   ?modelCode=xxx  → model_code (when form has `model_code` field)
  const urlDefaultValues = useMemo(() => {
    if (isEditMode) return {} as Record<string, string>;
    const defaults: Record<string, string> = {};
    for (const [key, value] of searchParams.entries()) {
      if (key.startsWith('dv.') && value) {
        defaults[key.substring(3)] = value;
      }
    }
    const modelCodeParam = searchParams.get('modelCode');
    if (modelCodeParam && schemaFieldNames.has('model_code') && !defaults.model_code) {
      defaults.model_code = modelCodeParam;
    }
    return defaults;
  }, [isEditMode, searchParams, schemaFieldNames]);

  // Field-config `defaultValue` for create mode (e.g. a select that should
  // pre-select an option). Lower precedence than URL defaults and user input.
  const schemaDefaultValues = useMemo(() => {
    if (isEditMode) return {} as Record<string, any>;
    const defaults: Record<string, any> = {};
    const blocks = (schema as any)?.blocks ?? [];
    for (const b of blocks) {
      if (b?.blockType === 'form-section' && Array.isArray(b.fields)) {
        for (const f of b.fields) {
          if (f?.field && f.defaultValue !== undefined && f.defaultValue !== null) {
            defaults[String(f.field)] = f.defaultValue;
          }
        }
      }
    }
    return defaults;
  }, [isEditMode, schema]);

  // Track whether modelCode was seeded from URL so we can show a hint.
  const urlSeededModelCode = useMemo(() => {
    if (isEditMode) return null;
    const mc = searchParams.get('modelCode');
    return mc && schemaFieldNames.has('model_code') ? mc : null;
  }, [isEditMode, searchParams, schemaFieldNames]);

  // Forms that edit both a model_code and a kind field (e.g. page_schema_form)
  // must validate that the selected kind is compatible with the model's
  // capabilities before submit. Fetch capabilities lazily when both fields
  // are present and the user has chosen a model code.
  const hasModelCodeAndKindFields =
    schemaFieldNames.has('model_code') && schemaFieldNames.has('kind');

  // Determine mode string for expression context
  const mode = isEditMode ? 'edit' : 'create';

  // Use unified page runtime hook (replaces useDynamicPageSetup)
  const { runtime, dataSourceManager, t, locale, navigate } = usePageRuntime(schema, {
    token: token || undefined,
    additionalContext: {
      mode,
      form: {
        mode,
        ...formData,
      },
      $page: {
        kind: (schema as any)?.kind,
        modelCode: (schema as any)?.modelCode,
        pageKey: (schema as any)?.pageKey,
        mode,
        recordPid: recordPid || undefined,
      },
    },
  });

  // Get user info and permissions from context
  const { user } = useUser();
  const { permissions, hasPermission } = usePermissions();

  // Kind × capability validation: fetch selected model's capabilities when the
  // form has both `model_code` and `kind` fields. No-op for other forms.
  const activeModelCodeForCaps = hasModelCodeAndKindFields
    ? typeof formData.model_code === 'string'
      ? formData.model_code
      : undefined
    : undefined;
  const { data: kindCapabilities } = useModelCapabilities(activeModelCodeForCaps);

  // Create complete ExpressionContext for field rendering
  const pageContext = useMemo(() => {
    // Transform user to match ExpressionContext requirements with real permissions
    // Support both formats: permissionCodes (string array) or permissions (object array)
    const permissionCodes =
      permissions?.permissionCodes || permissions?.permissions?.map((p: any) => p.code) || [];
    const contextUser = user
      ? {
          ...user,
          roles: permissions?.roles?.map((r: any) => r.code) || [],
          permissions: permissionCodes,
        }
      : undefined;

    // Transform tenantId to tenant object
    const contextTenant = user?.tenantId
      ? {
          id: String(user.tenantId),
          name: `Tenant ${user.tenantId}`,
        }
      : undefined;

    return createExpressionContext({
      global: {
        locale,
        theme: 'light',
        user: contextUser,
        tenant: contextTenant,
      },
      state: {
        mode,
      },
      form: {
        mode,
        ...formData,
      },
      // Expose current form values as `record` so DSL visibleWhen expressions
      // like `record.sc_status === 'active'` work uniformly on form + detail.
      // Detail page already provides `record`; form must match so the same
      // DSL works across both kinds.
      record: formData,
      locale,
      t: (key: string) => t(key),
      fetchResult,
      __dataSourceManager: dataSourceManager,
      __pageKey: (schema as any)?.pageKey,
      __modelCode: (schema as any)?.modelCode || tableName,
      __setFormFieldValue: (fieldCode: string, value: unknown) => {
        dirtyFieldsRef.current.add(fieldCode);
        setFormData((prev) => {
          if (prev[fieldCode] === value) return prev;
          return {
            ...prev,
            [fieldCode]: value,
          };
        });
      },
    });
  }, [
    locale,
    t,
    formData,
    dataSourceManager,
    user,
    permissions,
    mode,
    dirtyFieldsRef,
    setFormData,
  ]);

  useEffect(() => {
    const expectedPageKey = (schema as any)?.pageKey;
    const expectedModelCode = (schema as any)?.modelCode || tableName;
    const handleReferenceCreated = (event: Event) => {
      const detail = (event as CustomEvent)?.detail || {};
      if (detail.pageKey && detail.pageKey !== expectedPageKey) return;
      if (detail.modelCode && detail.modelCode !== expectedModelCode) return;
      if (!detail.fieldCode) return;
      dirtyFieldsRef.current.add(String(detail.fieldCode));
      setFormData((prev) => {
        if (prev[detail.fieldCode] === detail.value) return prev;
        return {
          ...prev,
          [detail.fieldCode]: detail.value,
        };
      });
    };
    window.addEventListener('aura:reference-field-created', handleReferenceCreated);
    return () => {
      window.removeEventListener('aura:reference-field-created', handleReferenceCreated);
    };
  }, [schema, tableName, dirtyFieldsRef, setFormData]);
  return {
    schemaFieldNames,
    urlDefaultValues,
    schemaDefaultValues,
    urlSeededModelCode,
    hasModelCodeAndKindFields,
    runtime,
    dataSourceManager,
    t,
    locale,
    navigate,
    hasPermission,
    kindCapabilities,
    pageContext,
  };
}
