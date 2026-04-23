/**
 * 跨列拖拽调整引擎
 * 支持通过拖拽调整组件的列宽和行高
 */

export interface ResizeHandle {
  id: string;
  type: 'column' | 'row' | 'corner';
  position: 'start' | 'end' | 'both';
  element: HTMLElement;
  bounds: DOMRect;
  cursor: string;
}

export interface ResizeTarget {
  id: string;
  element: HTMLElement;
  gridArea: {
    columnStart: number;
    columnEnd: number;
    rowStart: number;
    rowEnd: number;
  };
  minWidth: number;
  minHeight: number;
  maxWidth?: number;
  maxHeight?: number;
  aspectRatio?: number;
  resizable: {
    column: boolean;
    row: boolean;
  };
}

export interface ResizeOperation {
  targetId: string;
  handle: ResizeHandle;
  startPosition: { x: number; y: number };
  startArea: {
    columnStart: number;
    columnEnd: number;
    rowStart: number;
    rowEnd: number;
  };
  currentArea: {
    columnStart: number;
    columnEnd: number;
    rowStart: number;
    rowEnd: number;
  };
  constraints: {
    minColumns: number;
    maxColumns?: number;
    minRows: number;
    maxRows?: number;
  };
}

export interface CrossColumnDragConfig {
  // 调整手柄配置
  handleSize: number;
  handleColor: string;
  handleHoverColor: string;
  handleActiveColor: string;

  // 网格配置
  gridColumns: number;
  gridRows: number;
  columnWidth: number;
  rowHeight: number;
  gap: number;

  // 约束配置
  minColumnSpan: number;
  minRowSpan: number;
  maxColumnSpan?: number;
  maxRowSpan?: number;

  // 行为配置
  showPreview: boolean;
  snapToGrid: boolean;
  maintainAspectRatio: boolean;
  allowOverlap: boolean;

  // 视觉反馈
  showGuidelines: boolean;
  showDimensions: boolean;
  highlightAffectedCells: boolean;
}

export interface CrossColumnDragResult {
  targetId: string;
  oldArea: {
    columnStart: number;
    columnEnd: number;
    rowStart: number;
    rowEnd: number;
  };
  newArea: {
    columnStart: number;
    columnEnd: number;
    rowStart: number;
    rowEnd: number;
  };
  affectedCells: Array<{ column: number; row: number }>;
  conflicts: string[];
}

export interface CrossColumnDragEngineEvents {
  onResizeStart: (operation: ResizeOperation) => void;
  onResizeMove: (operation: ResizeOperation, result: CrossColumnDragResult) => void;
  onResizeEnd: (result: CrossColumnDragResult) => void;
  onHandleHover: (handle: ResizeHandle | null) => void;
  onConflictDetected: (conflicts: string[]) => void;
}

export class CrossColumnDragEngine {
  private config: CrossColumnDragConfig;
  private container: HTMLElement | null = null;
  private targets: Map<string, ResizeTarget> = new Map();
  private handles: Map<string, ResizeHandle[]> = new Map();
  private currentOperation: ResizeOperation | null = null;
  private previewElement: HTMLElement | null = null;
  private guidelineElements: HTMLElement[] = [];
  private dimensionElement: HTMLElement | null = null;
  private events: Partial<CrossColumnDragEngineEvents> = {};

  constructor(config: CrossColumnDragConfig, events?: Partial<CrossColumnDragEngineEvents>) {
    this.config = config;
    this.events = events || {};
    this.setupEventListeners();
  }

  /**
   * 初始化容器
   */
  initialize(container: HTMLElement): void {
    this.container = container;
    this.container.style.position = 'relative';
    this.setupContainerStyles();
  }

  /**
   * 添加调整目标
   */
  addTarget(target: ResizeTarget): void {
    this.targets.set(target.id, target);
    this.createHandles(target);
    this.updateHandlePositions(target.id);
  }

  /**
   * 移除调整目标
   */
  removeTarget(targetId: string): void {
    this.removeHandles(targetId);
    this.targets.delete(targetId);
  }

  /**
   * 更新目标
   */
  updateTarget(targetId: string, updates: Partial<ResizeTarget>): void {
    const target = this.targets.get(targetId);
    if (target) {
      Object.assign(target, updates);
      this.updateHandlePositions(targetId);
    }
  }

