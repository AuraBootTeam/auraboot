/**
 * 拖拽预览提供者组件
 * 为拖拽系统提供预览功能的React组件封装
 */

import React, { createContext, useContext, useRef, useEffect, useState } from 'react';
import {
  DragPreview,
  DragPreviewPresets,
  DragPreviewConfig,
  DragPreviewState,
} from '~/plugins/core-designer/components/studio/services/layout/drag-preview/DragPreview';

export interface DragPreviewContextValue {
  /** 拖拽预览实例 */
  dragPreview: DragPreview | null;
  /** 预览状态 */
  state: DragPreviewState;
  /** 开始预览 */
  startPreview: (item: any, sourceElement: HTMLElement, position: { x: number; y: number }) => void;
  /** 结束预览 */
  endPreview: () => void;
  /** 更新位置 */
  updatePosition: (position: { x: number; y: number }) => void;
  /** 设置自定义内容 */
  setCustomContent: (contentGenerator: (item: any) => HTMLElement) => void;
  /** 更新配置 */
  updateConfig: (config: Partial<DragPreviewConfig>) => void;
}

const DragPreviewContext = createContext<DragPreviewContextValue | null>(null);

export interface DragPreviewProviderProps {
  /** 预览配置 */
  config?: Partial<DragPreviewConfig>;
  /** 预设配置名称 */
  preset?: keyof typeof DragPreviewPresets;
  /** 容器元素引用 */
  containerRef?: React.RefObject<HTMLElement>;
  /** 子组件 */
  children: React.ReactNode;
}

/**
 * 拖拽预览提供者组件
 */
export const DragPreviewProvider: React.FC<DragPreviewProviderProps> = ({
  config = {},
  preset = 'default',
  containerRef,
  children,
}) => {
  const dragPreviewRef = useRef<DragPreview | null>(null);
  const [state, setState] = useState<DragPreviewState>({
    isVisible: false,
    previewElement: null,
    ghostElement: null,
    dragItem: null,
    mousePosition: { x: 0, y: 0 },
  });

  // 初始化拖拽预览
  useEffect(() => {
    const container = containerRef?.current || document.body;
    const presetConfig = DragPreviewPresets[preset] || DragPreviewPresets.default;
    const finalConfig = { ...presetConfig, ...config };

    dragPreviewRef.current = new DragPreview(container, finalConfig);

    return () => {
      dragPreviewRef.current?.destroy();
    };
  }, [containerRef?.current, preset]);

  // 更新状态
  useEffect(() => {
    if (!dragPreviewRef.current) return;

    const updateState = () => {
      if (dragPreviewRef.current) {
        setState(dragPreviewRef.current.getState());
      }
    };

    const interval = setInterval(updateState, 16); // ~60fps
    return () => clearInterval(interval);
  }, []);

  const contextValue: DragPreviewContextValue = {
    dragPreview: dragPreviewRef.current,
    state,
    startPreview: (item, sourceElement, position) => {
      dragPreviewRef.current?.startPreview(item, sourceElement, position);
    },
    endPreview: () => {
      dragPreviewRef.current?.endPreview();
    },
    updatePosition: (position) => {
      dragPreviewRef.current?.updatePosition(position);
    },
    setCustomContent: (contentGenerator) => {
      dragPreviewRef.current?.setCustomContent(contentGenerator);
    },
    updateConfig: (newConfig) => {
      dragPreviewRef.current?.updateConfig(newConfig);
    },
  };

  return <DragPreviewContext.Provider value={contextValue}>{children}</DragPreviewContext.Provider>;
};

/**
 * 使用拖拽预览的Hook
 */
export function useDragPreview(): DragPreviewContextValue {
  const context = useContext(DragPreviewContext);
  if (!context) {
    throw new Error('useDragPreview must be used within a DragPreviewProvider');
  }
  return context;
}

/**
 * 拖拽预览Hook，用于单个拖拽项
 */
export function useDragItemPreview(
  elementRef: React.RefObject<HTMLElement>,
  item: any,
  options: {
    /** 是否启用预览 */
    enabled?: boolean;
    /** 自定义预览内容 */
    customContent?: (item: any) => HTMLElement;
    /** 预览配置 */
    config?: Partial<DragPreviewConfig>;
  } = {},
) {
  const dragPreview = useDragPreview();
  const { enabled = true, customContent, config } = options;

  useEffect(() => {
    if (!elementRef.current || !enabled) return;

    const element = elementRef.current;

    // 设置自定义内容
    if (customContent) {
      dragPreview.setCustomContent(customContent);
    }

    // 更新配置
    if (config) {
      dragPreview.updateConfig(config);
    }

    const handleDragStart = (event: DragEvent) => {
      const position = {
        x: event.clientX,
        y: event.clientY,
      };

      dragPreview.startPreview(item, element, position);
    };

    const handleDragEnd = () => {
      dragPreview.endPreview();
    };

    element.addEventListener('dragstart', handleDragStart);
    element.addEventListener('dragend', handleDragEnd);

    return () => {
      element.removeEventListener('dragstart', handleDragStart);
      element.removeEventListener('dragend', handleDragEnd);
    };
  }, [elementRef.current, item, enabled, customContent, config]);

  return {
    isVisible: dragPreview.state.isVisible,
    dragItem: dragPreview.state.dragItem,
  };
}

