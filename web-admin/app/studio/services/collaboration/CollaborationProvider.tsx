/**
 * 协作编辑提供者组件
 *
 * 为设计器提供协作编辑功能的上下文
 */

import React, { createContext, useContext, useEffect, useState, useCallback } from 'react';
import type {
  CollaborationManager,
  CollaborationUser,
  CursorPosition,
  OperationConflict,
  CollaborationOperation,
} from '~/studio/services/collaboration/CollaborationManager';
import {
  createCollaborationManager,
  ConnectionStatus,
  UserPermission,
  OperationType,
} from '~/studio/services/collaboration/CollaborationManager';

/**
 * 协作上下文类型
 */
export interface CollaborationContextType {
  // 连接状态
  isConnected: boolean;
  connectionStatus: ConnectionStatus;

  // 用户管理
  currentUser: CollaborationUser | null;
  onlineUsers: CollaborationUser[];

  // 光标管理
  cursors: Map<string, CursorPosition>;
  updateCursor: (position: Omit<CursorPosition, 'userId' | 'timestamp'>) => void;

  // 操作管理
  sendOperation: (
    operation: Omit<CollaborationOperation, 'id' | 'userId' | 'timestamp'>,
  ) => Promise<void>;

  // 冲突管理
  conflicts: OperationConflict[];
  resolveConflict: (
    conflictId: string,
    resolution: 'accept_local' | 'accept_remote' | 'merge' | 'manual',
    mergedData?: any,
  ) => Promise<void>;

  // 连接管理
  connect: (pageId: string) => Promise<void>;
  disconnect: () => void;

  // 权限检查
  hasWritePermission: boolean;
  hasCommentPermission: boolean;

  // 协作管理器
  collaborationManager: CollaborationManager | null;
}

/**
 * 协作上下文
 */
const CollaborationContext = createContext<CollaborationContextType | undefined>(undefined);

/**
 * 协作提供者属性
 */
export interface CollaborationProviderProps {
  children: React.ReactNode;
  websocketUrl: string;
  userId: string;
  userName: string;
  userAvatar?: string;
  apiKey?: string;
  disabled?: boolean;
}

/**
 * 协作提供者组件
 */