  /**
   * 更新配置
   */
  updateConfig(updates: Partial<CrossColumnDragConfig>): void {
    Object.assign(this.config, updates);
    this.updateAllHandles();
  }

  /**
   * 获取配置
   */
  getConfig(): CrossColumnDragConfig {
    return { ...this.config };
  }

  /**
   * 获取所有目标
   */
  getTargets(): ResizeTarget[] {
    return Array.from(this.targets.values());
  }

  /**
   * 获取当前操作
   */
  getCurrentOperation(): ResizeOperation | null {
    return this.currentOperation;
  }

  /**
   * 销毁引擎
   */
  destroy(): void {
    this.clearAllHandles();
    this.clearPreview();
    this.clearGuidelines();
    this.clearDimensions();
    this.removeEventListeners();
    this.targets.clear();
    this.handles.clear();
  }

  /**
   * 设置容器样式
   */
  private setupContainerStyles(): void {
    if (!this.container) return;

    this.container.style.userSelect = 'none';
    this.container.classList.add('cross-column-drag-container');
  }

  /**
   * 创建调整手柄
   */
  private createHandles(target: ResizeTarget): void {
    const handles: ResizeHandle[] = [];

    // 创建列调整手柄
    if (target.resizable.column) {
      // 右边缘手柄
      const rightHandle = this.createHandle(target.id, 'column', 'end');
      handles.push(rightHandle);

      // 左边缘手柄（如果不是第一列）
      if (target.gridArea.columnStart > 1) {
        const leftHandle = this.createHandle(target.id, 'column', 'start');
        handles.push(leftHandle);
      }
    }

    // 创建行调整手柄
    if (target.resizable.row) {
      // 下边缘手柄
      const bottomHandle = this.createHandle(target.id, 'row', 'end');
      handles.push(bottomHandle);

      // 上边缘手柄（如果不是第一行）
      if (target.gridArea.rowStart > 1) {
        const topHandle = this.createHandle(target.id, 'row', 'start');
        handles.push(topHandle);
      }
    }

    // 创建角落手柄
    if (target.resizable.column && target.resizable.row) {
      const cornerHandle = this.createHandle(target.id, 'corner', 'both');
      handles.push(cornerHandle);
    }

    this.handles.set(target.id, handles);
  }

  /**
   * 创建单个手柄
   */
  private createHandle(
    targetId: string,
    type: ResizeHandle['type'],
    position: ResizeHandle['position'],
  ): ResizeHandle {
    const handleId = `${targetId}-${type}-${position}`;
    const element = document.createElement('div');

    element.className = `resize-handle resize-handle-${type} resize-handle-${position}`;
    element.style.position = 'absolute';
    element.style.zIndex = '1000';
    element.style.backgroundColor = this.config.handleColor;
    element.style.transition = 'all 0.2s ease';

    // 设置手柄样式
    this.setupHandleStyles(element, type, position);

    // 添加事件监听
    this.setupHandleEvents(element, handleId);

    if (this.container) {
      this.container.appendChild(element);
    }

    const handle: ResizeHandle = {
      id: handleId,
      type,
      position,
      element,
      bounds: element.getBoundingClientRect(),
      cursor: this.getHandleCursor(type, position),
    };

    return handle;
  }

  /**
   * 设置手柄样式
   */
  private setupHandleStyles(
    element: HTMLElement,
    type: ResizeHandle['type'],
    _position: ResizeHandle['position'],
  ): void {
    const size = this.config.handleSize;

    switch (type) {
      case 'column':
        element.style.width = `${size}px`;
        element.style.height = '100%';
        element.style.cursor = 'col-resize';
        break;
      case 'row':
        element.style.width = '100%';
        element.style.height = `${size}px`;
        element.style.cursor = 'row-resize';
        break;
      case 'corner':
        element.style.width = `${size}px`;
        element.style.height = `${size}px`;
        element.style.cursor = 'nw-resize';
        break;
    }
  }

