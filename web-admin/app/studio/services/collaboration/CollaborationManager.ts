/**
 * 协作编辑管理器
 *
 * 实现多用户实时编辑、操作冲突检测和解决、用户光标显示等功能
 */

import { EventEmitter } from 'events';
import type { PageState, StateChangeEvent } from '~/studio/services/state/PageStateManager';

/**
 * 用户信息
 */
export interface CollaborationUser {
  id: string;
  name: string;
  avatar?: string;
  color: string;
  isOnline: boolean;
  lastSeen: Date;
  permissions: UserPermission[];
}

/**
 * 用户权限
 */
export enum UserPermission {
  Read = 'read',
  Write = 'write',
  Comment = 'comment',
  Admin = 'admin',
}

/**
 * 光标位置
 */
export interface CursorPosition {
  userId: string;
  componentId?: string;
  propertyPath?: string;
  selection?: {
    start: number;
    end: number;
  };
  timestamp: Date;
}

/**
 * 操作类型
 */
export enum OperationType {
  Create = 'create',
  Update = 'update',
  Delete = 'delete',
  Move = 'move',
  Copy = 'copy',
  Paste = 'paste',
}

/**
 * 协作操作
 */
export interface CollaborationOperation {
  id: string;
  type: OperationType;
  userId: string;
  timestamp: Date;
  path: string;
  data: any;
  previousData?: any;
  metadata?: {
    componentId?: string;
    propertyName?: string;
    description?: string;
  };
}

/**
 * 操作冲突
 */
export interface OperationConflict {
  id: string;
  operations: CollaborationOperation[];
  conflictType: 'concurrent' | 'dependency' | 'permission';
  description: string;
  timestamp: Date;
  resolved: boolean;
  resolution?: 'accept_local' | 'accept_remote' | 'merge' | 'manual';
}

/**
 * 协作会话
 */
export interface CollaborationSession {
  id: string;
  pageId: string;
  users: Map<string, CollaborationUser>;
  operations: CollaborationOperation[];
  conflicts: OperationConflict[];
  createdAt: Date;
  lastActivity: Date;
}

/**
 * 实时消息类型
 */
export enum MessageType {
  UserJoin = 'user_join',
  UserLeave = 'user_leave',
  UserUpdate = 'user_update',
  CursorMove = 'cursor_move',
  Operation = 'operation',
  OperationAck = 'operation_ack',
  Conflict = 'conflict',
  ConflictResolved = 'conflict_resolved',
  StateSync = 'state_sync',
  Heartbeat = 'heartbeat',
}

/**
 * 实时消息
 */
export interface RealtimeMessage {
  type: MessageType;
  sessionId: string;
  userId: string;
  timestamp: Date;
  data: any;
}

/**
 * WebSocket 连接状态
 */
export enum ConnectionStatus {
  Disconnected = 'disconnected',
  Connecting = 'connecting',
  Connected = 'connected',
  Reconnecting = 'reconnecting',
  Error = 'error',
}

/**
 * 协作配置
 */
export interface CollaborationConfig {
  websocketUrl: string;
  apiKey?: string;
  userId: string;
  userName: string;
  userAvatar?: string;
  reconnectAttempts: number;
  reconnectDelay: number;
  heartbeatInterval: number;
  operationTimeout: number;
  conflictResolutionTimeout: number;
}

/**
 * 协作编辑管理器
 */
export class CollaborationManager extends EventEmitter {
  private config: CollaborationConfig;
  private websocket?: WebSocket;
  private connectionStatus: ConnectionStatus = ConnectionStatus.Disconnected;
  private session?: CollaborationSession;
  private currentUser: CollaborationUser;
  private cursors: Map<string, CursorPosition> = new Map();
  private pendingOperations: Map<string, CollaborationOperation> = new Map();
  private operationQueue: CollaborationOperation[] = [];
  private heartbeatTimer?: NodeJS.Timeout;
  private reconnectTimer?: NodeJS.Timeout;
  private reconnectAttempts = 0;

