import type { DragOverEvent, DragStartEvent, DragEndEvent } from '@dnd-kit/core';
import type { Component, Position } from '~/studio/domain/schema/types';
import type { DropZone } from '~/studio/services/layout/slotting/DropZoneManager';

type SlotSize = {
  width: number;
  height: number;
  span?: number;
};

/**
 * 插槽类型定义
 */
export interface SmartSlot {
  /** 插槽唯一标识 */
  id: string;
  /** 插槽类型 */
  type: 'grid' | 'container' | 'column' | 'row';
  /** 插槽位置 */
  position: Position;
  /** 插槽尺寸 */
  size: SlotSize;
  /** 插槽所属容器 */
  containerId?: string;
  /** 网格位置（仅grid类型） */
  gridPosition?: { row: number; col: number };
  /** 是否可接受当前拖拽项 */
  canAccept: boolean;
  /** 插槽优先级（用于重叠时的选择） */
  priority: number;
  /** 插槽状态 */
  state: 'idle' | 'highlighted' | 'active' | 'invalid';
  /** 插槽元素引用 */
  element?: HTMLElement;
}

/**
 * 拖拽上下文信息
 */
type DragPosition = { x: number; y: number };

export interface DragContextInfo {
  /** 拖拽的组件 */
  component: Component;
  /** 拖拽起始位置 */
  startPosition: DragPosition;
  /** 当前鼠标位置 */
  currentPosition: DragPosition;
  /** 拖拽偏移量 */
  offset: DragPosition;
  /** 拖拽类型 */
  dragType: 'new' | 'move' | 'copy';
}

/**
 * 智能插槽系统
 */
export class SmartSlotSystem {
  private slots: Map<string, SmartSlot> = new Map();
  private activeSlots: Set<string> = new Set();
  private highlightedSlot: string | null = null;
  private dragContext: DragContextInfo | null = null;
  private slotDetectionRadius = 20; // 插槽检测半径
  private highlightClassName = 'designer-slot-highlighted';
  private activeClassName = 'designer-slot-active';
  private invalidClassName = 'designer-slot-invalid';

  /**
   * 注册插槽
   */
  registerSlot(slot: SmartSlot): void {
    this.slots.set(slot.id, slot);
    this.updateSlotElement(slot);
  }

  /**
   * 注销插槽
   */
  unregisterSlot(slotId: string): void {
    const slot = this.slots.get(slotId);
    if (slot) {
      this.clearSlotHighlight(slot);
      this.slots.delete(slotId);
      this.activeSlots.delete(slotId);
    }
  }

  /**
   * 更新插槽信息
   */
  updateSlot(slotId: string, updates: Partial<SmartSlot>): void {
    const slot = this.slots.get(slotId);
    if (slot) {
      Object.assign(slot, updates);
      this.updateSlotElement(slot);
    }
  }

  /**
   * 开始拖拽
   */
  startDrag(event: DragStartEvent, component: Component): void {
    const startPosition = this.getEventPosition(event);

    this.dragContext = {
      component,
      startPosition,
      currentPosition: startPosition,
      offset: { x: 0, y: 0 },
      dragType: event.active.data.current?.dragType || 'move',
    };

    // 激活所有可接受的插槽
    this.activateCompatibleSlots(component);
  }

  /**
   * 拖拽过程中
   */
  updateDrag(event: DragOverEvent): void {
    if (!this.dragContext) return;

    const currentPosition = this.getEventPosition(event);
    this.dragContext.currentPosition = currentPosition;
    this.dragContext.offset = {
      x: currentPosition.x - this.dragContext.startPosition.x,
      y: currentPosition.y - this.dragContext.startPosition.y,
    };

    // 查找最佳插槽
    const bestSlot = this.findBestSlot(currentPosition);
    this.updateSlotHighlight(bestSlot);
  }

