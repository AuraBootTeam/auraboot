/**
 * Thin wrapper that exposes the meta runtime building blocks required by the
 * new Studio layers. This allows us to redirect imports away from the legacy
 * designer entrypoints while preserving behaviour.
 */
export { actionRegistry } from '~/meta/runtime/actions/ActionRegistry';
export { useActionHandler } from '~/meta/hooks/useActionHandler';
export { executeSchemaHandler } from '~/meta/hooks/executeSchemaHandler';
export { useSchemaRuntime } from '~/meta/hooks/useSchemaRuntime';
export { usePageDataSources } from '~/meta/hooks/usePageDataSources';
export { convertSchemaToUnified } from '~/studio/domain/schema/converters/toUnified';