  constructor(config: CollaborationConfig) {
    super();

    this.config = config;
    this.currentUser = {
      id: config.userId,
      name: config.userName,
      avatar: config.userAvatar,
      color: this.generateUserColor(config.userId),
      isOnline: true,
      lastSeen: new Date(),
      permissions: [UserPermission.Read, UserPermission.Write],
    };
  }

  /**
   * 连接到协作会话
   */
  async connect(pageId: string): Promise<void> {
    if (this.connectionStatus === ConnectionStatus.Connected) {
      return;
    }

    this.connectionStatus = ConnectionStatus.Connecting;
    this.emit('connectionStatusChange', this.connectionStatus);

    try {
      const wsUrl = `${this.config.websocketUrl}?pageId=${pageId}&userId=${this.config.userId}`;
      this.websocket = new WebSocket(wsUrl);

      this.websocket.onopen = this.handleWebSocketOpen;
      this.websocket.onmessage = this.handleWebSocketMessage;
      this.websocket.onclose = this.handleWebSocketClose;
      this.websocket.onerror = this.handleWebSocketError;

      // 等待连接建立
      await new Promise<void>((resolve, reject) => {
        const timeout = setTimeout(() => {
          reject(new Error('Connection timeout'));
        }, 10000);

        this.once('connected', () => {
          clearTimeout(timeout);
          resolve();
        });

        this.once('error', (error) => {
          clearTimeout(timeout);
          reject(error);
        });
      });
    } catch (error) {
      this.connectionStatus = ConnectionStatus.Error;
      this.emit('connectionStatusChange', this.connectionStatus);
      throw error;
    }
  }

  /**
   * 断开连接
   */
  disconnect(): void {
    if (this.websocket) {
      this.websocket.close();
      this.websocket = undefined;
    }

    if (this.heartbeatTimer) {
      clearInterval(this.heartbeatTimer);
      this.heartbeatTimer = undefined;
    }

    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = undefined;
    }

