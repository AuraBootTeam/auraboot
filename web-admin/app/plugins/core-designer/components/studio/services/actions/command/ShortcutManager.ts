/**
 * 快捷键管理器
 * 统一管理设计器中的所有快捷键操作
 */

export interface ShortcutConfig {
  /** 快捷键组合 */
  key: string;
  /** 修饰键 */
  modifiers?: {
    ctrl?: boolean;
    shift?: boolean;
    alt?: boolean;
    meta?: boolean;
  };
  /** 快捷键描述 */
  description: string;
  /** 快捷键分组 */
  group?: string;
  /** 是否阻止默认行为 */
  preventDefault?: boolean;
  /** 是否阻止事件冒泡 */
  stopPropagation?: boolean;
  /** 是否启用 */
  enabled?: boolean;
}

export interface ShortcutHandler {
  (event: KeyboardEvent): void | Promise<void>;
}

export interface ShortcutRegistration extends ShortcutConfig {
  id: string;
  handler: ShortcutHandler;
}

export interface ShortcutGroup {
  name: string;
  description: string;
  shortcuts: ShortcutRegistration[];
}

/**
 * 快捷键管理器
 */
export class ShortcutManager {
  private static instance: ShortcutManager | null = null;
  private shortcuts: Map<string, ShortcutRegistration> = new Map();
  private isListening = false;
  private enabled = true;

  private constructor() {
    this.handleKeyDown = this.handleKeyDown.bind(this);
  }

  /**
   * 获取单例实例
   */
  static getInstance(): ShortcutManager {
    if (!ShortcutManager.instance) {
      ShortcutManager.instance = new ShortcutManager();
    }
    return ShortcutManager.instance;
  }

  /**
   * 注册快捷键
   */
  register(id: string, config: ShortcutConfig, handler: ShortcutHandler): void {
    const registration: ShortcutRegistration = {
      id,
      ...config,
      handler,
      enabled: config.enabled !== false,
    };

    this.shortcuts.set(id, registration);

    // 如果还没有开始监听，则开始监听
    if (!this.isListening) {
      this.startListening();
    }
  }

  /**
   * 取消注册快捷键
   */
  unregister(id: string): boolean {
    return this.shortcuts.delete(id);
  }

  /**
   * 启用/禁用快捷键
   */
  setEnabled(id: string, enabled: boolean): boolean {
    const shortcut = this.shortcuts.get(id);
    if (shortcut) {
      shortcut.enabled = enabled;
      return true;
    }
    return false;
  }

  /**
   * 启用/禁用整个快捷键系统
   */
  setGlobalEnabled(enabled: boolean): void {
    this.enabled = enabled;
  }

  /**
   * 获取快捷键信息
   */
  getShortcut(id: string): ShortcutRegistration | undefined {
    return this.shortcuts.get(id);
  }

  /**
   * 获取所有快捷键
   */
  getAllShortcuts(): ShortcutRegistration[] {
    return Array.from(this.shortcuts.values());
  }

  /**
   * 按组获取快捷键
   */
  getShortcutsByGroup(group: string): ShortcutRegistration[] {
    return Array.from(this.shortcuts.values()).filter((shortcut) => shortcut.group === group);
  }

  /**
   * 获取快捷键组
   */
  getGroups(): ShortcutGroup[] {
    const groups = new Map<string, ShortcutGroup>();

    for (const shortcut of this.shortcuts.values()) {
      const groupName = shortcut.group || 'default';

      if (!groups.has(groupName)) {
        groups.set(groupName, {
          name: groupName,
          description: this.getGroupDescription(groupName),
          shortcuts: [],
        });
      }

      groups.get(groupName)!.shortcuts.push(shortcut);
    }

    return Array.from(groups.values());
  }

  /**
   * 检查快捷键是否匹配
   */
  private matchesShortcut(event: KeyboardEvent, shortcut: ShortcutRegistration): boolean {
    // 检查主键
    if (event.key.toLowerCase() !== shortcut.key.toLowerCase()) {
      return false;
    }

    // 检查修饰键
    const modifiers = shortcut.modifiers || {};

    if (!!modifiers.ctrl !== (event.ctrlKey || event.metaKey)) {
      return false;
    }

    if (!!modifiers.shift !== event.shiftKey) {
      return false;
    }

    if (!!modifiers.alt !== event.altKey) {
      return false;
    }

    if (!!modifiers.meta !== event.metaKey) {
      return false;
    }

    return true;
  }

  /**
   * 处理键盘事件
   */
  private async handleKeyDown(event: KeyboardEvent): Promise<void> {
    if (!this.enabled) return;

    // 忽略在输入框中的按键
    const target = event.target as HTMLElement;
    if (
      target &&
      (target.tagName === 'input' ||
        target.tagName === 'textarea' ||
        target.contentEditable === 'true')
    ) {
      return;
    }

    // 查找匹配的快捷键
    for (const shortcut of this.shortcuts.values()) {
      if (!shortcut.enabled) continue;

      if (this.matchesShortcut(event, shortcut)) {
        if (shortcut.preventDefault) {
          event.preventDefault();
        }

        if (shortcut.stopPropagation) {
          event.stopPropagation();
        }

        try {
          await shortcut.handler(event);
        } catch (error) {
          console.error(`快捷键处理器执行失败 [${shortcut.id}]:`, error);
        }

        // 只处理第一个匹配的快捷键
        break;
      }
    }
  }