  /**
   * 设置手柄事件
   */
  private setupHandleEvents(element: HTMLElement, handleId: string): void {
    element.addEventListener('mouseenter', () => {
      element.style.backgroundColor = this.config.handleHoverColor;
      const handle = this.findHandleById(handleId);
      this.events.onHandleHover?.(handle);
    });

    element.addEventListener('mouseleave', () => {
      if (!this.currentOperation || this.currentOperation.handle.id !== handleId) {
        element.style.backgroundColor = this.config.handleColor;
        this.events.onHandleHover?.(null);
      }
    });

    element.addEventListener('mousedown', (e) => {
      e.preventDefault();
      e.stopPropagation();
      this.startResize(handleId, { x: e.clientX, y: e.clientY });
    });
  }

  /**
   * 获取手柄光标样式
   */
  private getHandleCursor(type: ResizeHandle['type'], _position: ResizeHandle['position']): string {
    switch (type) {
      case 'column':
        return 'col-resize';
      case 'row':
        return 'row-resize';
      case 'corner':
        return 'nw-resize';
      default:
        return 'default';
    }
  }

  /**
   * 更新手柄位置
   */
  private updateHandlePositions(targetId: string): void {
    const target = this.targets.get(targetId);
    const handles = this.handles.get(targetId);

    if (!target || !handles || !this.container) return;

    const containerRect = this.container.getBoundingClientRect();
    const targetRect = target.element.getBoundingClientRect();

    handles.forEach((handle) => {
      const { type, position } = handle;

      switch (type) {
        case 'column':
          if (position === 'end') {
            handle.element.style.left = `${targetRect.right - containerRect.left - this.config.handleSize / 2}px`;
            handle.element.style.top = `${targetRect.top - containerRect.top}px`;
            handle.element.style.height = `${targetRect.height}px`;
          } else if (position === 'start') {
            handle.element.style.left = `${targetRect.left - containerRect.left - this.config.handleSize / 2}px`;
            handle.element.style.top = `${targetRect.top - containerRect.top}px`;
            handle.element.style.height = `${targetRect.height}px`;
          }
          break;
        case 'row':
          if (position === 'end') {
            handle.element.style.left = `${targetRect.left - containerRect.left}px`;
            handle.element.style.top = `${targetRect.bottom - containerRect.top - this.config.handleSize / 2}px`;
            handle.element.style.width = `${targetRect.width}px`;
          } else if (position === 'start') {
            handle.element.style.left = `${targetRect.left - containerRect.left}px`;
            handle.element.style.top = `${targetRect.top - containerRect.top - this.config.handleSize / 2}px`;
            handle.element.style.width = `${targetRect.width}px`;
          }
          break;
        case 'corner':
          handle.element.style.left = `${targetRect.right - containerRect.left - this.config.handleSize / 2}px`;
          handle.element.style.top = `${targetRect.bottom - containerRect.top - this.config.handleSize / 2}px`;
          break;
      }

      handle.bounds = handle.element.getBoundingClientRect();
    });
  }

  /**
   * 更新所有手柄
   */
  private updateAllHandles(): void {
    this.targets.forEach((_, targetId) => {
      this.updateHandlePositions(targetId);
    });
  }

  /**
   * 移除手柄
   */
  private removeHandles(targetId: string): void {
    const handles = this.handles.get(targetId);
    if (handles) {
      handles.forEach((handle) => {
        if (handle.element.parentNode) {
          handle.element.parentNode.removeChild(handle.element);
        }
      });
      this.handles.delete(targetId);
    }
  }

  /**
   * 清除所有手柄
   */
  private clearAllHandles(): void {
    this.handles.forEach((handles) => {
      handles.forEach((handle) => {
        if (handle.element.parentNode) {
          handle.element.parentNode.removeChild(handle.element);
        }
      });
    });
    this.handles.clear();
  }

  /**
   * 根据ID查找手柄
   */
  private findHandleById(handleId: string): ResizeHandle | null {
    for (const handles of this.handles.values()) {
      const handle = handles.find((h) => h.id === handleId);
      if (handle) return handle;
    }
    return null;
  }

  /**
   * 开始调整
   */
  private startResize(handleId: string, startPosition: { x: number; y: number }): void {
    const handle = this.findHandleById(handleId);
    if (!handle) return;

    const targetId = handleId.split('-')[0];
    const target = this.targets.get(targetId);
    if (!target) return;

    handle.element.style.backgroundColor = this.config.handleActiveColor;

    this.currentOperation = {
      targetId,
      handle,
      startPosition,
      startArea: { ...target.gridArea },
      currentArea: { ...target.gridArea },
      constraints: {
        minColumns: this.config.minColumnSpan,
        maxColumns: this.config.maxColumnSpan,
        minRows: this.config.minRowSpan,
        maxRows: this.config.maxRowSpan,
      },
    };

    if (this.config.showPreview) {
      this.createPreview(target);
    }

    this.events.onResizeStart?.(this.currentOperation);
  }

