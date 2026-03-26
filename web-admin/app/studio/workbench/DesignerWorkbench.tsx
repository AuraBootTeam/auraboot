import React, { useCallback, useMemo, useState } from 'react';
import { ComponentPalette } from '~/studio/workbench/palette/ComponentPalette';
import { FieldLibraryPanel } from '~/studio/workbench/panels/fields/FieldLibraryPanel';
import { PropertyPanel } from '~/studio/workbench/panels/properties/PropertyPanel/PropertyPanel';
import { DesignCanvas as GridDesignCanvas } from '~/studio/workbench/canvas/DesignCanvas';
import { TabContainerDesigner } from '~/studio/workbench/components/layout-hierarchy/TabContainerDesigner';
// HierarchyToolbar hidden - import removed
import { HierarchyOutline } from '~/studio/workbench/components/layout-hierarchy/HierarchyOutline';
import { TabContainerRuntime } from '~/studio/workbench/components/layout-hierarchy/runtime/TabContainerRuntime';
import { ActionPanel } from '~/studio/workbench/panels/actions/ActionPanel';
import { LinkagePanel } from '~/studio/workbench/panels/linkage/LinkagePanel';
import { DesignerPreview } from '~/studio/workbench/runtime/DesignerPreview';
import { LayoutPresetSelector } from '~/studio/workbench/components/toolbar/LayoutPresetSelector';
import {
  NewPageWizard,
  type NewPageWizardResult,
} from '~/studio/workbench/components/wizard/NewPageWizard';
import { ViewModelSelector } from '~/studio/workbench/panels/viewmodel/ViewModelSelector';
import { useDesignerStore } from '~/studio/hooks/store/useDesignerStore';
import { useHierarchyLayout } from '~/studio/hooks/layout/useHierarchyLayout';
import { applyLayoutPreset } from '~/studio/services/layout/preset-applicator';
import type { FormSchema } from '~/studio/domain/schema/types';
import type { Component, LayoutConfig } from '~/studio/domain/schema/types';
import type { LayoutPreset } from '~/studio/domain/schema/layout-presets';
import type { TabContainerConfig } from '~/studio/domain/schema/layout-hierarchy';

export interface DesignerWorkbenchProps {
  schema: FormSchema;
  previewMode: boolean;
  readonly: boolean;
  modelPid?: string;
  modelCode?: string;
  viewModelCode?: string;
}

export const DesignerWorkbench: React.FC<DesignerWorkbenchProps> = ({
  schema,
  previewMode,
  readonly,
  modelPid,
  modelCode,
  viewModelCode,
}) => {
  // Resolve viewModelCode from prop or schema meta
  const effectiveViewModelCode = viewModelCode || schema?.meta?.viewModelCode;

  return (
    <div className="flex flex-1 overflow-hidden">
      {!previewMode && (
        <LeftPanel
          schema={schema}
          readonly={readonly}
          modelPid={modelPid}
          modelCode={modelCode}
          viewModelCode={effectiveViewModelCode}
        />
      )}

      <div className="flex flex-1 flex-col overflow-hidden">
        {!previewMode && !readonly && <CanvasToolbar schema={schema} />}

        <div className="flex-1 overflow-auto bg-white">
          <div className="min-h-full p-6">
            {previewMode ? <DesignerPreview schema={schema} /> : <EditorCanvas schema={schema} />}
          </div>
        </div>
      </div>

      {!previewMode && <RightPanel schema={schema} readonly={readonly} modelCode={modelCode} />}
    </div>
  );
};

