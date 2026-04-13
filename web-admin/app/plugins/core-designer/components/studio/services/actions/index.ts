/**
 * Studio action services build on top of the meta runtime ActionRegistry.
 * For now we re-export the registry and helper types so studio code can
 * reference them without importing from app/meta directly.
 */
export { actionRegistry } from '~/framework/meta/runtime/actions/ActionRegistry';
export type { ActionContext, ActionHandler } from '~/framework/meta/runtime/actions/ActionRegistry';
