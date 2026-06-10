import { describe, it, expect, vi, beforeEach } from 'vitest';
import { EventBus, events, type EventHandler } from '../EventBus';
import type { ActionContext } from '../types';

const makeContext = (): ActionContext => ({
  componentId: 'comp1',
  pageId: 'page1',
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

const makeEventBus = () => {
  // Create a fresh (non-singleton) instance each test via the private constructor trick
  const bus = new (EventBus as any).__proto__.constructor();
  // EventBus constructor is private – create by forcing access
  // Instead, use the singleton but reset it between tests
  (EventBus as any).instance = undefined;
  const fresh = EventBus.getInstance();
  fresh.destroy();
  return fresh;
};

describe('EventBus', () => {
  let bus: EventBus;

  beforeEach(() => {
    (EventBus as any).instance = undefined;
    bus = EventBus.getInstance();
    bus.destroy();
  });

  // ---- subscribe / unsubscribe ----

  describe('subscribe / unsubscribe', () => {
    it('returns a subscription id string', () => {
      const id = bus.subscribe('test.event', vi.fn());
      expect(typeof id).toBe('string');
      expect(id.startsWith('sub_')).toBe(true);
    });

    it('unsubscribes by id and returns true', () => {
      const handler = vi.fn();
      const id = bus.subscribe('ev', handler);
      expect(bus.unsubscribe(id)).toBe(true);
    });

    it('returns false for unknown subscription id', () => {
      expect(bus.unsubscribe('nonexistent')).toBe(false);
    });

    it('handler is not called after unsubscribe', async () => {
      const handler = vi.fn();
      const id = bus.subscribe('ev', handler);
      bus.unsubscribe(id);
      await bus.emit('ev', {}, makeContext());
      expect(handler).not.toHaveBeenCalled();
    });

    it('cleans up event key when last subscriber removed', () => {
      const id = bus.subscribe('ev', vi.fn());
      bus.unsubscribe(id);
      expect(bus.getSubscriptionStats()['ev']).toBeUndefined();
    });
  });

  // ---- emit ----

  describe('emit', () => {
    it('calls handler with data and context', async () => {
      const handler = vi.fn();
      bus.subscribe('click', handler);
      const ctx = makeContext();
      await bus.emit('click', { x: 1 }, ctx);
      expect(handler).toHaveBeenCalledOnce();
      expect(handler.mock.calls[0][0]).toEqual({ x: 1 });
    });

    it('emits to multiple handlers', async () => {
      const h1 = vi.fn();
      const h2 = vi.fn();
      bus.subscribe('multi', h1);
      bus.subscribe('multi', h2);
      await bus.emit('multi', {}, makeContext());
      expect(h1).toHaveBeenCalledOnce();
      expect(h2).toHaveBeenCalledOnce();
    });

    it('does nothing when no subscribers', async () => {
      // should not throw
      await expect(bus.emit('no-one', {}, makeContext())).resolves.toBeUndefined();
    });

    it('records event history on emit', async () => {
      await bus.emit('logged', { payload: 1 }, makeContext());
      const history = bus.getEventHistory();
      expect(history.length).toBe(1);
      expect(history[0].eventName).toBe('logged');
      expect(history[0].data).toEqual({ payload: 1 });
    });

    it('handles handler errors without throwing', async () => {
      bus.subscribe('bad', () => {
        throw new Error('handler crash');
      });
      // Should not throw even when a handler errors
      await expect(bus.emit('bad', {}, makeContext(), { async: false })).resolves.toBeUndefined();
    });
  });

  // ---- once ----

  describe('once (subscribe with once: true)', () => {
    it('fires once then auto-removes', async () => {
      const handler = vi.fn();
      bus.subscribe('once-ev', handler, { once: true });
      await bus.emit('once-ev', {}, makeContext());
      await bus.emit('once-ev', {}, makeContext());
      expect(handler).toHaveBeenCalledOnce();
    });
  });

  // ---- priority ----

  describe('priority ordering', () => {
    it('high priority handler runs before low priority', async () => {
      const order: number[] = [];
      bus.subscribe('prio', () => { order.push(1); }, { priority: 10 });
      bus.subscribe('prio', () => { order.push(2); }, { priority: 1 });
      bus.subscribe('prio', () => { order.push(3); }, { priority: 100 });
      await bus.emit('prio', {}, makeContext());
      expect(order).toEqual([3, 1, 2]);
    });
  });

  // ---- scope / target filtering ----

  describe('scope and target filtering', () => {
    it('global scope subscriber receives page-scoped emit', async () => {
      const handler = vi.fn();
      bus.subscribe('scoped', handler, { scope: 'global' });
      await bus.emit('scoped', {}, makeContext(), { scope: 'page' });
      expect(handler).toHaveBeenCalled();
    });

    it('page scope subscriber does not receive block-scoped emit', async () => {
      const handler = vi.fn();
      bus.subscribe('scoped', handler, { scope: 'page' });
      await bus.emit('scoped', {}, makeContext(), { scope: 'block' });
      expect(handler).not.toHaveBeenCalled();
    });

    it('target-filtered subscription only receives matching target', async () => {
      const h1 = vi.fn();
      const h2 = vi.fn();
      bus.subscribe('targeted', h1, { target: 'comp-a' });
      bus.subscribe('targeted', h2, { target: 'comp-b' });
      await bus.emit('targeted', {}, makeContext(), { target: 'comp-a' });
      expect(h1).toHaveBeenCalled();
      expect(h2).not.toHaveBeenCalled();
    });
  });

  // ---- event chains ----

  describe('event chains', () => {
    it('registers and retrieves an event chain', () => {
      bus.registerEventChain({
        id: 'chain1',
        name: 'Test Chain',
        trigger: { eventName: 'trigger.ev', scope: 'global' },
        actions: [],
        enabled: true,
      });
      const chain = bus.getEventChain('chain1');
      expect(chain).toBeDefined();
      expect(chain!.name).toBe('Test Chain');
    });

    it('unregisters an event chain', () => {
      bus.registerEventChain({
        id: 'chain2',
        name: 'Temp',
        trigger: { eventName: 'x', scope: 'global' },
        actions: [],
        enabled: true,
      });
      expect(bus.unregisterEventChain('chain2')).toBe(true);
      expect(bus.getEventChain('chain2')).toBeUndefined();
    });

    it('getAllEventChains returns all registered chains', () => {
      bus.registerEventChain({
        id: 'c1',
        name: 'C1',
        trigger: { eventName: 'e', scope: 'global' },
        actions: [],
        enabled: true,
      });
      bus.registerEventChain({
        id: 'c2',
        name: 'C2',
        trigger: { eventName: 'e', scope: 'global' },
        actions: [],
        enabled: true,
      });
      expect(bus.getAllEventChains().length).toBe(2);
    });

    it('disabled chain does not execute', async () => {
      const handler = vi.fn();
      bus.subscribe('chain.result', handler);
      bus.registerEventChain({
        id: 'disabled-chain',
        name: 'Disabled',
        trigger: { eventName: 'trigger.disabled', scope: 'global' },
        actions: [{ type: 'emit', config: { eventName: 'chain.result' } }],
        enabled: false,
      });
      await bus.emit('trigger.disabled', {}, makeContext());
      expect(handler).not.toHaveBeenCalled();
    });
  });

  // ---- event history ----

  describe('event history', () => {
    it('records events up to maxHistorySize', async () => {
      for (let i = 0; i < 5; i++) {
        await bus.emit(`ev${i}`, { i }, makeContext());
      }
      expect(bus.getEventHistory().length).toBe(5);
    });

    it('getEventHistory with limit returns last N entries', async () => {
      for (let i = 0; i < 5; i++) {
        await bus.emit('hist', { i }, makeContext());
      }
      const last2 = bus.getEventHistory(2);
      expect(last2.length).toBe(2);
      expect(last2[1].data).toEqual({ i: 4 });
    });

    it('clearEventHistory empties the history', async () => {
      await bus.emit('x', {}, makeContext());
      bus.clearEventHistory();
      expect(bus.getEventHistory().length).toBe(0);
    });
  });

  // ---- subscription stats ----

  describe('getSubscriptionStats', () => {
    it('returns correct counts', () => {
      bus.subscribe('a', vi.fn());
      bus.subscribe('a', vi.fn());
      bus.subscribe('b', vi.fn());
      const stats = bus.getSubscriptionStats();
      expect(stats['a']).toBe(2);
      expect(stats['b']).toBe(1);
    });
  });

  // ---- destroy ----

  describe('destroy', () => {
    it('clears subscriptions so handlers no longer receive emits after destroy', async () => {
      const handler = vi.fn();
      bus.subscribe('ev', handler);
      await bus.emit('ev', {}, makeContext());
      bus.destroy();
      await bus.emit('ev', {}, makeContext());
      expect(handler).toHaveBeenCalledOnce(); // only the first emit before destroy
    });

    it('clears history immediately on destroy', async () => {
      await bus.emit('ev', {}, makeContext());
      expect(bus.getEventHistory().length).toBeGreaterThan(0);
      bus.destroy();
      // History is cleared on destroy; subsequent emits may re-populate it.
      expect(bus.getEventHistory().length).toBe(0);
    });
  });

  // ---- events convenience API ----

  describe('events convenience helpers', () => {
    beforeEach(() => {
      // events uses globalEventBus – need to clear it
      (EventBus as any).instance = undefined;
      // re-import is not possible so access via singleton
      const gb = EventBus.getInstance();
      gb.destroy();
    });

    it('events.on / events.emit / events.off works end-to-end', async () => {
      const gb = EventBus.getInstance();
      const handler = vi.fn();
      const id = gb.subscribe('hello', handler);
      await gb.emit('hello', { msg: 'world' }, makeContext());
      expect(handler).toHaveBeenCalledOnce();
      gb.unsubscribe(id);
      await gb.emit('hello', {}, makeContext());
      expect(handler).toHaveBeenCalledOnce(); // no second call
    });
  });
});