const LeftPanel: React.FC<{
  schema: FormSchema;
  readonly: boolean;
  modelPid?: string;
  modelCode?: string;
  viewModelCode?: string;
}> = ({ schema, readonly, modelPid, modelCode, viewModelCode }) => {
  const activeTab = useDesignerStore((state) => state.leftPanelTab);
  const setActiveTab = useDesignerStore((state) => state.setLeftPanelTab);
  const { updatePageSchema } = useDesignerStore();

  const handleViewModelChange = useCallback(
    (code: string | null) => {
      updatePageSchema((draft) => {
        if (!draft.meta) draft.meta = {};
        draft.meta.viewModelCode = code ?? undefined;
      });
    },
    [updatePageSchema],
  );

  // Use viewModelCode from prop or schema meta
  const effectiveViewModelCode = viewModelCode || schema?.meta?.viewModelCode;

  const leftTabs: Array<{ id: 'fields' | 'components' | 'outline'; icon: string; label: string }> =
    [
      { id: 'fields', icon: '📋', label: '字段' },
      { id: 'components', icon: '🧩', label: '组件' },
      { id: 'outline', icon: '📑', label: '大纲' },
    ];

  return (
    <div className="flex w-72 flex-col border-r border-gray-200 bg-white" data-domain="left-panel">
      <div className="flex border-b border-gray-200">
        {leftTabs.map((tab) => (
          <button
            key={tab.id}
            onClick={() => setActiveTab(tab.id)}
            className={`flex flex-1 items-center justify-center gap-1.5 px-2 py-2.5 text-sm font-medium transition-colors ${
              activeTab === tab.id
                ? 'border-b-2 border-blue-600 bg-blue-50/50 text-blue-600'
                : 'text-gray-500 hover:bg-gray-50 hover:text-gray-700'
            }`}
            title={tab.label}
          >
            <span className="text-base">{tab.icon}</span>
            <span className="text-xs">{tab.label}</span>
          </button>
        ))}
      </div>

      <div className="flex flex-1 flex-col overflow-hidden">
        {activeTab === 'fields' && (
          <>
            <ViewModelSelector value={effectiveViewModelCode} onChange={handleViewModelChange} />
            <div className="flex-1 overflow-hidden">
              <FieldLibraryPanel
                modelPid={modelPid}
                modelCode={modelCode}
                viewModelCode={effectiveViewModelCode}
              />
            </div>
          </>
        )}
        {activeTab === 'components' && <ComponentPalette />}
        {activeTab === 'outline' && <PageOutline schema={schema} readonly={readonly} />}
      </div>
    </div>
  );
};

/**
 * Extract field code + label from grid-mode components.
 */
function collectGridFieldOptions(blocks: Component[]): { code: string; label: string }[] {
  const result: { code: string; label: string }[] = [];
  for (const block of blocks) {
    if (block.props?.name) {
      result.push({
        code: String(block.props.name),
        label: String(block.props?.label || block.name || block.props.name),
      });
    }
    if ((block as any).children) {
      result.push(...collectGridFieldOptions((block as any).children));
    }
  }
  return result;
}

/**
 * Extract field code + label from hierarchy-mode layout (tabs → floors → blocks → fields).
 */
function collectHierarchyFieldOptions(
  hierarchy?: TabContainerConfig,
): { code: string; label: string }[] {
  if (!hierarchy?.tabs) return [];
  const result: { code: string; label: string }[] = [];
  for (const tab of hierarchy.tabs) {
    for (const floor of tab.floors) {
      for (const block of floor.blocks) {
        for (const field of block.fields) {
          result.push({
            code: field.fieldCode,
            label: field.label || field.props?.label || field.fieldCode,
          });
        }
      }
    }
  }
  return result;
}

/**
 * Collect all field options from both grid mode and hierarchy mode.
 */
function collectFieldOptions(
  components: Component[],
  hierarchy?: TabContainerConfig,
): { code: string; label: string }[] {
  const gridFields = collectGridFieldOptions(components);
  const hierarchyFields = collectHierarchyFieldOptions(hierarchy);
  // Deduplicate by code
  const seen = new Set<string>();
  const result: { code: string; label: string }[] = [];
  for (const f of [...gridFields, ...hierarchyFields]) {
    if (!seen.has(f.code)) {
      seen.add(f.code);
      result.push(f);
    }
  }
  return result;
}