  /**
   * 处理调整移动
   */
  private handleResizeMove(position: { x: number; y: number }): void {
    if (!this.currentOperation) return;

    const { handle, startPosition, startArea } = this.currentOperation;
    const deltaX = position.x - startPosition.x;
    const deltaY = position.y - startPosition.y;

    // 计算新的网格区域
    const newArea = this.calculateNewArea(handle, startArea, deltaX, deltaY);

    // 应用约束
    const constrainedArea = this.applyConstraints(newArea, this.currentOperation.constraints);

    this.currentOperation.currentArea = constrainedArea;

    // 检测冲突
    const conflicts = this.detectConflicts(this.currentOperation.targetId, constrainedArea);

    const result: CrossColumnDragResult = {
      targetId: this.currentOperation.targetId,
      oldArea: startArea,
      newArea: constrainedArea,
      affectedCells: this.getAffectedCells(startArea, constrainedArea),
      conflicts,
    };

    // 更新预览
    if (this.config.showPreview && this.previewElement) {
      this.updatePreview(constrainedArea);
    }

    // 显示指导线
    if (this.config.showGuidelines) {
      this.updateGuidelines(constrainedArea);
    }

    // 显示尺寸信息
    if (this.config.showDimensions) {
      this.updateDimensions(constrainedArea);
    }

    this.events.onResizeMove?.(this.currentOperation, result);

    if (conflicts.length > 0) {
      this.events.onConflictDetected?.(conflicts);
    }
  }

  /**
   * 结束调整
   */
  private endResize(): void {
    if (!this.currentOperation) return;

    const result: CrossColumnDragResult = {
      targetId: this.currentOperation.targetId,
      oldArea: this.currentOperation.startArea,
      newArea: this.currentOperation.currentArea,
      affectedCells: this.getAffectedCells(
        this.currentOperation.startArea,
        this.currentOperation.currentArea,
      ),
      conflicts: this.detectConflicts(
        this.currentOperation.targetId,
        this.currentOperation.currentArea,
      ),
    };

    // 恢复手柄颜色
    this.currentOperation.handle.element.style.backgroundColor = this.config.handleColor;

    this.clearPreview();
    this.clearGuidelines();
    this.clearDimensions();

    this.events.onResizeEnd?.(result);
    this.currentOperation = null;
  }

  /**
   * 计算新的网格区域
   */
  private calculateNewArea(
    handle: ResizeHandle,
    startArea: ResizeOperation['startArea'],
    deltaX: number,
    deltaY: number,
  ): ResizeOperation['currentArea'] {
    const newArea = { ...startArea };
    const columnDelta = Math.round(deltaX / (this.config.columnWidth + this.config.gap));
    const rowDelta = Math.round(deltaY / (this.config.rowHeight + this.config.gap));

    switch (handle.type) {
      case 'column':
        if (handle.position === 'end') {
          newArea.columnEnd = Math.max(
            startArea.columnStart + 1,
            startArea.columnEnd + columnDelta,
          );
        } else if (handle.position === 'start') {
          newArea.columnStart = Math.min(
            startArea.columnEnd - 1,
            startArea.columnStart + columnDelta,
          );
        }
        break;
      case 'row':
        if (handle.position === 'end') {
          newArea.rowEnd = Math.max(startArea.rowStart + 1, startArea.rowEnd + rowDelta);
        } else if (handle.position === 'start') {
          newArea.rowStart = Math.min(startArea.rowEnd - 1, startArea.rowStart + rowDelta);
        }
        break;
      case 'corner':
        newArea.columnEnd = Math.max(startArea.columnStart + 1, startArea.columnEnd + columnDelta);
        newArea.rowEnd = Math.max(startArea.rowStart + 1, startArea.rowEnd + rowDelta);
        break;
    }

    return newArea;
  }

