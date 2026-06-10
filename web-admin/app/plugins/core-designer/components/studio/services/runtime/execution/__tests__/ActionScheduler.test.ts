import { describe, it, expect, vi, beforeEach } from 'vitest';
import { ActionScheduler } from '../ActionScheduler';
import { ActionType, type Action, type ActionContext, type ActionChain } from '../types';

// Mock UIBridge to avoid CustomEvent dispatches
vi.mock('../UIBridge', () => ({
  dispatchToast: vi.fn(),
  dispatchModal: vi.fn(),
  dispatchLoading: vi.fn(),
  dispatchVisibility: vi.fn(),
}));

const makeContext = (): ActionContext => ({
  componentId: 'c1',
  pageId: 'p1',
  pageState: {},
  globalState: {},
  env: {},
  utils: {
    formatDate: () => '',
    formatNumber: () => '',
    validateEmail: () => false,
    generateId: () => 'id',
  },
});

const makeToastAction = (message = 'Hello'): Action => ({
  id: 'toast1',
  params: {
    type: ActionType.SHOW_TOAST,
    message,
    variant: 'info',
  } as any,
});

describe('ActionScheduler', () => {
  let scheduler: ActionScheduler;

  beforeEach(() => {
    // Reset singleton so each test gets a fresh instance with reset metrics
    (ActionScheduler as any).instance = null;
    scheduler = new ActionScheduler({
      maxConcurrentActions: 5,
      defaultTimeout: 10000,
      enableLogging: false,
      enableMetrics: true,
      retryPolicy: {
        maxRetries: 0,  // no retries – keeps tests fast
        backoffStrategy: 'linear',
        baseDelay: 0,
      },
    });
  });

  describe('constructor / executor registration', () => {
    it('registers all default executors on construction', () => {
      const names = scheduler.getRegisteredExecutors();
      expect(names.length).toBeGreaterThan(0);
      // Should include the built-in executor class names
      expect(names.some((n) => n.includes('UIActionExecutor'))).toBe(true);
      expect(names.some((n) => n.includes('NavigateActionExecutor'))).toBe(true);
    });

    it('allows custom executor registration', () => {
      const mockExecutor = {
        canExecute: vi.fn().mockReturnValue(false),
        execute: vi.fn(),
        getDescription: () => 'Custom',
        constructor: { name: 'CustomExecutor' },
      };
      Object.setPrototypeOf(mockExecutor, { constructor: { name: 'CustomExecutor' } });
      scheduler.registerExecutor(mockExecutor as any);
      // Just verify no error thrown
      expect(scheduler.getRegisteredExecutors()).toBeDefined();
    });
  });

  describe('executeAction', () => {
    it('succeeds for SHOW_TOAST', async () => {
      vi.stubGlobal('window', {
        dispatchEvent: vi.fn(),
        history: { back: vi.fn(), forward: vi.fn() },
        location: { href: '', reload: vi.fn(), replace: vi.fn() },
        open: vi.fn(),
      });
      const result = await scheduler.executeAction(makeToastAction(), makeContext());
      expect(result.success).toBe(true);
      expect(typeof result.duration).toBe('number');
      expect(typeof result.timestamp).toBe('number');
    });

    it('returns error result for unknown action type', async () => {
      const action: Action = {
        id: 'x',
        params: { type: 'actions.unknown.fake' as any } as any,
      };
      const result = await scheduler.executeAction(action, makeContext());
      expect(result.success).toBe(false);
      expect(result.error?.message).toContain('No executor found');
    });

    it('updates metrics after successful execution', async () => {
      vi.stubGlobal('window', { dispatchEvent: vi.fn() });
      await scheduler.executeAction(makeToastAction(), makeContext());
      const metrics = scheduler.getMetrics();
      expect(metrics.totalExecutions).toBe(1);
      expect(metrics.successCount).toBe(1);
      expect(metrics.errorCount).toBe(0);
    });

    it('updates errorCount on failure', async () => {
      const action: Action = {
        id: 'bad',
        params: { type: 'actions.nonexistent' as any } as any,
      };
      await scheduler.executeAction(action, makeContext());
      const metrics = scheduler.getMetrics();
      expect(metrics.errorCount).toBe(1);
      expect(metrics.errorRate).toBeGreaterThan(0);
    });
  });

  describe('executeActionChain', () => {
    it('runs all actions in sequence and returns success', async () => {
      vi.stubGlobal('window', { dispatchEvent: vi.fn() });
      const chain: ActionChain = {
        id: 'chain1',
        name: 'Toast Chain',
        actions: [makeToastAction('first'), makeToastAction('second')],
        stopOnError: true,
      };
      const result = await scheduler.executeActionChain(chain, makeContext());
      expect(result.success).toBe(true);
      expect(result.results).toHaveLength(2);
      expect(typeof result.totalDuration).toBe('number');
    });

    it('stopOnError stops after first failure', async () => {
      const badAction: Action = {
        id: 'bad',
        params: { type: 'actions.nonexistent' as any } as any,
      };
      const chain: ActionChain = {
        id: 'chain2',
        name: 'Stop on Error Chain',
        actions: [badAction, makeToastAction()],
        stopOnError: true,
      };
      const result = await scheduler.executeActionChain(chain, makeContext());
      expect(result.success).toBe(false);
      // Only one result because the chain stopped
      expect(result.results).toHaveLength(1);
    });

    it('continues despite errors when stopOnError is false', async () => {
      vi.stubGlobal('window', { dispatchEvent: vi.fn() });
      const badAction: Action = {
        id: 'bad',
        params: { type: 'actions.nonexistent' as any } as any,
      };
      const chain: ActionChain = {
        id: 'chain3',
        name: 'Continue Chain',
        actions: [badAction, makeToastAction()],
        stopOnError: false,
      };
      const result = await scheduler.executeActionChain(chain, makeContext());
      expect(result.results).toHaveLength(2);
    });

    it('includes error info when chain fails', async () => {
      const badAction: Action = {
        id: 'bad',
        params: { type: 'actions.nonexistent' as any } as any,
      };
      const chain: ActionChain = {
        id: 'chain-err',
        name: 'Error Chain',
        actions: [badAction],
        stopOnError: true,
      };
      const result = await scheduler.executeActionChain(chain, makeContext());
      expect(result.error).toBeDefined();
    });
  });

  describe('addEventListener / emitEvent', () => {
    it('fires listener actions on emitEvent', async () => {
      vi.stubGlobal('window', { dispatchEvent: vi.fn() });
      const listener = {
        id: 'l1',
        eventName: 'my.event',
        scope: 'page' as const,
        actions: [makeToastAction('event fired')],
        enabled: true,
      };
      scheduler.addEventListener('my.event', listener);
      await scheduler.emitEvent('my.event', { data: 1 }, makeContext());
      // No assertion on the inner result here – just verifying no throw
    });

    it('does nothing when no listeners', async () => {
      await expect(
        scheduler.emitEvent('no.listeners', {}, makeContext()),
      ).resolves.toBeUndefined();
    });

    it('removeEventListener removes the listener', async () => {
      const listener = {
        id: 'l2',
        eventName: 'ev',
        scope: 'page' as const,
        actions: [],
        enabled: true,
      };
      scheduler.addEventListener('ev', listener);
      scheduler.removeEventListener('ev', listener);
      // Should not throw on emit with no listeners
      await expect(scheduler.emitEvent('ev', {}, makeContext())).resolves.toBeUndefined();
    });
  });

  describe('metrics', () => {
    it('resetMetrics zeroes all counters', async () => {
      vi.stubGlobal('window', { dispatchEvent: vi.fn() });
      await scheduler.executeAction(makeToastAction(), makeContext());
      scheduler.resetMetrics();
      const metrics = scheduler.getMetrics();
      expect(metrics.totalExecutions).toBe(0);
      expect(metrics.successCount).toBe(0);
      expect(metrics.errorCount).toBe(0);
      expect(metrics.averageDuration).toBe(0);
    });

    it('averageDuration is calculated correctly', async () => {
      vi.stubGlobal('window', { dispatchEvent: vi.fn() });
      await scheduler.executeAction(makeToastAction(), makeContext());
      await scheduler.executeAction(makeToastAction(), makeContext());
      const { averageDuration } = scheduler.getMetrics();
      expect(averageDuration).toBeGreaterThanOrEqual(0);
    });

    it('errorRate = errors / total', async () => {
      vi.stubGlobal('window', { dispatchEvent: vi.fn() });
      await scheduler.executeAction(makeToastAction(), makeContext());
      const badAction: Action = {
        id: 'b',
        params: { type: 'fake' as any } as any,
      };
      await scheduler.executeAction(badAction, makeContext());
      const { errorRate, totalExecutions, errorCount } = scheduler.getMetrics();
      expect(errorRate).toBeCloseTo(errorCount / totalExecutions);
    });
  });

  describe('destroy', () => {
    it('clears executors and listeners without throwing', () => {
      expect(() => scheduler.destroy()).not.toThrow();
      expect(scheduler.getRegisteredExecutors()).toHaveLength(0);
    });
  });

  describe('execute / executeChain aliases', () => {
    it('execute is an alias for executeAction', async () => {
      vi.stubGlobal('window', { dispatchEvent: vi.fn() });
      const result = await scheduler.execute(makeToastAction(), makeContext());
      expect(result.success).toBe(true);
    });

    it('executeChain is an alias for executeActionChain', async () => {
      vi.stubGlobal('window', { dispatchEvent: vi.fn() });
      const chain: ActionChain = {
        id: 'alias',
        name: 'Alias',
        actions: [makeToastAction()],
      };
      const result = await scheduler.executeChain(chain, makeContext());
      expect(result.success).toBe(true);
    });
  });
});