const RightPanel: React.FC<{ schema: FormSchema; readonly: boolean; modelCode?: string }> = ({
  schema,
  readonly,
  modelCode,
}) => {
  const activeTab = useDesignerStore((state) => state.rightPanelTab);
  const setActiveTab = useDesignerStore((state) => state.setRightPanelTab);

  const fieldOptions = useMemo(
    () => collectFieldOptions(schema?.components ?? [], schema?.hierarchy),
    [schema?.components, schema?.hierarchy],
  );

  const rightTabs: Array<{
    id: 'properties' | 'actions' | 'linkage' | 'styles';
    icon: string;
    label: string;
  }> = [
    { id: 'properties', icon: '⚙️', label: '属性' },
    { id: 'actions', icon: '⚡', label: '动作' },
    { id: 'linkage', icon: '🔗', label: '联动' },
    { id: 'styles', icon: '🎨', label: '样式' },
  ];

  return (
    <div className="flex w-72 flex-col border-l border-gray-200 bg-white" data-domain="right-panel">
      <div className="flex border-b border-gray-200">
        {rightTabs.map((tab) => (
          <button
            key={tab.id}
            onClick={() => setActiveTab(tab.id)}
            className={`flex flex-1 items-center justify-center gap-1 px-1.5 py-2.5 text-sm font-medium transition-colors ${
              activeTab === tab.id
                ? 'border-b-2 border-blue-600 bg-blue-50/50 text-blue-600'
                : 'text-gray-500 hover:bg-gray-50 hover:text-gray-700'
            }`}
            title={tab.label}
          >
            <span className="text-sm">{tab.icon}</span>
            <span className="text-xs">{tab.label}</span>
          </button>
        ))}
      </div>

      <div className="flex-1 overflow-hidden">
        {activeTab === 'properties' && <PropertiesPanel schema={schema} readonly={readonly} />}
        {activeTab === 'actions' && <ActionPanel modelCode={modelCode} readonly={readonly} />}
        {activeTab === 'linkage' && (
          <LinkagePanel readonly={readonly} fieldOptions={fieldOptions} />
        )}
        {activeTab === 'styles' && <StylesPanel />}
      </div>
    </div>
  );
};

const CanvasToolbar: React.FC<{ schema: FormSchema }> = ({ schema }) => {
  const { isHierarchyMode, hierarchy, enableHierarchyMode, disableHierarchyMode } =
    useHierarchyLayout();
  const { updatePageSchema } = useDesignerStore();
  const [showWizard, setShowWizard] = useState(false);

  const currentColumns = hierarchy?.tabs?.[0]?.floors?.[0]?.blocks?.[0]?.layout?.columns ?? 2;

  const handlePresetSelect = useCallback(
    (preset: LayoutPreset) => {
      if (hierarchy) {
        const updated = applyLayoutPreset(hierarchy, preset);
        updatePageSchema((draft) => {
          draft.hierarchy = updated;
        });
      }
    },
    [hierarchy, updatePageSchema],
  );

  const handleWizardComplete = useCallback(
    (result: NewPageWizardResult) => {
      updatePageSchema((draft) => {
        draft.hierarchy = result.hierarchy;
        if (!draft.meta) draft.meta = {};
        draft.meta.viewModelCode = result.viewModelCode;
      });
      enableHierarchyMode();
      setShowWizard(false);
    },
    [updatePageSchema, enableHierarchyMode],
  );

  return (
    <>
      <div className="flex h-10 items-center justify-between border-b border-gray-200 bg-white px-4">
        <div className="flex items-center space-x-3">
          <span className="text-sm font-medium text-gray-700">{schema.title || '未命名页面'}</span>
          {/* HierarchyToolbar 已隐藏，布局模式切换可通过其他方式实现 */}
        </div>

        <div className="flex items-center space-x-2">
          {isHierarchyMode && (
            <LayoutPresetSelector currentColumns={currentColumns} onSelect={handlePresetSelect} />
          )}
          <button className="rounded p-1.5 text-gray-400 hover:text-gray-600" title="搜索">
            <svg className="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z"
              />
            </svg>
          </button>
          <button className="rounded p-1.5 text-gray-400 hover:text-gray-600" title="设置">
            <svg className="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M10.325 4.317c.426-1.756 2.924-1.756 3.35 0a1.724 1.724 0 002.573 1.066c1.543-.94 3.31.826 2.37 2.37a1.724 1.724 0 001.065 2.572c1.756.426 1.756 2.924 0 3.35a1.724 1.724 0 00-1.066 2.573c.94 1.543-.826 3.31-2.37 2.37a1.724 1.724 0 00-2.572 1.065c-.426 1.756-2.924 1.756-3.35 0a1.724 1.724 0 00-2.573-1.066c-1.543.94-3.31-.826-2.37-2.37a1.724 1.724 0 00-1.065-2.572c-1.756-.426-1.756-2.924 0-3.35a1.724 1.724 0 001.066-2.573c-.94-1.543.826-3.31 2.37-2.37.996.608 2.296.07 2.572-1.065z"
              />
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M15 12a3 3 0 11-6 0 3 3 0 016 0z"
              />
            </svg>
          </button>
        </div>
      </div>
      {showWizard && (
        <NewPageWizard onComplete={handleWizardComplete} onCancel={() => setShowWizard(false)} />
      )}
    </>
  );
};

