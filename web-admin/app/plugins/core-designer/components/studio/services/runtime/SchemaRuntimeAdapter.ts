/**
 * Thin wrapper that exposes the meta runtime building blocks required by the
 * new Studio layers. This allows us to redirect imports away from the legacy
 * designer entrypoints while preserving behaviour.
 */
export { actionRegistry } from '~/framework/meta/runtime/actions/ActionRegistry';
export { useActionHandler } from '~/framework/meta/hooks/useActionHandler';
export { executeSchemaHandler } from '~/framework/meta/hooks/executeSchemaHandler';
export { useSchemaRuntime } from '~/framework/meta/hooks/useSchemaRuntime';
export { usePageDataSources } from '~/framework/meta/hooks/usePageDataSources';
export { convertSchemaToUnified } from '~/plugins/core-designer/components/studio/workbench/panels/preview/toUnified';