/**
 * 自定义预览内容组件
 */
export interface CustomPreviewContentProps {
  /** 拖拽项数据 */
  item: any;
  /** 样式类名 */
  className?: string;
  /** 内联样式 */
  style?: React.CSSProperties;
  /** 子组件 */
  children?: React.ReactNode;
}

export const CustomPreviewContent: React.FC<CustomPreviewContentProps> = ({
  item,
  className = '',
  style = {},
  children,
}) => {
  return (
    <div className={`custom-preview-content ${className}`} style={style}>
      {children || (
        <div className="custom-preview-content__default">
          <div className="custom-preview-content__type">{item.type || 'Component'}</div>
          <div className="custom-preview-content__name">
            {item.name || item.data?.name || 'Unnamed'}
          </div>
        </div>
      )}
    </div>
  );
};

/**
 * 预览配置组件
 */
export interface PreviewConfigPanelProps {
  /** 当前配置 */
  config: DragPreviewConfig;
  /** 配置变更回调 */
  onChange: (config: Partial<DragPreviewConfig>) => void;
  /** 样式类名 */
  className?: string;
}

export const PreviewConfigPanel: React.FC<PreviewConfigPanelProps> = ({
  config,
  onChange,
  className = '',
}) => {
  const handlePresetChange = (preset: keyof typeof DragPreviewPresets) => {
    const presetConfig = DragPreviewPresets[preset];
    onChange(presetConfig);
  };

  return (
    <div className={`preview-config-panel ${className}`}>
      <div className="preview-config-panel__section">
        <h3 className="preview-config-panel__title">预设配置</h3>
        <div className="preview-config-panel__presets">
          {Object.keys(DragPreviewPresets).map((preset) => (
            <button
              key={preset}
              className="preview-config-panel__preset-button"
              onClick={() => handlePresetChange(preset as keyof typeof DragPreviewPresets)}
            >
              {preset}
            </button>
          ))}
        </div>
      </div>

      <div className="preview-config-panel__section">
        <h3 className="preview-config-panel__title">基础设置</h3>

        <label className="preview-config-panel__field">
          <span>启用预览</span>
          <input
            type="checkbox"
            checked={config.enabled}
            onChange={(e) => onChange({ enabled: e.target.checked })}
          />
        </label>

        <label className="preview-config-panel__field">
          <span>启用Ghost效果</span>
          <input
            type="checkbox"
            checked={config.enableGhost}
            onChange={(e) => onChange({ enableGhost: e.target.checked })}
          />
        </label>

        <label className="preview-config-panel__field">
          <span>显示原始内容</span>
          <input
            type="checkbox"
            checked={config.showOriginalContent}
            onChange={(e) => onChange({ showOriginalContent: e.target.checked })}
          />
        </label>
      </div>

      <div className="preview-config-panel__section">
        <h3 className="preview-config-panel__title">外观设置</h3>

        <label className="preview-config-panel__field">
          <span>缩放比例</span>
          <input
            type="range"
            min="0.1"
            max="1"
            step="0.1"
            value={config.scale}
            onChange={(e) => onChange({ scale: parseFloat(e.target.value) })}
          />
          <span>{config.scale}</span>
        </label>

        <label className="preview-config-panel__field">
          <span>透明度</span>
          <input
            type="range"
            min="0.1"
            max="1"
            step="0.1"
            value={config.opacity}
            onChange={(e) => onChange({ opacity: parseFloat(e.target.value) })}
          />
          <span>{config.opacity}</span>
        </label>

        <label className="preview-config-panel__field">
          <span>Ghost透明度</span>
          <input
            type="range"
            min="0.1"
            max="1"
            step="0.1"
            value={config.ghostOpacity}
            onChange={(e) => onChange({ ghostOpacity: parseFloat(e.target.value) })}
          />
          <span>{config.ghostOpacity}</span>
        </label>
      </div>

      <div className="preview-config-panel__section">
        <h3 className="preview-config-panel__title">位置设置</h3>

        <label className="preview-config-panel__field">
          <span>X偏移</span>
          <input
            type="number"
            value={config.offset.x}
            onChange={(e) =>
              onChange({
                offset: { ...config.offset, x: parseInt(e.target.value) || 0 },
              })
            }
          />
        </label>

        <label className="preview-config-panel__field">
          <span>Y偏移</span>
          <input
            type="number"
            value={config.offset.y}
            onChange={(e) =>
              onChange({
                offset: { ...config.offset, y: parseInt(e.target.value) || 0 },
              })
            }
          />
        </label>
      </div>

      <div className="preview-config-panel__section">
        <h3 className="preview-config-panel__title">动画设置</h3>

        <label className="preview-config-panel__field">
          <span>动画持续时间 (ms)</span>
          <input
            type="number"
            min="0"
            max="1000"
            step="50"
            value={config.animationDuration}
            onChange={(e) => onChange({ animationDuration: parseInt(e.target.value) || 0 })}
          />
        </label>
      </div>
    </div>
  );
};