const PageOutline: React.FC<{ schema: FormSchema; readonly: boolean }> = ({ schema }) => {
  const {
    hierarchy,
    isHierarchyMode,
    selection,
    selectTab,
    selectFloor,
    selectBlock,
    selectField,
  } = useHierarchyLayout();

  if (isHierarchyMode) {
    return (
      <HierarchyOutline
        hierarchy={hierarchy}
        selection={selection}
        onSelectTab={selectTab}
        onSelectFloor={selectFloor}
        onSelectBlock={selectBlock}
        onSelectField={selectField}
      />
    );
  }

  return (
    <div className="p-4">
      <div className="text-center text-gray-500">
        <svg
          className="mx-auto mb-2 h-12 w-12 text-gray-300"
          fill="none"
          stroke="currentColor"
          viewBox="0 0 24 24"
        >
          <path
            strokeLinecap="round"
            strokeLinejoin="round"
            strokeWidth={2}
            d="M9 5H7a2 2 0 00-2 2v10a2 2 0 002 2h8a2 2 0 002-2V7a2 2 0 00-2-2h-2M9 5a2 2 0 002 2h2a2 2 0 002-2M9 5a2 2 0 012-2h2a2 2 0 012 2"
          />
        </svg>
        <p className="text-sm">页面大纲</p>
        <p className="mt-1 text-xs text-gray-400">显示页面组件结构</p>
      </div>
    </div>
  );
};

const PropertiesPanel: React.FC<{ schema: FormSchema; readonly: boolean }> = () => {
  const { selectedComponentId, updateComponent, components } = useDesignerStore();

  const selectedComponents = selectedComponentId
    ? [components[selectedComponentId]].filter(Boolean)
    : [];

  const [layoutConfig, setLayoutConfig] = useState<LayoutConfig>({
    type: 'grid',
    columns: 12,
    spacing: 16,
    padding: 16,
    mode: 'auto',
    breakpoints: {
      xs: { columns: 1, gap: 8 },
      sm: { columns: 2, gap: 12 },
      md: { columns: 6, gap: 16 },
      lg: { columns: 12, gap: 16 },
      xl: { columns: 12, gap: 20 },
    },
  });

  const [layoutSettings, setLayoutSettings] = useState({
    columns: 12,
    rows: 10,
    gap: 16,
    autoFlow: 'row' as 'row' | 'column' | 'row dense' | 'column dense',
    densePackingEnabled: false,
    densePackingStrategy: 'first-fit' as 'first-fit' | 'best-fit' | 'worst-fit' | 'next-fit',
    optimizeFor: 'balance' as 'space' | 'readability' | 'balance',
  });

  const handleComponentUpdate = useCallback(
    (componentId: string, updates: Partial<Component>) => {
      updateComponent(componentId, updates);
    },
    [updateComponent],
  );

  return (
    <PropertyPanel
      selectedComponents={selectedComponents}
      onComponentUpdate={handleComponentUpdate}
      layoutConfig={layoutConfig}
      onLayoutConfigChange={setLayoutConfig}
      layoutSettings={layoutSettings}
      onLayoutSettingsChange={setLayoutSettings}
    />
  );
};

