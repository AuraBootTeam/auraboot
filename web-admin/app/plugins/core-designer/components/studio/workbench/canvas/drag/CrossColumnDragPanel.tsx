/**
 * 跨列拖拽调整配置面板
 * 提供跨列拖拽调整功能的可视化配置界面
 */

import React, { useState, useCallback } from 'react';
import {
  CrossColumnDragEnginePresets,
  CrossColumnDragConfig,
  ResizeTarget,
} from '~/plugins/core-designer/components/studio/services/layout/resize/CrossColumnDragEngine';
import { useCrossColumnDragConfig } from '~/plugins/core-designer/components/studio/hooks/drag/useCrossColumnDrag';

export interface CrossColumnDragPanelProps {
  config: CrossColumnDragConfig;
  targets?: ResizeTarget[];
  onConfigChange: (config: Partial<CrossColumnDragConfig>) => void;
  onTargetsChange?: (targets: ResizeTarget[]) => void;
  className?: string;
}

/**
 * 基础配置面板
 */
interface BasicConfigPanelProps {
  config: CrossColumnDragConfig;
  onConfigChange: (config: Partial<CrossColumnDragConfig>) => void;
}

const BasicConfigPanel: React.FC<BasicConfigPanelProps> = ({ config, onConfigChange }) => {
  const handlePresetChange = (preset: keyof typeof CrossColumnDragEnginePresets) => {
    onConfigChange(CrossColumnDragEnginePresets[preset]);
  };

  return (
    <div className="basic-config-panel">
      <div className="config-section">
        <h4>预设配置</h4>
        <div className="config-row">
          <label>选择预设:</label>
          <select
            onChange={(e) =>
              handlePresetChange(e.target.value as keyof typeof CrossColumnDragEnginePresets)
            }
          >
            <option value="">自定义</option>
            <option value="default">默认</option>
            <option value="precise">精确</option>
            <option value="flexible">灵活</option>
            <option value="performance">性能</option>
          </select>
        </div>
      </div>

      <div className="config-section">
        <h4>网格设置</h4>
        <div className="config-row">
          <label>列数:</label>
          <input
            type="number"
            value={config.gridColumns}
            onChange={(e) => onConfigChange({ gridColumns: parseInt(e.target.value) })}
            min="1"
            max="24"
          />
        </div>
        <div className="config-row">
          <label>行数:</label>
          <input
            type="number"
            value={config.gridRows}
            onChange={(e) => onConfigChange({ gridRows: parseInt(e.target.value) })}
            min="1"
            max="24"
          />
        </div>
        <div className="config-row">
          <label>列宽:</label>
          <input
            type="number"
            value={config.columnWidth}
            onChange={(e) => onConfigChange({ columnWidth: parseInt(e.target.value) })}
            min="20"
            max="200"
          />
          <span className="unit">px</span>
        </div>
        <div className="config-row">
          <label>行高:</label>
          <input
            type="number"
            value={config.rowHeight}
            onChange={(e) => onConfigChange({ rowHeight: parseInt(e.target.value) })}
            min="20"
            max="200"
          />
          <span className="unit">px</span>
        </div>
        <div className="config-row">
          <label>间距:</label>
          <input
            type="number"
            value={config.gap}
            onChange={(e) => onConfigChange({ gap: parseInt(e.target.value) })}
            min="0"
            max="50"
          />
          <span className="unit">px</span>
        </div>
      </div>

      <div className="config-section">
        <h4>约束设置</h4>
        <div className="config-row">
          <label>最小列跨度:</label>
          <input
            type="number"
            value={config.minColumnSpan}
            onChange={(e) => onConfigChange({ minColumnSpan: parseInt(e.target.value) })}
            min="1"
            max={config.maxColumnSpan || config.gridColumns}
          />
        </div>
        <div className="config-row">
          <label>最大列跨度:</label>
          <input
            type="number"
            value={config.maxColumnSpan || config.gridColumns}
            onChange={(e) => onConfigChange({ maxColumnSpan: parseInt(e.target.value) })}
            min={config.minColumnSpan}
            max={config.gridColumns}
          />
        </div>
        <div className="config-row">
          <label>最小行跨度:</label>
          <input
            type="number"
            value={config.minRowSpan}
            onChange={(e) => onConfigChange({ minRowSpan: parseInt(e.target.value) })}
            min="1"
            max={config.maxRowSpan || config.gridRows}
          />
        </div>
        <div className="config-row">
          <label>最大行跨度:</label>
          <input
            type="number"
            value={config.maxRowSpan || config.gridRows}
            onChange={(e) => onConfigChange({ maxRowSpan: parseInt(e.target.value) })}
            min={config.minRowSpan}
            max={config.gridRows}
          />
        </div>
      </div>
    </div>
  );
};

