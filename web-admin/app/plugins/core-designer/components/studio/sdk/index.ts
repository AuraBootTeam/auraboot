import { useCanvasEditorState, type CanvasEditorState } from '~/plugins/core-designer/components/studio/hooks/store/useCanvasEditorState';
import { getSchemaManager, type SchemaManager } from '~/plugins/core-designer/components/studio/services/schema/SchemaManager';
import { getLayoutManager, type LayoutManager } from '~/plugins/core-designer/components/studio/services/layout/LayoutManager';
import {
  getPageStateManager,
  type IPageStateManager,
} from '~/plugins/core-designer/components/studio/services/state/PageStateManager';
import {
  getCommandManager,
  type CommandManager,
} from '~/plugins/core-designer/components/studio/services/actions/command/CommandManager';
import {
  componentRegistry,
  initializeComponentRegistry,
  type ComponentRegistry,
} from '~/framework/meta/registry/components';
import {
  actionRegistry,
  useSchemaRuntime,
  useActionHandler,
  executeSchemaHandler,
  usePageDataSources,
} from '~/plugins/core-designer/components/studio/services/runtime/SchemaRuntimeAdapter';

let registryInitialized = false;

function ensureComponentRegistry() {
  if (!registryInitialized) {
    initializeComponentRegistry();
    registryInitialized = true;
  }
}

export interface DesignerSDK {
  useStore: typeof useCanvasEditorState;
  getStore: () => CanvasEditorState;
  schemaManager: SchemaManager;
  layoutManager: LayoutManager;
  pageStateManager: IPageStateManager;
  commandManager: CommandManager;
  componentRegistry: ComponentRegistry;
  runtime: {
    actionRegistry: typeof actionRegistry;
    useSchemaRuntime: typeof useSchemaRuntime;
    useActionHandler: typeof useActionHandler;
    executeSchemaHandler: typeof executeSchemaHandler;
    usePageDataSources: typeof usePageDataSources;
  };
}

let sdkInstance: DesignerSDK | null = null;

export function getDesignerSDK(): DesignerSDK {
  if (!sdkInstance) {
    ensureComponentRegistry();

    sdkInstance = {
      useStore: useCanvasEditorState,
      getStore: () => useCanvasEditorState.getState(),
      schemaManager: getSchemaManager(),
      layoutManager: getLayoutManager(),
      pageStateManager: getPageStateManager(),
      commandManager: getCommandManager(),
      componentRegistry,
      runtime: {
        actionRegistry,
        useSchemaRuntime,
        useActionHandler,
        executeSchemaHandler,
        usePageDataSources,
      },
    };
  }

  return sdkInstance;
}

export { useCanvasEditorState } from '~/plugins/core-designer/components/studio/hooks/store/useCanvasEditorState';
export {
  actionRegistry,
  useSchemaRuntime,
  useActionHandler,
  executeSchemaHandler,
  usePageDataSources,
} from '~/plugins/core-designer/components/studio/services/runtime/SchemaRuntimeAdapter';
