import { describe, it, expect, vi, beforeEach } from 'vitest';
import {
  NavigateActionExecutor,
  DataActionExecutor,
  FormActionExecutor,
  UIActionExecutor,
  StateActionExecutor,
  EventActionExecutor,
  ConditionActionExecutor,
} from '../ActionExecutor';
import { ActionType, type Action, type ActionContext } from '../types';

// Stub UIBridge so window.dispatchEvent calls don't blow up
vi.mock('../UIBridge', () => ({
  dispatchToast: vi.fn(),
  dispatchModal: vi.fn(),
  dispatchLoading: vi.fn(),
  dispatchVisibility: vi.fn(),
}));

// Stub ActionScheduler to avoid circular imports in ConditionActionExecutor
vi.mock('../ActionScheduler', () => ({
  globalActionScheduler: {
    executeAction: vi.fn().mockResolvedValue({ success: true, duration: 0, timestamp: 0 }),
  },
}));

const makeContext = (): ActionContext => ({
  componentId: 'c1',
  pageId: 'p1',
  pageState: {},
  globalState: {},
  env: {},
  formData: {},
  utils: {
    formatDate: () => '',
    formatNumber: () => '',
    validateEmail: () => false,
    generateId: () => 'id',
  },
});

const makeAction = (type: ActionType, extras: Record<string, any> = {}): Action => ({
  id: 'act1',
  params: { type, ...extras } as any,
});

