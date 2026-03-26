/**
 * 吸附和对齐配置面板
 * 提供可视化的吸附和对齐配置界面
 */

import React, { useState } from 'react';
import { SnapEnginePresets, type SnapConfig } from '~/studio/services/layout/snap/SnapEngine';
import {
  AlignmentSystemPresets,
  type AlignmentConfig,
} from '~/studio/services/layout/alignment/AlignmentSystem';
import { useSnapAlignConfig } from '~/studio/hooks/layout/useSnapAndAlign';
import type { SnapAndAlignConfig } from '~/studio/hooks/layout/useSnapAndAlign';

export interface SnapAlignPanelProps {
  /** 当前配置 */
  config: SnapAndAlignConfig;
  /** 配置变更回调 */
  onConfigChange: (config: Partial<SnapAndAlignConfig>) => void;
  /** 是否显示面板 */
  visible?: boolean;
  /** 面板位置 */
  position?: 'left' | 'right' | 'top' | 'bottom';
  /** 是否可折叠 */
  collapsible?: boolean;
}

/**
 * 吸附配置组件
 */
function SnapConfigSection({
  config,
  onConfigChange,
}: {
  config: SnapAndAlignConfig;
  onConfigChange: (config: Partial<SnapAndAlignConfig>) => void;
}) {
  const snapConfig = config.snap;
  const handleSnapConfigChange = (patch: Partial<SnapConfig>) => {
    onConfigChange({
      snap: { ...config.snap, ...patch },
    });
  };
  const gridConfig = snapConfig.grid ?? { enabled: false, size: 8, offset: { x: 0, y: 0 } };
  const edgesConfig = snapConfig.edges ?? {
    enabled: false,
    threshold: 10,
    types: ['container', 'component'],
  };
  const componentsConfig = snapConfig.components ?? {
    enabled: false,
    threshold: 10,
    alignTypes: ['left', 'right'],
  };
  const guidesConfig = snapConfig.guides ?? {
    enabled: true,
    threshold: 5,
    showLines: true,
    lineStyle: { color: '#3b82f6', width: 1 },
  };

  return (
    <div className="snap-config-section">
      <div className="config-section-header">
        <h4>吸附设置</h4>
        <label className="config-toggle">
          <input
            type="checkbox"
            checked={config.enableSnap}
            onChange={(e) => onConfigChange({ enableSnap: e.target.checked })}
          />
          <span>启用吸附</span>
        </label>
      </div>

      {config.enableSnap && (
        <div className="config-section-content">
          <div className="config-group">
            <label>预设配置</label>
            <select
              value={
                Object.keys(SnapEnginePresets).find(
                  (key) =>
                    JSON.stringify(SnapEnginePresets[key as keyof typeof SnapEnginePresets]) ===
                    JSON.stringify(config.snap),
                ) || 'custom'
              }
              onChange={(e) => {
                if (e.target.value !== 'custom') {
                  const preset =
                    SnapEnginePresets[e.target.value as keyof typeof SnapEnginePresets];
                  onConfigChange({ snap: preset });
                }
              }}
            >
              <option value="default">默认</option>
              <option value="precise">精确</option>
              <option value="loose">宽松</option>
              <option value="performance">性能优先</option>
              <option value="disabled">禁用</option>
              <option value="custom">自定义</option>
            </select>
          </div>

          <div className="config-group">
            <label>网格吸附</label>
            <div className="config-row">
              <label>
                <input
                  type="checkbox"
                  checked={gridConfig.enabled}
                  onChange={(e) =>
                    handleSnapConfigChange({ grid: { ...gridConfig, enabled: e.target.checked } })
                  }
                />
                启用网格
              </label>
              <input
                type="number"
                value={gridConfig.size}
                onChange={(e) =>
                  handleSnapConfigChange({ grid: { ...gridConfig, size: Number(e.target.value) } })
                }
                min="1"
                max="100"
                disabled={!gridConfig.enabled}
              />
              <span>px</span>
            </div>
          </div>

          <div className="config-group">
            <label>边缘吸附</label>
            <div className="config-row">
              <label>
                <input
                  type="checkbox"
                  checked={edgesConfig.enabled}
                  onChange={(e) =>
                    handleSnapConfigChange({ edges: { ...edgesConfig, enabled: e.target.checked } })
                  }
                />
                启用边缘
              </label>
              <input
                type="number"
                value={edgesConfig.threshold}
                onChange={(e) =>
                  handleSnapConfigChange({
                    edges: { ...edgesConfig, threshold: Number(e.target.value) },
                  })
                }
                min="1"
                max="50"
                disabled={!edgesConfig.enabled}
              />
              <span>px</span>
            </div>
          </div>

          <div className="config-group">
            <label>组件吸附</label>
            <div className="config-row">
              <label>
                <input
                  type="checkbox"
                  checked={componentsConfig.enabled}
                  onChange={(e) =>
                    handleSnapConfigChange({
                      components: { ...componentsConfig, enabled: e.target.checked },
                    })
                  }
                />
                启用组件
              </label>
            </div>
          </div>

          <div className="config-group">
            <label>辅助线</label>
            <div className="config-row">
              <label>
                <input
                  type="checkbox"
                  checked={guidesConfig.showLines}
                  onChange={(e) =>
                    handleSnapConfigChange({
                      guides: {
                        ...guidesConfig,
                        showLines: e.target.checked,
                        enabled: e.target.checked,
                      },
                    })
                  }
                />
                显示辅助线
              </label>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

/**
 * 对齐配置组件
 */
function AlignmentConfigSection({
  config,
  onConfigChange,
}: {
  config: SnapAndAlignConfig;
  onConfigChange: (config: Partial<SnapAndAlignConfig>) => void;
}) {
  const alignmentConfig = config.alignment;
  const handleAlignmentConfigChange = (patch: Partial<AlignmentConfig>) => {
    onConfigChange({
      alignment: { ...config.alignment, ...patch },
    });
  };
  const guidesConfig = alignmentConfig.guides ?? {
    enabled: true,
    showDistance: true,
    style: { color: '#f59e0b', width: 1 },
  };
  const autoAlignConfig = alignmentConfig.autoAlign ?? { enabled: false, delay: 500 };

  return (
    <div className="alignment-config-section">
      <div className="config-section-header">
        <h4>对齐设置</h4>
        <label className="config-toggle">
          <input
            type="checkbox"
            checked={config.enableAlignment}
            onChange={(e) => onConfigChange({ enableAlignment: e.target.checked })}
          />
          <span>启用对齐</span>
        </label>
      </div>

      {config.enableAlignment && (
        <div className="config-section-content">
          <div className="config-group">
            <label>预设配置</label>
            <select
              value={
                Object.keys(AlignmentSystemPresets).find(
                  (key) =>
                    JSON.stringify(
                      AlignmentSystemPresets[key as keyof typeof AlignmentSystemPresets],
                    ) === JSON.stringify(config.alignment),
                ) || 'custom'
              }
              onChange={(e) => {
                if (e.target.value !== 'custom') {
                  const preset =
                    AlignmentSystemPresets[e.target.value as keyof typeof AlignmentSystemPresets];
                  onConfigChange({ alignment: preset });
                }
              }}
            >
              <option value="default">默认</option>
              <option value="precise">精确</option>
              <option value="simple">简单</option>
              <option value="performance">性能优先</option>
              <option value="custom">自定义</option>
            </select>
          </div>

          <div className="config-group">
            <label>对齐类型</label>
            <div className="alignment-types">
              {(['left', 'right', 'top', 'bottom', 'center-x', 'center-y'] as const).map((type) => (
                <label key={type} className="alignment-type">
                  <input
                    type="checkbox"
                    checked={alignmentConfig.alignTypes?.includes(type)}
                    onChange={(e) => {
                      const currentTypes = alignmentConfig.alignTypes || [];
                      const newTypes = e.target.checked
                        ? [...currentTypes, type]
                        : currentTypes.filter((t: string) => t !== type);
                      handleAlignmentConfigChange({ alignTypes: newTypes });
                    }}
                  />
                  <span>{type}</span>
                </label>
              ))}
            </div>
          </div>

          <div className="config-group">
            <label>对齐距离</label>
            <div className="config-row">
              <input
                type="number"
                value={alignmentConfig.threshold ?? 8}
                onChange={(e) => handleAlignmentConfigChange({ threshold: Number(e.target.value) })}
                min="1"
                max="50"
              />
              <span>px</span>
            </div>
          </div>

          <div className="config-group">
            <label>自动对齐</label>
            <div className="config-row">
              <label>
                <input
                  type="checkbox"
                  checked={autoAlignConfig.enabled}
                  onChange={(e) =>
                    handleAlignmentConfigChange({
                      autoAlign: { ...autoAlignConfig, enabled: e.target.checked },
                    })
                  }
                />
                启用自动对齐
              </label>
            </div>
          </div>

          <div className="config-group">
            <label>辅助线</label>
            <div className="config-row">
              <label>
                <input
                  type="checkbox"
                  checked={guidesConfig.enabled}
                  onChange={(e) =>
                    handleAlignmentConfigChange({
                      guides: { ...guidesConfig, enabled: e.target.checked },
                    })
                  }
                />
                显示辅助线
              </label>
              <label>
                <input
                  type="checkbox"
                  checked={guidesConfig.showDistance}
                  onChange={(e) =>
                    handleAlignmentConfigChange({
                      guides: { ...guidesConfig, showDistance: e.target.checked },
                    })
                  }
                />
                显示距离
              </label>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

/**
 * 优先级配置组件
 */
function PriorityConfigSection({
  config,
  onConfigChange,
}: {
  config: SnapAndAlignConfig;
  onConfigChange: (config: Partial<SnapAndAlignConfig>) => void;
}) {
  return (
    <div className="priority-config-section">
      <div className="config-section-header">
        <h4>优先级设置</h4>
      </div>
      <div className="config-section-content">
        <div className="config-group">
          <label>优先级</label>
          <select
            value={config.priority}
            onChange={(e) =>
              onConfigChange({ priority: e.target.value as SnapAndAlignConfig['priority'] })
            }
          >
            <option value="snap">吸附优先</option>
            <option value="alignment">对齐优先</option>
            <option value="both">智能选择</option>
          </select>
        </div>
      </div>
    </div>
  );
}

/**
 * 吸附和对齐配置面板
 */
export function SnapAlignPanel({
  config,
  onConfigChange,
  visible = true,
  position = 'right',
  collapsible = true,
}: SnapAlignPanelProps) {
  const [collapsed, setCollapsed] = useState(false);

  if (!visible) return null;

  return (
    <div
      className={`snap-align-panel snap-align-panel--${position} ${collapsed ? 'collapsed' : ''}`}
    >
      <div className="snap-align-panel__header">
        <h3>吸附和对齐</h3>
        {collapsible && (
          <button
            className="collapse-button"
            onClick={() => setCollapsed(!collapsed)}
            aria-label={collapsed ? '展开面板' : '折叠面板'}
          >
            {collapsed ? '▶' : '▼'}
          </button>
        )}
      </div>

      {!collapsed && (
        <div className="snap-align-panel__content">
          <SnapConfigSection config={config} onConfigChange={onConfigChange} />
          <AlignmentConfigSection config={config} onConfigChange={onConfigChange} />
          <PriorityConfigSection config={config} onConfigChange={onConfigChange} />
        </div>
      )}
    </div>
  );
}

/**
 * 快速配置工具栏
 */
export function SnapAlignToolbar({
  config,
  onConfigChange,
}: {
  config: SnapAndAlignConfig;
  onConfigChange: (config: Partial<SnapAndAlignConfig>) => void;
}) {
  return (
    <div className="snap-align-toolbar">
      <div className="toolbar-group">
        <button
          className={`toolbar-button ${config.enableSnap ? 'active' : ''}`}
          onClick={() => onConfigChange({ enableSnap: !config.enableSnap })}
          title="切换吸附"
        >
          <span className="icon">⊞</span>
          <span>吸附</span>
        </button>

        <button
          className={`toolbar-button ${config.enableAlignment ? 'active' : ''}`}
          onClick={() => onConfigChange({ enableAlignment: !config.enableAlignment })}
          title="切换对齐"
        >
          <span className="icon">⫽</span>
          <span>对齐</span>
        </button>
      </div>

      <div className="toolbar-group">
        <button
          className={`toolbar-button ${config.snap.guides?.showLines ? 'active' : ''}`}
          onClick={() =>
            onConfigChange({
              snap: {
                ...config.snap,
                guides: {
                  ...(config.snap.guides ?? {
                    enabled: true,
                    showLines: true,
                    threshold: 5,
                    lineStyle: { color: '#3b82f6', width: 1 },
                  }),
                  showLines: !(config.snap.guides?.showLines ?? false),
                  enabled: !(config.snap.guides?.showLines ?? false),
                },
              },
            })
          }
          title="切换吸附辅助线"
          disabled={!config.enableSnap}
        >
          <span className="icon">⫸</span>
        </button>

        <button
          className={`toolbar-button ${config.alignment.guides?.enabled ? 'active' : ''}`}
          onClick={() =>
            onConfigChange({
              alignment: {
                ...config.alignment,
                guides: {
                  ...(config.alignment.guides ?? {
                    enabled: true,
                    showDistance: true,
                    style: { color: '#f59e0b', width: 1 },
                  }),
                  enabled: !(config.alignment.guides?.enabled ?? false),
                },
              },
            })
          }
          title="切换对齐辅助线"
          disabled={!config.enableAlignment}
        >
          <span className="icon">⫷</span>
        </button>
      </div>

      <div className="toolbar-group">
        <select
          className="toolbar-select"
          value={config.priority}
          onChange={(e) =>
            onConfigChange({ priority: e.target.value as SnapAndAlignConfig['priority'] })
          }
          title="优先级"
        >
          <option value="snap">吸附</option>
          <option value="alignment">对齐</option>
          <option value="both">智能</option>
        </select>
      </div>
    </div>
  );
}

/**
 * 使用配置Hook的面板组件
 */
export function SnapAlignConfigPanel() {
  const { config, updateSnapPreset, updateAlignPreset, updateCustomConfig } = useSnapAlignConfig();

  return <SnapAlignPanel config={config} onConfigChange={updateCustomConfig} />;
}