export const CollaborationProvider: React.FC<CollaborationProviderProps> = ({
  children,
  websocketUrl,
  userId,
  userName,
  userAvatar,
  apiKey,
  disabled = false,
}) => {
  const [collaborationManager, setCollaborationManager] = useState<CollaborationManager | null>(
    null,
  );
  const [connectionStatus, setConnectionStatus] = useState<ConnectionStatus>(
    ConnectionStatus.Disconnected,
  );
  const [currentUser, setCurrentUser] = useState<CollaborationUser | null>(null);
  const [onlineUsers, setOnlineUsers] = useState<CollaborationUser[]>([]);
  const [cursors, setCursors] = useState<Map<string, CursorPosition>>(new Map());
  const [conflicts, setConflicts] = useState<OperationConflict[]>([]);

  // 初始化协作管理器
  useEffect(() => {
    if (disabled) {
      return;
    }

    const manager = createCollaborationManager({
      websocketUrl,
      userId,
      userName,
      userAvatar,
      apiKey,
      reconnectAttempts: 3,
      reconnectDelay: 1000,
      heartbeatInterval: 30000,
      operationTimeout: 10000,
      conflictResolutionTimeout: 30000,
    });

    setCollaborationManager(manager);
    setCurrentUser({
      id: userId,
      name: userName,
      avatar: userAvatar,
      color: generateUserColor(userId),
      isOnline: true,
      lastSeen: new Date(),
      permissions: [UserPermission.Read, UserPermission.Write, UserPermission.Comment],
    });

    return () => {
      manager.destroy();
    };
  }, [websocketUrl, userId, userName, userAvatar, apiKey, disabled]);

  // 监听连接状态变化
  useEffect(() => {
    if (!collaborationManager) return;

    const handleConnectionStatusChange = (status: ConnectionStatus) => {
      setConnectionStatus(status);
    };

    collaborationManager.on('connectionStatusChange', handleConnectionStatusChange);

    return () => {
      collaborationManager.off('connectionStatusChange', handleConnectionStatusChange);
    };
  }, [collaborationManager]);

  // 监听用户变化
  useEffect(() => {
    if (!collaborationManager) return;

    const handleUserJoin = (user: CollaborationUser) => {
      setOnlineUsers((prev) => {
        const existing = prev.find((u) => u.id === user.id);
        if (existing) {
          return prev.map((u) => (u.id === user.id ? user : u));
        }
        return [...prev, user];
      });
    };

    const handleUserLeave = (user: CollaborationUser) => {
      setOnlineUsers((prev) => prev.filter((u) => u.id !== user.id));
    };

    const handleUserUpdate = (user: CollaborationUser) => {
      setOnlineUsers((prev) => prev.map((u) => (u.id === user.id ? user : u)));
    };

    collaborationManager.on('userJoin', handleUserJoin);
    collaborationManager.on('userLeave', handleUserLeave);
    collaborationManager.on('userUpdate', handleUserUpdate);

    return () => {
      collaborationManager.off('userJoin', handleUserJoin);
      collaborationManager.off('userLeave', handleUserLeave);
      collaborationManager.off('userUpdate', handleUserUpdate);
    };
  }, [collaborationManager]);

  // 监听光标变化
  useEffect(() => {
    if (!collaborationManager) return;

    const handleCursorUpdate = (cursor: CursorPosition) => {
      setCursors((prev) => new Map(prev.set(cursor.userId, cursor)));
    };

    collaborationManager.on('cursorUpdate', handleCursorUpdate);

    return () => {
      collaborationManager.off('cursorUpdate', handleCursorUpdate);
    };
  }, [collaborationManager]);

  // 监听冲突变化
  useEffect(() => {
    if (!collaborationManager) return;

    const handleConflict = (conflict: OperationConflict) => {
      setConflicts((prev) => [...prev, conflict]);
    };

    const handleConflictResolved = (conflict: OperationConflict) => {
      setConflicts((prev) => prev.filter((c) => c.id !== conflict.id));
    };

    collaborationManager.on('conflict', handleConflict);
    collaborationManager.on('conflictResolved', handleConflictResolved);

    return () => {
      collaborationManager.off('conflict', handleConflict);
      collaborationManager.off('conflictResolved', handleConflictResolved);
    };
  }, [collaborationManager]);

  // 连接到协作会话
  const connect = useCallback(
    async (pageId: string) => {
      if (!collaborationManager || disabled) {
        return;
      }

      try {
        await collaborationManager.connect(pageId);
      } catch (error) {
        console.error('Failed to connect to collaboration session:', error);
        throw error;
      }
    },
    [collaborationManager, disabled],
  );

  // 断开连接
  const disconnect = useCallback(() => {
    if (!collaborationManager) {
      return;
    }

    collaborationManager.disconnect();
  }, [collaborationManager]);

  // 更新光标位置
  const updateCursor = useCallback(
    (position: Omit<CursorPosition, 'userId' | 'timestamp'>) => {
      if (!collaborationManager || disabled) {
        return;
      }

      collaborationManager.updateCursor(position);
    },
    [collaborationManager, disabled],
  );

  // 发送操作
  const sendOperation = useCallback(
    async (operation: Omit<CollaborationOperation, 'id' | 'userId' | 'timestamp'>) => {
      if (!collaborationManager || disabled) {
        return;
      }

      try {
        await collaborationManager.sendOperation(operation);
      } catch (error) {
        console.error('Failed to send operation:', error);
        throw error;
      }
    },
    [collaborationManager, disabled],
  );

  // 解决冲突
  const resolveConflict = useCallback(
    async (
      conflictId: string,
      resolution: 'accept_local' | 'accept_remote' | 'merge' | 'manual',
      mergedData?: any,
    ) => {
      if (!collaborationManager || disabled) {
        return;
      }

      try {
        await collaborationManager.resolveConflict(conflictId, resolution, mergedData);
      } catch (error) {
        console.error('Failed to resolve conflict:', error);
        throw error;
      }
    },
    [collaborationManager, disabled],
  );

  const contextValue: CollaborationContextType = {
    isConnected: connectionStatus === 'connected',
    connectionStatus,
    currentUser,
    onlineUsers,
    cursors,
    updateCursor,
    sendOperation,
    conflicts,
    resolveConflict,
    connect,
    disconnect,
    hasWritePermission: currentUser?.permissions.includes(UserPermission.Write) || false,
    hasCommentPermission: currentUser?.permissions.includes(UserPermission.Comment) || false,
    collaborationManager,
  };

  return (
    <CollaborationContext.Provider value={contextValue}>{children}</CollaborationContext.Provider>
  );
};

/**
 * 使用协作上下文的 Hook
 */
export const useCollaboration = (): CollaborationContextType => {
  const context = useContext(CollaborationContext);
  if (!context) {
    throw new Error('useCollaboration must be used within CollaborationProvider');
  }
  return context;
};

/**
 * 协作状态 Hook
 */
export const useCollaborationStatus = () => {
  const { isConnected, connectionStatus, onlineUsers, conflicts } = useCollaboration();

  return {
    isConnected,
    connectionStatus,
    userCount: onlineUsers.length + 1, // +1 for current user
    hasConflicts: conflicts.length > 0,
    conflictCount: conflicts.length,
  };
};

/**
 * 协作操作 Hook
 */
export const useCollaborationOperations = () => {
  const { sendOperation, hasWritePermission } = useCollaboration();

  const sendComponentOperation = useCallback(
    async (type: OperationType, componentId: string, data: any, metadata?: any) => {
      if (!hasWritePermission) {
        throw new Error('No write permission');
      }

      await sendOperation({
        type,
        path: `components.${componentId}`,
        data,
        metadata: {
          componentId,
          ...metadata,
        },
      });
    },
    [sendOperation, hasWritePermission],
  );

  const sendPropertyOperation = useCallback(
    async (componentId: string, propertyName: string, value: any, previousValue?: any) => {
      if (!hasWritePermission) {
        throw new Error('No write permission');
      }

      await sendOperation({
        type: OperationType.Update,
        path: `components.${componentId}.props.${propertyName}`,
        data: value,
        previousData: previousValue,
        metadata: {
          componentId,
          propertyName,
        },
      });
    },
    [sendOperation, hasWritePermission],
  );

  return {
    sendComponentOperation,
    sendPropertyOperation,
    hasWritePermission,
  };
};

// 辅助函数

/**
 * 生成用户颜色
 */
function generateUserColor(userId: string): string {
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