  /**
   * 应用约束
   */
  private applyConstraints(
    area: ResizeOperation['currentArea'],
    constraints: ResizeOperation['constraints'],
  ): ResizeOperation['currentArea'] {
    const constrainedArea = { ...area };

    // 应用最小/最大列约束
    const columnSpan = constrainedArea.columnEnd - constrainedArea.columnStart;
    if (columnSpan < constraints.minColumns) {
      constrainedArea.columnEnd = constrainedArea.columnStart + constraints.minColumns;
    }
    if (constraints.maxColumns && columnSpan > constraints.maxColumns) {
      constrainedArea.columnEnd = constrainedArea.columnStart + constraints.maxColumns;
    }

    // 应用最小/最大行约束
    const rowSpan = constrainedArea.rowEnd - constrainedArea.rowStart;
    if (rowSpan < constraints.minRows) {
      constrainedArea.rowEnd = constrainedArea.rowStart + constraints.minRows;
    }
    if (constraints.maxRows && rowSpan > constraints.maxRows) {
      constrainedArea.rowEnd = constrainedArea.rowStart + constraints.maxRows;
    }

    // 应用网格边界约束
    constrainedArea.columnStart = Math.max(1, constrainedArea.columnStart);
    constrainedArea.columnEnd = Math.min(this.config.gridColumns + 1, constrainedArea.columnEnd);
    constrainedArea.rowStart = Math.max(1, constrainedArea.rowStart);
    constrainedArea.rowEnd = Math.min(this.config.gridRows + 1, constrainedArea.rowEnd);

    return constrainedArea;
  }

  /**
   * 检测冲突
   */
  private detectConflicts(targetId: string, area: ResizeOperation['currentArea']): string[] {
    if (this.config.allowOverlap) return [];

    const conflicts: string[] = [];

    this.targets.forEach((target, id) => {
      if (id === targetId) return;

      const { gridArea } = target;

      // 检查是否有重叠
      const hasOverlap = !(
        area.columnEnd <= gridArea.columnStart ||
        area.columnStart >= gridArea.columnEnd ||
        area.rowEnd <= gridArea.rowStart ||
        area.rowStart >= gridArea.rowEnd
      );

      if (hasOverlap) {
        conflicts.push(id);
      }
    });

    return conflicts;
  }

  /**
   * 获取受影响的单元格
   */
  private getAffectedCells(
    oldArea: ResizeOperation['startArea'],
    newArea: ResizeOperation['currentArea'],
  ): Array<{ column: number; row: number }> {
    const cells: Array<{ column: number; row: number }> = [];

    const minColumn = Math.min(oldArea.columnStart, newArea.columnStart);
    const maxColumn = Math.max(oldArea.columnEnd, newArea.columnEnd);
    const minRow = Math.min(oldArea.rowStart, newArea.rowStart);
    const maxRow = Math.max(oldArea.rowEnd, newArea.rowEnd);

    for (let column = minColumn; column < maxColumn; column++) {
      for (let row = minRow; row < maxRow; row++) {
        cells.push({ column, row });
      }
    }

    return cells;
  }

  /**
   * 创建预览
   */
  private createPreview(_target: ResizeTarget): void {
    if (!this.container) return;

    this.previewElement = document.createElement('div');
    this.previewElement.className = 'resize-preview';
    this.previewElement.style.position = 'absolute';
    this.previewElement.style.border = '2px dashed #007acc';
    this.previewElement.style.backgroundColor = 'rgba(0, 122, 204, 0.1)';
    this.previewElement.style.pointerEvents = 'none';
    this.previewElement.style.zIndex = '999';

    this.container.appendChild(this.previewElement);
  }

  /**
   * 更新预览
   */
  private updatePreview(area: ResizeOperation['currentArea']): void {
    if (!this.previewElement || !this.container) return;

    const left = (area.columnStart - 1) * (this.config.columnWidth + this.config.gap);
    const top = (area.rowStart - 1) * (this.config.rowHeight + this.config.gap);
    const width =
      (area.columnEnd - area.columnStart) * this.config.columnWidth +
      (area.columnEnd - area.columnStart - 1) * this.config.gap;
    const height =
      (area.rowEnd - area.rowStart) * this.config.rowHeight +
      (area.rowEnd - area.rowStart - 1) * this.config.gap;

    this.previewElement.style.left = `${left}px`;
    this.previewElement.style.top = `${top}px`;
    this.previewElement.style.width = `${width}px`;
    this.previewElement.style.height = `${height}px`;
  }

