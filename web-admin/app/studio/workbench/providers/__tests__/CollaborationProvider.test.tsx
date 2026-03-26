import React from 'react';
import { describe, it, expect, beforeEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import {
  CollaborationProvider,
  useCollaboration,
  useCollaborationOperations,
} from '~/studio/workbench/providers/CollaborationProvider';

const wrapper: React.FC<{ children: React.ReactNode }> = ({ children }) => (
  <CollaborationProvider enabled>{children}</CollaborationProvider>
);

describe('CollaborationProvider (studio implementation)', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
  });

  it('connects and disconnects users', async () => {
    const { result } = renderHook(
      () => ({
        context: useCollaboration(),
        operations: useCollaborationOperations(),
      }),
      { wrapper },
    );

    await act(async () => {
      await result.current.operations.connect({
        id: 'u1',
        name: 'User One',
        color: '#fff',
      });
    });

    expect(result.current.context.state.isConnected).toBe(true);
    expect(result.current.context.state.users).toHaveLength(1);

    await act(async () => {
      await result.current.operations.disconnect();
    });

    expect(result.current.context.state.isConnected).toBe(false);
    expect(result.current.context.state.users).toHaveLength(0);
  });
});