/**
 * 手柄配置面板
 */
interface HandleConfigPanelProps {
  config: CrossColumnDragConfig;
  onConfigChange: (config: Partial<CrossColumnDragConfig>) => void;
}

const HandleConfigPanel: React.FC<HandleConfigPanelProps> = ({ config, onConfigChange }) => {
  return (
    <div className="handle-config-panel">
      <div className="config-section">
        <h4>手柄外观</h4>
        <div className="config-row">
          <label>手柄大小:</label>
          <input
            type="number"
            value={config.handleSize}
            onChange={(e) => onConfigChange({ handleSize: parseInt(e.target.value) })}
            min="4"
            max="20"
          />
          <span className="unit">px</span>
        </div>
        <div className="config-row">
          <label>默认颜色:</label>
          <input
            type="color"
            value={config.handleColor}
            onChange={(e) => onConfigChange({ handleColor: e.target.value })}
          />
        </div>
        <div className="config-row">
          <label>悬停颜色:</label>
          <input
            type="color"
            value={config.handleHoverColor}
            onChange={(e) => onConfigChange({ handleHoverColor: e.target.value })}
          />
        </div>
        <div className="config-row">
          <label>激活颜色:</label>
          <input
            type="color"
            value={config.handleActiveColor}
            onChange={(e) => onConfigChange({ handleActiveColor: e.target.value })}
          />
        </div>
      </div>
    </div>
  );
};

/**
 * 行为配置面板
 */
interface BehaviorConfigPanelProps {
  config: CrossColumnDragConfig;
  onConfigChange: (config: Partial<CrossColumnDragConfig>) => void;
}

const BehaviorConfigPanel: React.FC<BehaviorConfigPanelProps> = ({ config, onConfigChange }) => {
  return (
    <div className="behavior-config-panel">
      <div className="config-section">
        <h4>拖拽行为</h4>
        <div className="config-row">
          <label>
            <input
              type="checkbox"
              checked={config.snapToGrid}
              onChange={(e) => onConfigChange({ snapToGrid: e.target.checked })}
            />
            吸附到网格
          </label>
        </div>
        <div className="config-row">
          <label>
            <input
              type="checkbox"
              checked={config.maintainAspectRatio}
              onChange={(e) => onConfigChange({ maintainAspectRatio: e.target.checked })}
            />
            保持宽高比
          </label>
        </div>
        <div className="config-row">
          <label>
            <input
              type="checkbox"
              checked={config.allowOverlap}
              onChange={(e) => onConfigChange({ allowOverlap: e.target.checked })}
            />
            允许重叠
          </label>
        </div>
      </div>

      <div className="config-section">
        <h4>视觉反馈</h4>
        <div className="config-row">
          <label>
            <input
              type="checkbox"
              checked={config.showPreview}
              onChange={(e) => onConfigChange({ showPreview: e.target.checked })}
            />
            显示预览
          </label>
        </div>
        <div className="config-row">
          <label>
            <input
              type="checkbox"
              checked={config.showGuidelines}
              onChange={(e) => onConfigChange({ showGuidelines: e.target.checked })}
            />
            显示指导线
          </label>
        </div>
        <div className="config-row">
          <label>
            <input
              type="checkbox"
              checked={config.showDimensions}
              onChange={(e) => onConfigChange({ showDimensions: e.target.checked })}
            />
            显示尺寸信息
          </label>
        </div>
        <div className="config-row">
          <label>
            <input
              type="checkbox"
              checked={config.highlightAffectedCells}
              onChange={(e) => onConfigChange({ highlightAffectedCells: e.target.checked })}
            />
            高亮受影响单元格
          </label>
        </div>
      </div>
    </div>
  );
};

/**
 * 目标管理面板
 */
interface TargetsConfigPanelProps {
  targets: ResizeTarget[];
  onTargetsChange: (targets: ResizeTarget[]) => void;
}