  /**
   * 开始监听键盘事件
   */
  private startListening(): void {
    if (this.isListening) return;

    document.addEventListener('keydown', this.handleKeyDown, true);
    this.isListening = true;
  }

  /**
   * 停止监听键盘事件
   */
  private stopListening(): void {
    if (!this.isListening) return;

    document.removeEventListener('keydown', this.handleKeyDown, true);
    this.isListening = false;
  }

  /**
   * 获取组描述
   */
  private getGroupDescription(group: string): string {
    const descriptions: Record<string, string> = {
      'undo-redo': '撤销重做操作',
      edit: '编辑操作',
      view: '视图操作',
      layout: '布局操作',
      component: '组件操作',
      file: '文件操作',
      default: '其他操作',
    };

    return descriptions[group] || group;
  }

  /**
   * 格式化快捷键显示文本
   */
  static formatShortcut(shortcut: ShortcutConfig): string {
    const parts: string[] = [];
    const modifiers = shortcut.modifiers || {};

    // 根据平台显示不同的修饰键
    const isMac = navigator.platform.includes('Mac');

    if (modifiers.ctrl) {
      parts.push(isMac ? 'Cmd' : 'Ctrl');
    }

    if (modifiers.shift) {
      parts.push('Shift');
    }

    if (modifiers.alt) {
      parts.push(isMac ? 'Option' : 'Alt');
    }

    if (modifiers.meta && !isMac) {
      parts.push('Meta');
    }

    parts.push(shortcut.key.toUpperCase());

    return parts.join('+');
  }

  /**
   * 清空所有快捷键
   */
  clear(): void {
    this.shortcuts.clear();
  }

  /**
   * 销毁管理器
   */
  destroy(): void {
    this.stopListening();
    this.shortcuts.clear();
    ShortcutManager.instance = null;
  }
}

/**
 * 预定义的快捷键配置
 */
export const PREDEFINED_SHORTCUTS = {
  // 撤销重做
  UNDO: {
    key: 'z',
    modifiers: { ctrl: true },
    description: '撤销上一步操作',
    group: 'undo-redo',
  } as ShortcutConfig,

  REDO: {
    key: 'z',
    modifiers: { ctrl: true, shift: true },
    description: '重做上一步操作',
    group: 'undo-redo',
  } as ShortcutConfig,

  REDO_ALT: {
    key: 'y',
    modifiers: { ctrl: true },
    description: '重做上一步操作（备选）',
    group: 'undo-redo',
  } as ShortcutConfig,

  // 编辑操作
  COPY: {
    key: 'c',
    modifiers: { ctrl: true },
    description: '复制选中内容',
    group: 'edit',
  } as ShortcutConfig,

  PASTE: {
    key: 'v',
    modifiers: { ctrl: true },
    description: '粘贴内容',
    group: 'edit',
  } as ShortcutConfig,

  CUT: {
    key: 'x',
    modifiers: { ctrl: true },
    description: '剪切选中内容',
    group: 'edit',
  } as ShortcutConfig,

  SELECT_ALL: {
    key: 'a',
    modifiers: { ctrl: true },
    description: '全选',
    group: 'edit',
  } as ShortcutConfig,

  DELETE: {
    key: 'Delete',
    description: '删除选中内容',
    group: 'edit',
  } as ShortcutConfig,

  // 文件操作
  SAVE: {
    key: 's',
    modifiers: { ctrl: true },
    description: '保存',
    group: 'file',
  } as ShortcutConfig,

  NEW: {
    key: 'n',
    modifiers: { ctrl: true },
    description: '新建',
    group: 'file',
  } as ShortcutConfig,

  open: {
    key: 'o',
    modifiers: { ctrl: true },
    description: '打开',
    group: 'file',
  } as ShortcutConfig,

  // 视图操作
  ZOOM_IN: {
    key: '=',
    modifiers: { ctrl: true },
    description: '放大',
    group: 'view',
  } as ShortcutConfig,

  ZOOM_OUT: {
    key: '-',
    modifiers: { ctrl: true },
    description: '缩小',
    group: 'view',
  } as ShortcutConfig,

  ZOOM_RESET: {
    key: '0',
    modifiers: { ctrl: true },
    description: '重置缩放',
    group: 'view',
  } as ShortcutConfig,

  // 布局操作
  TOGGLE_GRID: {
    key: 'g',
    modifiers: { ctrl: true },
    description: '切换网格显示',
    group: 'layout',
  } as ShortcutConfig,

  TOGGLE_RULER: {
    key: 'r',
    modifiers: { ctrl: true },
    description: '切换标尺显示',
    group: 'layout',
  } as ShortcutConfig,
};