  /**
   * 结束拖拽
   */
  endDrag(event: DragEndEvent): DropZone | null {
    let dropZone: DropZone | null = null;

    if (this.highlightedSlot && this.dragContext) {
      const slot = this.slots.get(this.highlightedSlot);
      if (slot && slot.canAccept) {
        dropZone = this.createDropZone(slot);
      }
    }

    // 清理状态
    this.clearAllHighlights();
    this.deactivateAllSlots();
    this.dragContext = null;

    return dropZone;
  }

  /**
   * 激活兼容的插槽
   */
  private activateCompatibleSlots(component: Component): void {
    this.slots.forEach((slot, slotId) => {
      const canAccept = this.canSlotAcceptComponent(slot, component);
      slot.canAccept = canAccept;

      if (canAccept) {
        slot.state = 'active';
        this.activeSlots.add(slotId);
      } else {
        slot.state = 'invalid';
      }

      this.updateSlotElement(slot);
    });
  }

  /**
   * 检查插槽是否可以接受组件
   */
  private canSlotAcceptComponent(slot: SmartSlot, component: Component): boolean {
    // 基础类型检查
    if (slot.type === 'grid') {
      return true; // 网格插槽可以接受任何组件
    }

    if (slot.type === 'container') {
      return component.type !== 'container'; // 容器不能嵌套容器
    }

    if (slot.type === 'column' || slot.type === 'row') {
      return component.type !== 'layout'; // 布局组件不能放入布局插槽
    }

    // 尺寸检查
    if (component.size && slot.size) {
      return component.size.width <= slot.size.width && component.size.height <= slot.size.height;
    }

    return true;
  }

  /**
   * 查找最佳插槽
   */
  private findBestSlot(position: DragPosition): SmartSlot | null {
    let bestSlot: SmartSlot | null = null;
    let minDistance = Infinity;
    let maxPriority = -1;

    this.activeSlots.forEach((slotId) => {
      const slot = this.slots.get(slotId);
      if (!slot || !slot.canAccept) return;

      const distance = this.calculateDistance(position, slot);
      const isInRange = distance <= this.slotDetectionRadius;
      const isInBounds = this.isPositionInSlot(position, slot);

      if (isInBounds || isInRange) {
        // 优先选择边界内的插槽，然后是距离最近的，最后是优先级最高的
        if (isInBounds) {
          if (
            slot.priority > maxPriority ||
            (slot.priority === maxPriority && distance < minDistance)
          ) {
            bestSlot = slot;
            minDistance = distance;
            maxPriority = slot.priority;
          }
        } else if (
          !bestSlot ||
          (!this.isPositionInSlot(position, bestSlot) && distance < minDistance)
        ) {
          bestSlot = slot;
          minDistance = distance;
        }
      }
    });

    return bestSlot;
  }

  /**
   * 计算位置到插槽的距离
   */
  private calculateDistance(position: DragPosition, slot: SmartSlot): number {
    const posX = position.x;
    const posY = position.y;

    const slotCenter = {
      x: slot.position.column * 100 + slot.size.width / 2,
      y: slot.position.row * 100 + slot.size.height / 2,
    };

    return Math.sqrt(Math.pow(posX - slotCenter.x, 2) + Math.pow(posY - slotCenter.y, 2));
  }

  /**
   * 检查位置是否在插槽内
   */
  private isPositionInSlot(position: DragPosition, slot: SmartSlot): boolean {
    const posX = position.x;
    const posY = position.y;
    const slotX = slot.position.column * 100;
    const slotY = slot.position.row * 100;

    return (
      posX >= slotX &&
      posX <= slotX + slot.size.width &&
      posY >= slotY &&
      posY <= slotY + slot.size.height
    );
  }

  /**
   * 更新插槽高亮
   */
  private updateSlotHighlight(slot: SmartSlot | null): void {
    // 清除之前的高亮
    if (this.highlightedSlot) {
      const prevSlot = this.slots.get(this.highlightedSlot);
      if (prevSlot) {
        prevSlot.state = prevSlot.canAccept ? 'active' : 'invalid';
        this.updateSlotElement(prevSlot);
      }
    }

    // 设置新的高亮
    if (slot) {
      slot.state = 'highlighted';
      this.highlightedSlot = slot.id;
      this.updateSlotElement(slot);
    } else {
      this.highlightedSlot = null;
    }
  }