  /**
   * 清除预览
   */
  private clearPreview(): void {
    if (this.previewElement && this.previewElement.parentNode) {
      this.previewElement.parentNode.removeChild(this.previewElement);
      this.previewElement = null;
    }
  }

  /**
   * 更新指导线
   */
  private updateGuidelines(area: ResizeOperation['currentArea']): void {
    this.clearGuidelines();

    if (!this.container) return;

    // 创建垂直指导线
    const leftGuideline = this.createGuideline(
      'vertical',
      (area.columnStart - 1) * (this.config.columnWidth + this.config.gap),
    );
    const rightGuideline = this.createGuideline(
      'vertical',
      area.columnEnd * (this.config.columnWidth + this.config.gap) - this.config.gap,
    );

    // 创建水平指导线
    const topGuideline = this.createGuideline(
      'horizontal',
      (area.rowStart - 1) * (this.config.rowHeight + this.config.gap),
    );
    const bottomGuideline = this.createGuideline(
      'horizontal',
      area.rowEnd * (this.config.rowHeight + this.config.gap) - this.config.gap,
    );

    this.guidelineElements.push(leftGuideline, rightGuideline, topGuideline, bottomGuideline);
  }

  /**
   * 创建指导线
   */
  private createGuideline(type: 'vertical' | 'horizontal', position: number): HTMLElement {
    const guideline = document.createElement('div');
    guideline.className = `resize-guideline resize-guideline-${type}`;
    guideline.style.position = 'absolute';
    guideline.style.backgroundColor = '#007acc';
    guideline.style.pointerEvents = 'none';
    guideline.style.zIndex = '998';

    if (type === 'vertical') {
      guideline.style.left = `${position}px`;
      guideline.style.top = '0';
      guideline.style.width = '1px';
      guideline.style.height = '100%';
    } else {
      guideline.style.left = '0';
      guideline.style.top = `${position}px`;
      guideline.style.width = '100%';
      guideline.style.height = '1px';
    }

    if (this.container) {
      this.container.appendChild(guideline);
    }

    return guideline;
  }

  /**
   * 清除指导线
   */
  private clearGuidelines(): void {
    this.guidelineElements.forEach((guideline) => {
      if (guideline.parentNode) {
        guideline.parentNode.removeChild(guideline);
      }
    });
    this.guidelineElements = [];
  }

  /**
   * 更新尺寸信息
   */
  private updateDimensions(area: ResizeOperation['currentArea']): void {
    if (!this.dimensionElement) {
      this.createDimensionElement();
    }

    if (!this.dimensionElement || !this.container) return;

    const columnSpan = area.columnEnd - area.columnStart;
    const rowSpan = area.rowEnd - area.rowStart;
    const width = columnSpan * this.config.columnWidth + (columnSpan - 1) * this.config.gap;
    const height = rowSpan * this.config.rowHeight + (rowSpan - 1) * this.config.gap;

    this.dimensionElement.textContent = `${columnSpan} × ${rowSpan} (${width}px × ${height}px)`;

    const left = (area.columnStart - 1) * (this.config.columnWidth + this.config.gap) + width / 2;
    const top = (area.rowStart - 1) * (this.config.rowHeight + this.config.gap) + height / 2;

    this.dimensionElement.style.left = `${left}px`;
    this.dimensionElement.style.top = `${top}px`;
  }

  /**
   * 创建尺寸元素
   */
  private createDimensionElement(): void {
    if (!this.container) return;

    this.dimensionElement = document.createElement('div');
    this.dimensionElement.className = 'resize-dimensions';
    this.dimensionElement.style.position = 'absolute';
    this.dimensionElement.style.backgroundColor = 'rgba(0, 0, 0, 0.8)';
    this.dimensionElement.style.color = 'white';
    this.dimensionElement.style.padding = '4px 8px';
    this.dimensionElement.style.borderRadius = '4px';
    this.dimensionElement.style.fontSize = '12px';
    this.dimensionElement.style.fontFamily = 'monospace';
    this.dimensionElement.style.pointerEvents = 'none';
    this.dimensionElement.style.zIndex = '1001';
    this.dimensionElement.style.transform = 'translate(-50%, -50%)';
    this.dimensionElement.style.whiteSpace = 'nowrap';

    this.container.appendChild(this.dimensionElement);
  }