const TargetsConfigPanel: React.FC<TargetsConfigPanelProps> = ({ targets, onTargetsChange }) => {
  const [selectedTargetId, setSelectedTargetId] = useState<string | null>(null);

  const selectedTarget = targets.find((t) => t.id === selectedTargetId);

  const updateTarget = useCallback(
    (targetId: string, updates: Partial<ResizeTarget>) => {
      const updatedTargets = targets.map((t) => (t.id === targetId ? { ...t, ...updates } : t));
      onTargetsChange(updatedTargets);
    },
    [targets, onTargetsChange],
  );

  const removeTarget = useCallback(
    (targetId: string) => {
      const updatedTargets = targets.filter((t) => t.id !== targetId);
      onTargetsChange(updatedTargets);
      if (selectedTargetId === targetId) {
        setSelectedTargetId(null);
      }
    },
    [targets, onTargetsChange, selectedTargetId],
  );

  return (
    <div className="targets-config-panel">
      <div className="config-section">
        <h4>目标列表</h4>
        <div className="targets-list">
          {targets.map((target) => (
            <div
              key={target.id}
              className={`target-item ${selectedTargetId === target.id ? 'selected' : ''}`}
              onClick={() => setSelectedTargetId(target.id)}
            >
              <div className="target-info">
                <span className="target-id">{target.id}</span>
                <span className="target-area">
                  {target.gridArea.columnStart}-{target.gridArea.columnEnd} ×{' '}
                  {target.gridArea.rowStart}-{target.gridArea.rowEnd}
                </span>
              </div>
              <button
                className="remove-target-btn"
                onClick={(e) => {
                  e.stopPropagation();
                  removeTarget(target.id);
                }}
              >
                ×
              </button>
            </div>
          ))}
          {targets.length === 0 && <div className="empty-targets">暂无调整目标</div>}
        </div>
      </div>

      {selectedTarget && (
        <div className="config-section">
          <h4>目标配置 - {selectedTarget.id}</h4>
          <div className="config-row">
            <label>最小宽度:</label>
            <input
              type="number"
              value={selectedTarget.minWidth}
              onChange={(e) =>
                updateTarget(selectedTarget.id, { minWidth: parseInt(e.target.value) })
              }
              min="20"
            />
            <span className="unit">px</span>
          </div>
          <div className="config-row">
            <label>最小高度:</label>
            <input
              type="number"
              value={selectedTarget.minHeight}
              onChange={(e) =>
                updateTarget(selectedTarget.id, { minHeight: parseInt(e.target.value) })
              }
              min="20"
            />
            <span className="unit">px</span>
          </div>
          <div className="config-row">
            <label>最大宽度:</label>
            <input
              type="number"
              value={selectedTarget.maxWidth || ''}
              onChange={(e) =>
                updateTarget(selectedTarget.id, {
                  maxWidth: e.target.value ? parseInt(e.target.value) : undefined,
                })
              }
              min={selectedTarget.minWidth}
              placeholder="无限制"
            />
            <span className="unit">px</span>
          </div>
          <div className="config-row">
            <label>最大高度:</label>
            <input
              type="number"
              value={selectedTarget.maxHeight || ''}
              onChange={(e) =>
                updateTarget(selectedTarget.id, {
                  maxHeight: e.target.value ? parseInt(e.target.value) : undefined,
                })
              }
              min={selectedTarget.minHeight}
              placeholder="无限制"
            />
            <span className="unit">px</span>
          </div>
          <div className="config-row">
            <label>宽高比:</label>
            <input
              type="number"
              step="0.1"
              value={selectedTarget.aspectRatio || ''}
              onChange={(e) =>
                updateTarget(selectedTarget.id, {
                  aspectRatio: e.target.value ? parseFloat(e.target.value) : undefined,
                })
              }
              min="0.1"
              placeholder="自由"
            />
          </div>
          <div className="config-row">
            <label>
              <input
                type="checkbox"
                checked={selectedTarget.resizable.column}
                onChange={(e) =>
                  updateTarget(selectedTarget.id, {
                    resizable: { ...selectedTarget.resizable, column: e.target.checked },
                  })
                }
              />
              可调整列宽
            </label>
          </div>
          <div className="config-row">
            <label>
              <input
                type="checkbox"
                checked={selectedTarget.resizable.row}
                onChange={(e) =>
                  updateTarget(selectedTarget.id, {
                    resizable: { ...selectedTarget.resizable, row: e.target.checked },
                  })
                }
              />
              可调整行高
            </label>
          </div>
        </div>
      )}
    </div>
  );
};

/**
 * 跨列拖拽工具栏
 */