const StylesPanel: React.FC = () => {
  return (
    <div className="p-4">
      <div className="text-center text-gray-500">
        <svg
          className="mx-auto mb-2 h-12 w-12 text-gray-300"
          fill="none"
          stroke="currentColor"
          viewBox="0 0 24 24"
        >
          <path
            strokeLinecap="round"
            strokeLinejoin="round"
            strokeWidth={2}
            d="M7 21a4 4 0 01-4-4V5a2 2 0 012-2h4a2 2 0 012 2v12a4 4 0 01-4 4zm0 0h12a2 2 0 002-2v-4a2 2 0 00-2-2h-2.343M11 7.343l1.657-1.657a2 2 0 012.828 0l2.829 2.829a2 2 0 010 2.828l-8.486 8.485M7 17v4a2 2 0 002 2h4M13 13h4a2 2 0 012 2v4a2 2 0 01-2 2h-4a2 2 0 01-2-2v-4a2 2 0 012-2z"
          />
        </svg>
        <p className="text-sm">样式面板</p>
        <p className="mt-1 text-xs text-gray-400">编辑选中组件的样式</p>
      </div>
    </div>
  );
};

const EditorCanvas: React.FC<{ schema: FormSchema }> = ({ schema }) => {
  const { selectComponent, selectedComponentId, updateComponent, removeComponent, components } =
    useDesignerStore();

  const {
    hierarchy,
    isHierarchyMode,
    selection,
    selectTab,
    selectFloor,
    selectBlock,
    selectField,
    addTab,
    removeTab,
    addFloor,
    removeFloor,
    toggleFloorCollapse,
    addBlock,
    removeBlock,
    removeField,
    updateField,
  } = useHierarchyLayout();

  // Grid mode layout config
  const layoutConfig = schema.layout || {
    type: 'grid',
    columns: 12,
    gap: 16,
    padding: 24,
  };

  // All hooks must be called before any conditional returns
  const handleComponentClick = useCallback(
    (component: Component) => {
      selectComponent(component.id);
    },
    [selectComponent],
  );

  const handleComponentUpdate = useCallback(
    (componentId: string, updates: Partial<Component>) => {
      updateComponent(componentId, updates);
    },
    [updateComponent],
  );

  const handleComponentDelete = useCallback(
    (componentId: string) => {
      removeComponent(componentId);
    },
    [removeComponent],
  );

  const handleComponentDoubleClick = useCallback((_component: Component) => {
    // TODO: Implement double-click behavior (e.g., open property editor)
  }, []);

  // Hierarchy mode: render TabContainerDesigner
  if (isHierarchyMode) {
    return (
      <TabContainerDesigner
        hierarchy={hierarchy}
        selection={selection}
        onSelectTab={selectTab}
        onSelectFloor={selectFloor}
        onSelectBlock={selectBlock}
        onSelectField={selectField}
        onAddTab={addTab}
        onRemoveTab={removeTab}
        onAddFloor={addFloor}
        onRemoveFloor={removeFloor}
        onToggleFloorCollapse={toggleFloorCollapse}
        onAddBlock={addBlock}
        onRemoveBlock={removeBlock}
        onRemoveField={removeField}
        onUpdateField={updateField}
      />
    );
  }

  // Grid mode: existing behavior

  const allComponents = Object.values(components);
  const selectedComponents =
    selectedComponentId && components[selectedComponentId] ? [components[selectedComponentId]] : [];

  const rows = Math.max(8, Math.ceil(allComponents.length / (layoutConfig?.columns || 12)));
  const gap = (layoutConfig as any).gap || (layoutConfig as any).spacing || 16;

  return (
    <GridDesignCanvas
      columns={layoutConfig?.columns || 12}
      rows={rows}
      gap={gap}
      components={allComponents}
      selectedComponents={selectedComponents}
      onComponentClick={handleComponentClick}
      onComponentUpdate={handleComponentUpdate}
      onComponentDelete={handleComponentDelete}
      onComponentDoubleClick={handleComponentDoubleClick}
    />
  );
};