  /**
   * 清除尺寸信息
   */
  private clearDimensions(): void {
    if (this.dimensionElement && this.dimensionElement.parentNode) {
      this.dimensionElement.parentNode.removeChild(this.dimensionElement);
      this.dimensionElement = null;
    }
  }

  /**
   * 设置事件监听器
   */
  private setupEventListeners(): void {
    this.handleMouseMove = this.handleMouseMove.bind(this);
    this.handleMouseUp = this.handleMouseUp.bind(this);

    document.addEventListener('mousemove', this.handleMouseMove);
    document.addEventListener('mouseup', this.handleMouseUp);
  }

  /**
   * 移除事件监听器
   */
  private removeEventListeners(): void {
    document.removeEventListener('mousemove', this.handleMouseMove);
    document.removeEventListener('mouseup', this.handleMouseUp);
  }

  /**
   * 处理鼠标移动
   */
  private handleMouseMove(e: MouseEvent): void {
    if (this.currentOperation) {
      this.handleResizeMove({ x: e.clientX, y: e.clientY });
    }
  }

  /**
   * 处理鼠标释放
   */
  private handleMouseUp(): void {
    if (this.currentOperation) {
      this.endResize();
    }
  }
}

/**
 * 创建跨列拖拽引擎
 */
export function createCrossColumnDragEngine(
  config: CrossColumnDragConfig,
  events?: Partial<CrossColumnDragEngineEvents>,
): CrossColumnDragEngine {
  return new CrossColumnDragEngine(config, events);
}

/**
 * 跨列拖拽引擎预设配置
 */
export const CrossColumnDragEnginePresets = {
  default: {
    handleSize: 8,
    handleColor: '#007acc',
    handleHoverColor: '#005a9e',
    handleActiveColor: '#003d6b',
    gridColumns: 12,
    gridRows: 12,
    columnWidth: 80,
    rowHeight: 60,
    gap: 8,
    minColumnSpan: 1,
    minRowSpan: 1,
    maxColumnSpan: 12,
    maxRowSpan: 12,
    showPreview: true,
    snapToGrid: true,
    maintainAspectRatio: false,
    allowOverlap: false,
    showGuidelines: true,
    showDimensions: true,
    highlightAffectedCells: true,
  } as CrossColumnDragConfig,

  precise: {
    handleSize: 6,
    handleColor: '#28a745',
    handleHoverColor: '#1e7e34',
    handleActiveColor: '#155724',
    gridColumns: 24,
    gridRows: 24,
    columnWidth: 40,
    rowHeight: 30,
    gap: 4,
    minColumnSpan: 1,
    minRowSpan: 1,
    maxColumnSpan: 24,
    maxRowSpan: 24,
    showPreview: true,
    snapToGrid: true,
    maintainAspectRatio: false,
    allowOverlap: false,
    showGuidelines: true,
    showDimensions: true,
    highlightAffectedCells: true,
  } as CrossColumnDragConfig,

  flexible: {
    handleSize: 10,
    handleColor: '#ffc107',
    handleHoverColor: '#e0a800',
    handleActiveColor: '#b69500',
    gridColumns: 6,
    gridRows: 6,
    columnWidth: 120,
    rowHeight: 80,
    gap: 12,
    minColumnSpan: 1,
    minRowSpan: 1,
    showPreview: true,
    snapToGrid: false,
    maintainAspectRatio: false,
    allowOverlap: true,
    showGuidelines: false,
    showDimensions: true,
    highlightAffectedCells: false,
  } as CrossColumnDragConfig,

  performance: {
    handleSize: 8,
    handleColor: '#6c757d',
    handleHoverColor: '#5a6268',
    handleActiveColor: '#495057',
    gridColumns: 8,
    gridRows: 8,
    columnWidth: 100,
    rowHeight: 75,
    gap: 10,
    minColumnSpan: 1,
    minRowSpan: 1,
    maxColumnSpan: 8,
    maxRowSpan: 8,
    showPreview: false,
    snapToGrid: true,
    maintainAspectRatio: false,
    allowOverlap: false,
    showGuidelines: false,
    showDimensions: false,
    highlightAffectedCells: false,
  } as CrossColumnDragConfig,
};