interface CrossColumnDragToolbarProps {
  onResetConfig: () => void;
  onClearTargets: () => void;
  onExportConfig: () => void;
  onImportConfig: () => void;
}

const CrossColumnDragToolbar: React.FC<CrossColumnDragToolbarProps> = ({
  onResetConfig,
  onClearTargets,
  onExportConfig,
  onImportConfig,
}) => {
  return (
    <div className="cross-column-drag-toolbar">
      <div className="toolbar-group">
        <button onClick={onResetConfig}>重置配置</button>
        <button onClick={onClearTargets}>清除目标</button>
      </div>
      <div className="toolbar-group">
        <button onClick={onExportConfig}>导出配置</button>
        <button onClick={onImportConfig}>导入配置</button>
      </div>
    </div>
  );
};

/**
 * 跨列拖拽配置面板主组件
 */
export const CrossColumnDragPanel: React.FC<CrossColumnDragPanelProps> = ({
  config,
  targets = [],
  onConfigChange,
  onTargetsChange,
  className = '',
}) => {
  const [activeTab, setActiveTab] = useState<'basic' | 'handles' | 'behavior' | 'targets'>('basic');
  const { resetConfig, applyPreset: _applyPreset } = useCrossColumnDragConfig(config);

  const handleResetConfig = useCallback(() => {
    resetConfig();
    onConfigChange(config);
  }, [resetConfig, config, onConfigChange]);

  const handleClearTargets = useCallback(() => {
    if (onTargetsChange) {
      onTargetsChange([]);
    }
  }, [onTargetsChange]);

  const handleExportConfig = useCallback(() => {
    const configData = {
      config,
      targets,
    };
    const dataStr = JSON.stringify(configData, null, 2);
    const dataBlob = new Blob([dataStr], { type: 'application/json' });
    const url = URL.createObjectURL(dataBlob);
    const link = document.createElement('a');
    link.href = url;
    link.download = 'cross-column-drag-config.json';
    link.click();
    URL.revokeObjectURL(url);
  }, [config, targets]);

  const handleImportConfig = useCallback(() => {
    const input = document.createElement('input');
    input.type = 'file';
    input.accept = '.json';
    input.onchange = (e) => {
      const file = (e.target as HTMLInputElement).files?.[0];
      if (file) {
        const reader = new FileReader();
        reader.onload = (e) => {
          try {
            const configData = JSON.parse(e.target?.result as string);
            if (configData.config) {
              onConfigChange(configData.config);
            }
            if (configData.targets && onTargetsChange) {
              onTargetsChange(configData.targets);
            }
          } catch (error) {
            console.error('Failed to import config:', error);
          }
        };
        reader.readAsText(file);
      }
    };
    input.click();
  }, [onConfigChange, onTargetsChange]);

  return (
    <div className={`cross-column-drag-panel ${className}`}>
      <div className="panel-header">
        <h3>跨列拖拽调整</h3>
        <CrossColumnDragToolbar
          onResetConfig={handleResetConfig}
          onClearTargets={handleClearTargets}
          onExportConfig={handleExportConfig}
          onImportConfig={handleImportConfig}
        />
      </div>

      <div className="panel-tabs">
        <button
          className={`tab-button ${activeTab === 'basic' ? 'active' : ''}`}
          onClick={() => setActiveTab('basic')}
        >
          基础设置
        </button>
        <button
          className={`tab-button ${activeTab === 'handles' ? 'active' : ''}`}
          onClick={() => setActiveTab('handles')}
        >
          手柄配置
        </button>
        <button
          className={`tab-button ${activeTab === 'behavior' ? 'active' : ''}`}
          onClick={() => setActiveTab('behavior')}
        >
          行为设置
        </button>
        <button
          className={`tab-button ${activeTab === 'targets' ? 'active' : ''}`}
          onClick={() => setActiveTab('targets')}
        >
          目标管理
        </button>
      </div>

      <div className="panel-content">
        {activeTab === 'basic' && (
          <BasicConfigPanel config={config} onConfigChange={onConfigChange} />
        )}
        {activeTab === 'handles' && (
          <HandleConfigPanel config={config} onConfigChange={onConfigChange} />
        )}
        {activeTab === 'behavior' && (
          <BehaviorConfigPanel config={config} onConfigChange={onConfigChange} />
        )}
        {activeTab === 'targets' && onTargetsChange && (
          <TargetsConfigPanel targets={targets} onTargetsChange={onTargetsChange} />
        )}
      </div>
    </div>
  );
};