  /**
   * 更新插槽DOM元素
   */
  private updateSlotElement(slot: SmartSlot): void {
    if (!slot.element) return;

    // 清除所有状态类
    slot.element.classList.remove(
      this.highlightClassName,
      this.activeClassName,
      this.invalidClassName,
    );

    // 添加对应状态类
    switch (slot.state) {
      case 'highlighted':
        slot.element.classList.add(this.highlightClassName);
        break;
      case 'active':
        slot.element.classList.add(this.activeClassName);
        break;
      case 'invalid':
        slot.element.classList.add(this.invalidClassName);
        break;
    }

    // 设置自定义属性
    slot.element.setAttribute('data-slot-state', slot.state);
    slot.element.setAttribute('data-slot-type', slot.type);
    slot.element.setAttribute('data-can-accept', slot.canAccept.toString());
  }

  /**
   * 清除插槽高亮
   */
  private clearSlotHighlight(slot: SmartSlot): void {
    if (slot.element) {
      slot.element.classList.remove(
        this.highlightClassName,
        this.activeClassName,
        this.invalidClassName,
      );
      slot.element.removeAttribute('data-slot-state');
      slot.element.removeAttribute('data-slot-type');
      slot.element.removeAttribute('data-can-accept');
    }
    slot.state = 'idle';
  }

  /**
   * 清除所有高亮
   */
  private clearAllHighlights(): void {
    this.slots.forEach((slot) => {
      if (slot.state !== 'idle') {
        this.clearSlotHighlight(slot);
      }
    });
    this.highlightedSlot = null;
  }

  /**
   * 停用所有插槽
   */
  private deactivateAllSlots(): void {
    this.activeSlots.clear();
    this.slots.forEach((slot) => {
      slot.state = 'idle';
      slot.canAccept = false;
      this.updateSlotElement(slot);
    });
  }

  /**
   * 创建投放区域
   */
  private createDropZone(slot: SmartSlot): DropZone {
    const dropZoneType: DropZone['type'] =
      slot.type === 'container' ? 'container' : slot.type === 'grid' ? 'grid-cell' : 'slot';

    return {
      id: slot.id,
      type: dropZoneType,
      element:
        slot.element ??
        (typeof document !== 'undefined' ? document.createElement('div') : ({} as HTMLElement)),
      bounds: slot.element?.getBoundingClientRect() ?? new DOMRect(),
      position: slot.position,
      size: slot.size,
      containerId: slot.containerId,
      gridPosition: slot.gridPosition,
      canAccept: slot.canAccept,
      priority: slot.priority,
      metadata: {
        slotType: slot.type,
        priority: slot.priority,
      },
    };
  }

  /**
   * 获取事件位置
   */
  private getEventPosition(event: any): DragPosition {
    // 从事件中提取位置信息
    const clientX = event.delta?.x || event.active?.rect?.current?.translated?.left || 0;
    const clientY = event.delta?.y || event.active?.rect?.current?.translated?.top || 0;

    return { x: clientX, y: clientY };
  }

  /**
   * 获取当前高亮的插槽
   */
  getHighlightedSlot(): SmartSlot | null {
    return this.highlightedSlot ? this.slots.get(this.highlightedSlot) || null : null;
  }

  /**
   * 获取所有活跃的插槽
   */
  getActiveSlots(): SmartSlot[] {
    return Array.from(this.activeSlots)
      .map((id) => this.slots.get(id))
      .filter(Boolean) as SmartSlot[];
  }

  /**
   * 获取插槽统计信息
   */
  getStats() {
    return {
      totalSlots: this.slots.size,
      activeSlots: this.activeSlots.size,
      highlightedSlot: this.highlightedSlot,
      dragActive: !!this.dragContext,
    };
  }

  /**
   * 清理系统
   */
  destroy(): void {
    this.clearAllHighlights();
    this.deactivateAllSlots();
    this.slots.clear();
    this.activeSlots.clear();
    this.dragContext = null;
    this.highlightedSlot = null;
  }
}