    this.connectionStatus = ConnectionStatus.Disconnected;
    this.emit('connectionStatusChange', this.connectionStatus);
  }

  /**
   * 发送操作
   */
  async sendOperation(
    operation: Omit<CollaborationOperation, 'id' | 'userId' | 'timestamp'>,
  ): Promise<void> {
    const fullOperation: CollaborationOperation = {
      ...operation,
      id: this.generateOperationId(),
      userId: this.currentUser.id,
      timestamp: new Date(),
    };

    // 检查权限
    if (!this.hasPermission(UserPermission.Write)) {
      throw new Error('No write permission');
    }

    // 添加到待确认队列
    this.pendingOperations.set(fullOperation.id, fullOperation);

    // 发送到服务器
    this.sendMessage({
      type: MessageType.Operation,
      sessionId: this.session?.id || '',
      userId: this.currentUser.id,
      timestamp: new Date(),
      data: fullOperation,
    });

    // 设置超时
    setTimeout(() => {
      if (this.pendingOperations.has(fullOperation.id)) {
        this.pendingOperations.delete(fullOperation.id);
        this.emit('operationTimeout', fullOperation);
      }
    }, this.config.operationTimeout);
  }

  /**
   * 更新光标位置
   */
  updateCursor(position: Omit<CursorPosition, 'userId' | 'timestamp'>): void {
    const cursorPosition: CursorPosition = {
      ...position,
      userId: this.currentUser.id,
      timestamp: new Date(),
    };

    this.cursors.set(this.currentUser.id, cursorPosition);

    this.sendMessage({
      type: MessageType.CursorMove,
      sessionId: this.session?.id || '',
      userId: this.currentUser.id,
      timestamp: new Date(),
      data: cursorPosition,
    });

    this.emit('cursorUpdate', cursorPosition);
  }

  /**
   * 获取所有用户光标
   */
  getCursors(): Map<string, CursorPosition> {
    return new Map(this.cursors);
  }

  /**
   * 获取在线用户
   */
  getOnlineUsers(): CollaborationUser[] {
    if (!this.session) {
      return [this.currentUser];
    }

    return Array.from(this.session.users.values()).filter((user) => user.isOnline);
  }

  /**
   * 获取会话信息
   */
  getSession(): CollaborationSession | undefined {
    return this.session;
  }

  /**
   * 获取连接状态
   */
  getConnectionStatus(): ConnectionStatus {
    return this.connectionStatus;
  }

  /**
   * 解决冲突
   */
  async resolveConflict(
    conflictId: string,
    resolution: 'accept_local' | 'accept_remote' | 'merge' | 'manual',
    mergedData?: any,
  ): Promise<void> {
    const conflict = this.session?.conflicts.find((c) => c.id === conflictId);
    if (!conflict) {
      throw new Error('Conflict not found');
    }

    conflict.resolved = true;
    conflict.resolution = resolution;

    this.sendMessage({
      type: MessageType.ConflictResolved,
      sessionId: this.session?.id || '',
      userId: this.currentUser.id,
      timestamp: new Date(),
      data: {
        conflictId,
        resolution,
        mergedData,
      },
    });

    this.emit('conflictResolved', conflict);
  }

  /**
   * 检查用户权限
   */
  hasPermission(permission: UserPermission): boolean {
    return this.currentUser.permissions.includes(permission);
  }

  /**
   * 获取操作历史
   */
  getOperationHistory(limit?: number): CollaborationOperation[] {
    if (!this.session) {
      return [];
    }

    const operations = [...this.session.operations];
    return limit ? operations.slice(-limit) : operations;
  }

  /**
   * 获取冲突列表
   */
  getConflicts(): OperationConflict[] {
    return this.session?.conflicts || [];
  }

  /**
   * 销毁管理器
   */
  destroy(): void {
    this.disconnect();
    this.removeAllListeners();
    this.cursors.clear();
    this.pendingOperations.clear();
    this.operationQueue = [];
  }

  // 私有方法

  private handleWebSocketOpen = (): void => {
    this.connectionStatus = ConnectionStatus.Connected;
    this.reconnectAttempts = 0;
    this.emit('connectionStatusChange', this.connectionStatus);
    this.emit('connected');

    // 开始心跳
    this.startHeartbeat();
  };

  private handleWebSocketMessage = (event: MessageEvent): void => {
    try {
      const message: RealtimeMessage = JSON.parse(event.data);
      this.handleRealtimeMessage(message);
    } catch (error) {
      console.error('Failed to parse WebSocket message:', error);
    }
  };

  private handleWebSocketClose = (event: CloseEvent): void => {
    this.connectionStatus = ConnectionStatus.Disconnected;
    this.emit('connectionStatusChange', this.connectionStatus);

    if (this.heartbeatTimer) {
      clearInterval(this.heartbeatTimer);
      this.heartbeatTimer = undefined;
    }

    // 尝试重连
    if (event.code !== 1000 && this.reconnectAttempts < this.config.reconnectAttempts) {
      this.attemptReconnect();
    }
  };

  private handleWebSocketError = (event: Event): void => {
    this.connectionStatus = ConnectionStatus.Error;
    this.emit('connectionStatusChange', this.connectionStatus);
    this.emit('error', new Error('WebSocket error'));
  };

  private handleRealtimeMessage(message: RealtimeMessage): void {
    switch (message.type) {
      case MessageType.UserJoin:
        this.handleUserJoin(message.data);
        break;

      case MessageType.UserLeave:
        this.handleUserLeave(message.data);
        break;

      case MessageType.UserUpdate:
        this.handleUserUpdate(message.data);
        break;

      case MessageType.CursorMove:
        this.handleCursorMove(message.data);
        break;

      case MessageType.Operation:
        this.handleOperation(message.data);
        break;

      case MessageType.OperationAck:
        this.handleOperationAck(message.data);
        break;

      case MessageType.Conflict:
        this.handleConflict(message.data);
        break;

      case MessageType.ConflictResolved:
        this.handleConflictResolved(message.data);
        break;

      case MessageType.StateSync:
        this.handleStateSync(message.data);
        break;

      case MessageType.Heartbeat:
        // 心跳响应，无需处理
        break;

      default:
        console.warn('Unknown message type:', message.type);
    }
  }

  private handleUserJoin(userData: CollaborationUser): void {
    if (!this.session) {
      this.session = {
        id: `session_${Date.now()}`,
        pageId: '',
        users: new Map(),
        operations: [],
        conflicts: [],
        createdAt: new Date(),
        lastActivity: new Date(),
      };
    }

    this.session.users.set(userData.id, userData);
    this.emit('userJoin', userData);
  }

  private handleUserLeave(userData: { userId: string }): void {
    if (this.session) {
      const user = this.session.users.get(userData.userId);
      if (user) {
        user.isOnline = false;
        user.lastSeen = new Date();
        this.emit('userLeave', user);
      }
    }

    // 移除用户光标
    this.cursors.delete(userData.userId);
  }

  private handleUserUpdate(userData: CollaborationUser): void {
    if (this.session) {
      this.session.users.set(userData.id, userData);
      this.emit('userUpdate', userData);
    }
  }

  private handleCursorMove(cursorData: CursorPosition): void {
    if (cursorData.userId !== this.currentUser.id) {
      this.cursors.set(cursorData.userId, cursorData);
      this.emit('cursorUpdate', cursorData);
    }
  }

  private handleOperation(operation: CollaborationOperation): void {
    if (operation.userId === this.currentUser.id) {
      return; // 忽略自己的操作
    }

    // 检查冲突
    const conflict = this.detectConflict(operation);
    if (conflict) {
      this.handleConflict(conflict);
      return;
    }

    // 应用操作
    this.applyOperation(operation);

    if (this.session) {
      this.session.operations.push(operation);
      this.session.lastActivity = new Date();
    }

    this.emit('operationReceived', operation);
  }

  private handleOperationAck(ackData: { operationId: string }): void {
    const operation = this.pendingOperations.get(ackData.operationId);
    if (operation) {
      this.pendingOperations.delete(ackData.operationId);
      this.emit('operationAck', operation);
    }
  }

  private handleConflict(conflict: OperationConflict): void {
    if (this.session) {
      this.session.conflicts.push(conflict);
    }

    this.emit('conflict', conflict);

    // 自动解决简单冲突
    if (this.canAutoResolveConflict(conflict)) {
      this.autoResolveConflict(conflict);
    }
  }

  private handleConflictResolved(resolutionData: {
    conflictId: string;
    resolution: string;
    mergedData?: any;
  }): void {
    const conflict = this.session?.conflicts.find((c) => c.id === resolutionData.conflictId);
    if (conflict) {
      conflict.resolved = true;
      conflict.resolution = resolutionData.resolution as any;
      this.emit('conflictResolved', conflict);
    }
  }

  private handleStateSync(syncData: { state: PageState }): void {
    this.emit('stateSync', syncData.state);
  }

  private detectConflict(operation: CollaborationOperation): OperationConflict | null {
    // 检查是否有并发操作冲突
    const recentOperations = this.getRecentOperations(5000); // 5秒内的操作
    const conflictingOps = recentOperations.filter(
      (op) =>
        op.path === operation.path &&
        op.userId !== operation.userId &&
        Math.abs(op.timestamp.getTime() - operation.timestamp.getTime()) < 1000, // 1秒内
    );

    if (conflictingOps.length > 0) {
      return {
        id: this.generateConflictId(),
        operations: [operation, ...conflictingOps],
        conflictType: 'concurrent',
        description: `Concurrent operations on ${operation.path}`,
        timestamp: new Date(),
        resolved: false,
      };
    }

    return null;
  }

  private applyOperation(operation: CollaborationOperation): void {
    // 触发操作应用事件，由外部处理具体的状态更新
    this.emit('applyOperation', operation);
  }

  private canAutoResolveConflict(conflict: OperationConflict): boolean {
    // 简单的自动解决策略：只有两个操作且类型不同
    return (
      conflict.operations.length === 2 &&
      conflict.operations[0].type !== conflict.operations[1].type
    );
  }

  private autoResolveConflict(conflict: OperationConflict): void {
    // 简单的解决策略：优先级 create > update > delete
    const priorityOrder = [OperationType.Create, OperationType.Update, OperationType.Delete];

    const sortedOps = conflict.operations.sort((a, b) => {
      const aPriority = priorityOrder.indexOf(a.type);
      const bPriority = priorityOrder.indexOf(b.type);
      return aPriority - bPriority;
    });

    this.resolveConflict(conflict.id, 'accept_remote', sortedOps[0].data);
  }

  private getRecentOperations(timeWindow: number): CollaborationOperation[] {
    if (!this.session) {
      return [];
    }

    const cutoff = Date.now() - timeWindow;
    return this.session.operations.filter((op) => op.timestamp.getTime() > cutoff);
  }

  private sendMessage(message: RealtimeMessage): void {
    if (this.websocket && this.websocket.readyState === WebSocket.OPEN) {
      this.websocket.send(JSON.stringify(message));
    } else {
      // 连接断开时，将消息加入队列
      this.operationQueue.push(message.data);
    }
  }

  private startHeartbeat(): void {
    this.heartbeatTimer = setInterval(() => {
      this.sendMessage({
        type: MessageType.Heartbeat,
        sessionId: this.session?.id || '',
        userId: this.currentUser.id,
        timestamp: new Date(),
        data: {},
      });
    }, this.config.heartbeatInterval);
  }

  private attemptReconnect(): void {
    this.connectionStatus = ConnectionStatus.Reconnecting;
    this.emit('connectionStatusChange', this.connectionStatus);

    this.reconnectTimer = setTimeout(() => {
      this.reconnectAttempts++;
      this.connect(this.session?.pageId || '').catch(() => {
        if (this.reconnectAttempts < this.config.reconnectAttempts) {
          this.attemptReconnect();
        } else {
          this.connectionStatus = ConnectionStatus.Error;
          this.emit('connectionStatusChange', this.connectionStatus);
        }
      });
    }, this.config.reconnectDelay * this.reconnectAttempts);
  }

  private generateOperationId(): string {
    return `op_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`;
  }

  private generateConflictId(): string {
    return `conflict_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`;
  }

  private generateUserColor(userId: string): string {
    const colors = [
      '#FF6B6B',
      '#4ECDC4',
      '#45B7D1',
      '#96CEB4',
      '#FFEAA7',
      '#DDA0DD',
      '#98D8C8',
      '#F7DC6F',
      '#BB8FCE',
      '#85C1E9',
    ];

    let hash = 0;
    for (let i = 0; i < userId.length; i++) {
      hash = userId.charCodeAt(i) + ((hash << 5) - hash);
    }

    return colors[Math.abs(hash) % colors.length];
  }
}

// 全局协作管理器实例
let globalCollaborationManager: CollaborationManager | null = null;

/**
 * 获取全局协作管理器
 */
export function getCollaborationManager(): CollaborationManager | null {
  return globalCollaborationManager;
}

/**
 * 设置全局协作管理器
 */
export function setCollaborationManager(manager: CollaborationManager): void {
  globalCollaborationManager = manager;
}

/**
 * 创建协作管理器
 */
export function createCollaborationManager(config: CollaborationConfig): CollaborationManager {
  return new CollaborationManager(config);
}
