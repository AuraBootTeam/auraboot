/**
 * Collaboration Provider
 *
 * 提供协作功能的 React Context Provider
 */

import React, { createContext, useContext, useState, type ReactNode } from 'react';

export interface CollaborationUser {
  id: string;
  name: string;
  avatar?: string;
  color: string;
  cursor?: { x: number; y: number };
}

export interface CollaborationState {
  isConnected: boolean;
  users: CollaborationUser[];
  currentUser: CollaborationUser | null;
}

export interface CollaborationOperations {
  connect: (user: CollaborationUser) => Promise<void>;
  disconnect: () => Promise<void>;
  sendCursor: (x: number, y: number) => void;
  sendOperation: (operation: any) => void;
}

export interface CollaborationStatus {
  connectionStatus: 'connecting' | 'connected' | 'disconnected' | 'error';
  lastSync: number | null;
  conflictCount: number;
}

interface CollaborationContextType {
  state: CollaborationState;
  operations: CollaborationOperations;
  status: CollaborationStatus;
}

const CollaborationContext = createContext<CollaborationContextType | undefined>(undefined);

interface CollaborationProviderProps {
  children: ReactNode;
  websocketUrl?: string;
  enabled?: boolean;
}

export function CollaborationProvider({
  children,
  websocketUrl: _websocketUrl = 'ws://localhost:3001',
  enabled = false,
}: CollaborationProviderProps) {
  const [state, setState] = useState<CollaborationState>({
    isConnected: false,
    users: [],
    currentUser: null,
  });

  const [status, setStatus] = useState<CollaborationStatus>({
    connectionStatus: 'disconnected',
    lastSync: null,
    conflictCount: 0,
  });

  const operations: CollaborationOperations = {
    connect: async (user: CollaborationUser) => {
      if (!enabled) {
        console.warn('Collaboration is disabled');
        return;
      }

      try {
        setStatus((prev) => ({ ...prev, connectionStatus: 'connecting' }));
        await new Promise((resolve) => setTimeout(resolve, 1000));
        setState((prev) => ({
          ...prev,
          isConnected: true,
          currentUser: user,
          users: [user],
        }));
        setStatus((prev) => ({
          ...prev,
          connectionStatus: 'connected',
          lastSync: Date.now(),
        }));
      } catch (error) {
        console.error('Failed to connect to collaboration server', error);
        setStatus((prev) => ({ ...prev, connectionStatus: 'error' }));
      }
    },

    disconnect: async () => {
      try {
        setState((prev) => ({
          ...prev,
          isConnected: false,
          currentUser: null,
          users: [],
        }));

        setStatus((prev) => ({
          ...prev,
          connectionStatus: 'disconnected',
          lastSync: null,
        }));
      } catch (error) {
        console.error('Failed to disconnect from collaboration server', error);
      }
    },

    sendCursor: (x: number, y: number) => {
      if (!state.isConnected || !state.currentUser) return;

      setState((prev) => ({
        ...prev,
        users: prev.users.map((user) =>
          user.id === prev.currentUser?.id ? { ...user, cursor: { x, y } } : user,
        ),
      }));
    },

    sendOperation: (_operation: any) => {
      if (!state.isConnected) {
        console.warn('Cannot send operation: not connected');
        return;
      }

      setStatus((prev) => ({
        ...prev,
        lastSync: Date.now(),
      }));
    },
  };

  const contextValue: CollaborationContextType = {
    state,
    operations,
    status,
  };

  return (
    <CollaborationContext.Provider value={contextValue}>{children}</CollaborationContext.Provider>
  );
}

export function useCollaboration() {
  const context = useContext(CollaborationContext);
  if (!context) {
    throw new Error('useCollaboration must be used within CollaborationProvider');
  }
  return context;
}

export function useCollaborationStatus() {
  return useCollaboration().status;
}

export function useCollaborationOperations() {
  return useCollaboration().operations;
}

export default CollaborationProvider;