// ─────────────────────────────────────────────────────────────────────────────
// NavigateActionExecutor
// ─────────────────────────────────────────────────────────────────────────────
describe('NavigateActionExecutor', () => {
  let executor: NavigateActionExecutor;

  beforeEach(() => {
    executor = new NavigateActionExecutor();
    // Stub window methods used by navigate
    vi.stubGlobal('window', {
      history: { back: vi.fn(), forward: vi.fn() },
      location: { href: '', reload: vi.fn(), replace: vi.fn() },
      open: vi.fn(),
    });
  });

  it('canExecute returns true for NAVIGATE', () => {
    expect(executor.canExecute(ActionType.NAVIGATE)).toBe(true);
  });

  it('canExecute returns true for BACK', () => {
    expect(executor.canExecute(ActionType.BACK)).toBe(true);
  });

  it('canExecute returns false for FETCH_DATA', () => {
    expect(executor.canExecute(ActionType.FETCH_DATA)).toBe(false);
  });

  it('getDescription returns a string', () => {
    expect(typeof executor.getDescription()).toBe('string');
  });

  it('BACK calls window.history.back', async () => {
    const action = makeAction(ActionType.BACK, { type: ActionType.BACK });
    const result = await executor.execute(action, makeContext());
    expect(result.success).toBe(true);
    expect(window.history.back).toHaveBeenCalled();
  });

  it('FORWARD calls window.history.forward', async () => {
    const action = makeAction(ActionType.FORWARD, { type: ActionType.FORWARD });
    await executor.execute(action, makeContext());
    expect(window.history.forward).toHaveBeenCalled();
  });

  it('REFRESH calls window.location.reload', async () => {
    const action = makeAction(ActionType.REFRESH, { type: ActionType.REFRESH });
    await executor.execute(action, makeContext());
    expect(window.location.reload).toHaveBeenCalled();
  });

  it('NAVIGATE with _blank calls window.open', async () => {
    const action = makeAction(ActionType.NAVIGATE, {
      type: ActionType.NAVIGATE,
      url: 'https://example.com',
      target: '_blank',
    });
    await executor.execute(action, makeContext());
    expect(window.open).toHaveBeenCalledWith('https://example.com', '_blank');
  });

  it('NAVIGATE sets window.location.href', async () => {
    const action = makeAction(ActionType.NAVIGATE, {
      type: ActionType.NAVIGATE,
      url: '/dashboard',
    });
    await executor.execute(action, makeContext());
    expect(window.location.href).toBe('/dashboard');
  });

  it('returns error result when navigate throws', async () => {
    vi.stubGlobal('window', {
      history: { back: vi.fn(), forward: vi.fn() },
      location: { href: '', reload: vi.fn(), replace: vi.fn() },
      open: () => { throw new Error('blocked'); },
    });
    const action = makeAction(ActionType.NAVIGATE, {
      type: ActionType.NAVIGATE,
      url: 'https://x.com',
      target: '_blank',
    });
    const result = await executor.execute(action, makeContext());
    expect(result.success).toBe(false);
    expect(result.error?.code).toBe('navigation_error');
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// DataActionExecutor
// ─────────────────────────────────────────────────────────────────────────────
describe('DataActionExecutor', () => {
  let executor: DataActionExecutor;

  beforeEach(() => {
    executor = new DataActionExecutor();
  });

  it('canExecute returns true for FETCH_DATA', () => {
    expect(executor.canExecute(ActionType.FETCH_DATA)).toBe(true);
  });

  it('canExecute returns false for NAVIGATE', () => {
    expect(executor.canExecute(ActionType.NAVIGATE)).toBe(false);
  });

  it('succeeds on a mocked fetch', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve({ id: 1 }),
    }));
    const action = makeAction(ActionType.FETCH_DATA, {
      type: ActionType.FETCH_DATA,
      url: '/api/data',
    });
    const result = await executor.execute(action, makeContext());
    expect(result.success).toBe(true);
    expect(result.data).toEqual({ id: 1 });
  });

  it('returns error result when fetch fails', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: false,
      status: 500,
      statusText: 'Internal Server Error',
    }));
    const action = makeAction(ActionType.FETCH_DATA, {
      type: ActionType.FETCH_DATA,
      url: '/api/fail',
    });
    const result = await executor.execute(action, makeContext());
    expect(result.success).toBe(false);
    expect(result.error?.code).toBe('data_action_error');
  });

  it('appends query params to URL', async () => {
    let capturedUrl = '';
    vi.stubGlobal('fetch', vi.fn().mockImplementation((url: string) => {
      capturedUrl = url;
      return Promise.resolve({ ok: true, json: () => Promise.resolve({}) });
    }));
    const action = makeAction(ActionType.FETCH_DATA, {
      type: ActionType.FETCH_DATA,
      url: '/api/items',
      params: { page: '1', size: '20' },
    });
    await executor.execute(action, makeContext());
    expect(capturedUrl).toContain('page=1');
    expect(capturedUrl).toContain('size=20');
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// FormActionExecutor
// ─────────────────────────────────────────────────────────────────────────────
describe('FormActionExecutor', () => {
  let executor: FormActionExecutor;

  beforeEach(() => {
    executor = new FormActionExecutor();
  });

  it('canExecute returns true for FORM_SUBMIT', () => {
    expect(executor.canExecute(ActionType.FORM_SUBMIT)).toBe(true);
  });

  it('canExecute returns false for NAVIGATE', () => {
    expect(executor.canExecute(ActionType.NAVIGATE)).toBe(false);
  });

  it('succeeds when fetch returns ok', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve({ saved: true }),
    }));
    const action = makeAction(ActionType.FORM_SUBMIT, {
      type: ActionType.FORM_SUBMIT,
      url: '/api/submit',
      method: 'post',
    });
    const result = await executor.execute(action, makeContext());
    expect(result.success).toBe(true);
    expect(result.data).toEqual({ saved: true });
  });

  it('returns error when fetch returns non-ok', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: false,
      status: 400,
      statusText: 'Bad Request',
    }));
    const action = makeAction(ActionType.FORM_SUBMIT, {
      type: ActionType.FORM_SUBMIT,
      url: '/api/submit',
    });
    const result = await executor.execute(action, makeContext());
    expect(result.success).toBe(false);
    expect(result.error?.code).toBe('form_action_error');
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// UIActionExecutor
// ─────────────────────────────────────────────────────────────────────────────
describe('UIActionExecutor', () => {
  let executor: UIActionExecutor;

  beforeEach(() => {
    executor = new UIActionExecutor();
    vi.clearAllMocks();
  });

  it('canExecute returns true for SHOW_TOAST', () => {
    expect(executor.canExecute(ActionType.SHOW_TOAST)).toBe(true);
  });

  it('canExecute returns true for TOGGLE_VISIBILITY', () => {
    expect(executor.canExecute(ActionType.TOGGLE_VISIBILITY)).toBe(true);
  });

  it('canExecute returns false for NAVIGATE', () => {
    expect(executor.canExecute(ActionType.NAVIGATE)).toBe(false);
  });

  it('SHOW_TOAST calls dispatchToast', async () => {
    const { dispatchToast } = await import('../UIBridge');
    const action = makeAction(ActionType.SHOW_TOAST, {
      type: ActionType.SHOW_TOAST,
      message: 'Hello',
      variant: 'success',
      duration: 2000,
    });
    const result = await executor.execute(action, makeContext());
    expect(result.success).toBe(true);
    expect(dispatchToast).toHaveBeenCalledWith({
      message: 'Hello',
      variant: 'success',
      duration: 2000,
    });
  });

  it('SHOW_LOADING calls dispatchLoading with visible:true', async () => {
    const { dispatchLoading } = await import('../UIBridge');
    const action = makeAction(ActionType.SHOW_LOADING, {
      type: ActionType.SHOW_LOADING,
    });
    await executor.execute(action, makeContext());
    expect(dispatchLoading).toHaveBeenCalledWith({ visible: true });
  });

  it('HIDE_LOADING calls dispatchLoading with visible:false', async () => {
    const { dispatchLoading } = await import('../UIBridge');
    const action = makeAction(ActionType.HIDE_LOADING, {
      type: ActionType.HIDE_LOADING,
    });
    await executor.execute(action, makeContext());
    expect(dispatchLoading).toHaveBeenCalledWith({ visible: false });
  });

  it('TOGGLE_VISIBILITY calls dispatchVisibility', async () => {
    const { dispatchVisibility } = await import('../UIBridge');
    const action = makeAction(ActionType.TOGGLE_VISIBILITY, {
      type: ActionType.TOGGLE_VISIBILITY,
      targetId: 'elem1',
      visible: true,
    });
    await executor.execute(action, makeContext());
    expect(dispatchVisibility).toHaveBeenCalledWith({ targetId: 'elem1', visible: true });
  });

  it('SHOW_MODAL calls dispatchModal with action:show', async () => {
    const { dispatchModal } = await import('../UIBridge');
    const action = makeAction(ActionType.SHOW_MODAL, {
      type: ActionType.SHOW_MODAL,
      modalId: 'modal1',
      title: 'Test',
    });
    await executor.execute(action, makeContext());
    expect(dispatchModal).toHaveBeenCalledWith(
      expect.objectContaining({ action: 'show', modalId: 'modal1', title: 'Test' }),
    );
  });

  it('HIDE_MODAL calls dispatchModal with action:hide', async () => {
    const { dispatchModal } = await import('../UIBridge');
    const action = makeAction(ActionType.HIDE_MODAL, {
      type: ActionType.HIDE_MODAL,
      modalId: 'modal1',
    });
    await executor.execute(action, makeContext());
    expect(dispatchModal).toHaveBeenCalledWith({ action: 'hide', modalId: 'modal1' });
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// StateActionExecutor
// ─────────────────────────────────────────────────────────────────────────────
describe('StateActionExecutor', () => {
  let executor: StateActionExecutor;

  beforeEach(() => {
    executor = new StateActionExecutor();
  });

  it('canExecute returns true for SET_STATE', () => {
    expect(executor.canExecute(ActionType.SET_STATE)).toBe(true);
  });

  it('canExecute returns false for NAVIGATE', () => {
    expect(executor.canExecute(ActionType.NAVIGATE)).toBe(false);
  });

  it('returns error result (not yet implemented)', async () => {
    const action = makeAction(ActionType.SET_STATE, {
      type: ActionType.SET_STATE,
      key: 'x',
      value: 1,
    });
    const result = await executor.execute(action, makeContext());
    // StateActionExecutor throws "Not yet implemented"
    expect(result.success).toBe(false);
    expect(result.error?.code).toBe('state_action_error');
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// EventActionExecutor
// ─────────────────────────────────────────────────────────────────────────────
describe('EventActionExecutor', () => {
  let executor: EventActionExecutor;

  beforeEach(() => {
    executor = new EventActionExecutor();
  });

  it('canExecute returns true for EMIT_EVENT', () => {
    expect(executor.canExecute(ActionType.EMIT_EVENT)).toBe(true);
  });

  it('canExecute returns false for NAVIGATE', () => {
    expect(executor.canExecute(ActionType.NAVIGATE)).toBe(false);
  });

  it('returns error result (not yet implemented)', async () => {
    const action = makeAction(ActionType.EMIT_EVENT, {
      type: ActionType.EMIT_EVENT,
      eventName: 'test',
    });
    const result = await executor.execute(action, makeContext());
    expect(result.success).toBe(false);
    expect(result.error?.code).toBe('event_action_error');
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// ConditionActionExecutor
// ─────────────────────────────────────────────────────────────────────────────
describe('ConditionActionExecutor', () => {
  let executor: ConditionActionExecutor;

  beforeEach(() => {
    executor = new ConditionActionExecutor();
  });

  it('canExecute returns true for IF_CONDITION', () => {
    expect(executor.canExecute(ActionType.IF_CONDITION)).toBe(true);
  });

  it('canExecute returns true for SWITCH_CONDITION', () => {
    expect(executor.canExecute(ActionType.SWITCH_CONDITION)).toBe(true);
  });

  it('canExecute returns false for NAVIGATE', () => {
    expect(executor.canExecute(ActionType.NAVIGATE)).toBe(false);
  });

  it('IF_CONDITION: true branch executes then actions', async () => {
    const innerAction = makeAction(ActionType.SHOW_TOAST, {
      type: ActionType.SHOW_TOAST,
      message: 'Then',
    });
    const action: Action = {
      id: 'cond1',
      params: {
        type: ActionType.IF_CONDITION,
        condition: '{{1 === 1}}',
        then: [innerAction],
      } as any,
    };
    const result = await executor.execute(action, makeContext());
    expect(result.success).toBe(true);
  });

  it('SWITCH_CONDITION: matches correct case', async () => {
    const toastAction = makeAction(ActionType.SHOW_TOAST, {
      type: ActionType.SHOW_TOAST,
      message: 'Matched',
    });
    const action: Action = {
      id: 'sw1',
      params: {
        type: ActionType.SWITCH_CONDITION,
        expression: '{{"b"}}',
        cases: [
          { value: 'a', actions: [] },
          { value: 'b', actions: [toastAction] },
        ],
        default: [],
      } as any,
    };
    const result = await executor.execute(action, makeContext());
    expect(result.success).toBe(true);
  });
});
