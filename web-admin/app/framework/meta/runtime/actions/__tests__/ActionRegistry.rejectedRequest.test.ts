import { afterEach, describe, expect, it, vi } from 'vitest';
import { actionRegistry } from '../ActionRegistry';
import { createRequestKeySession } from '../request-key/session';
import { ExpressionEvaluator } from '../../expression/evaluator';
import { ScopedStateManager } from '../../state/scoped-state';
import { FlowRunner } from '../../schema-runtime/FlowRunner';

const field = 'bookingRequest';
const rejection = {
  httpStatus: 400,
  code: '35000',
  context: { messageKey: '$i18n:booking.quote_expired' },
};
const policy = { field, responseCode: '35000', messageKeys: ['$i18n:booking.quote_expired'] };
function setup(result: unknown = rejection, selectedPolicy: unknown = policy) {
  let identity = { tenantId: '1', userId: 'supplier', pageKey: 'order_detail', recordPid: 'order' };
  const session = createRequestKeySession(() => identity);
  const key = session.ensure(field, null);
  const manager = new ScopedStateManager({ locale: 'en-US', theme: 'light' } as any);
  manager.createScope('booking', {});
  manager.updateState('booking', field, key);
  const fetchResult = vi.fn().mockResolvedValue(result),
    showToast = vi.fn();
  const runner = new FlowRunner({
    evaluator: new ExpressionEvaluator(),
    actionRegistry,
    stateManager: manager,
    scopeId: 'booking',
    requestKeySession: session,
    dataSourceManager: {} as any,
    schema: {} as any,
    getAllFormFields: () => [],
    showToast,
  });
  const steps = [
    {
      action: 'api.request',
      args: {
        endpoint: '/api/booking',
        method: 'post',
        target: 'bookingResult',
        releaseRequestKeyOnRejection: selectedPolicy,
        body: { clientRequestId: key, payload: { requestKey: key } },
      },
    },
    { action: 'toast.success', args: { message: 'Booked' } },
  ];
  return {
    key,
    session,
    manager,
    fetchResult,
    showToast,
    steps,
    changeIdentity: () => {
      identity = { ...identity, userId: 'another-user' };
    },
    run: () =>
      runner.run(steps as any, { ...manager.getContext('booking'), fetchResult, showToast } as any),
  };
}
afterEach(() => sessionStorage.clear());
describe('API request recovery from confirmed pre-effect rejection', () => {
  it('releases only the original persisted identity but still aborts the success path', async () => {
    const s = setup();
    await expect(s.run()).rejects.toThrow();
    expect(s.fetchResult).toHaveBeenCalledOnce();
    expect(s.session.peek(field, null)).toBeNull();
    expect(s.manager.getContext('booking').state[field]).toBeNull();
    expect(s.manager.getContext('booking').state.bookingResult).toBeUndefined();
    expect(s.showToast).not.toHaveBeenCalled();
    expect(s.session.ensure(field, null)).not.toBe(s.key);
  });
  it.each([
    { ...rejection, httpStatus: 500 },
    { ...rejection, httpStatus: 200 },
    { ...rejection, httpStatus: undefined },
    { ...rejection, code: '34003' },
    { ...rejection, context: { messageKey: '$i18n:booking.provider_unknown' } },
    { ...rejection, context: {}, message: 'Quote expired' },
  ])('preserves an unconfirmed outcome: %j', async (result) => {
    const s = setup(result);
    await expect(s.run()).rejects.toThrow();
    expect(s.session.peek(field, s.key)).toBe(s.key);
    expect(s.manager.getContext('booking').state[field]).toBe(s.key);
    expect(s.showToast).not.toHaveBeenCalled();
  });
  it('retains the same attempt when the transport throws', async () => {
    const s = setup();
    s.fetchResult.mockRejectedValue(new Error('Connection lost'));
    await expect(s.run()).rejects.toThrow('Connection lost');
    expect(s.session.peek(field, s.key)).toBe(s.key);
    expect(s.showToast).not.toHaveBeenCalled();
  });
  it('does not change default API behavior when recovery was not declared', async () => {
    const s = setup(rejection, null);
    delete (s.steps[0].args as any).releaseRequestKeyOnRejection;
    await expect(s.run()).rejects.toThrow();
    expect(s.session.peek(field, s.key)).toBe(s.key);
  });
  it('does not clear the attempt following success', async () => {
    const s = setup({ code: '0', data: { shipmentPid: 'existing-shipment' } });
    await s.run();
    expect(s.session.peek(field, s.key)).toBe(s.key);
    expect(s.manager.getContext('booking').state.bookingResult).toEqual({
      shipmentPid: 'existing-shipment',
    });
    expect(s.showToast).toHaveBeenCalledWith('Booked', 'success');
  });
  it('rejects malformed policies before making a request', async () => {
    const s = setup(rejection, { ...policy, responseCode: '0' });
    await expect(s.run()).rejects.toThrow('Invalid rejected-request release policy');
    expect(s.fetchResult).not.toHaveBeenCalled();
    expect(s.session.peek(field, s.key)).toBe(s.key);
  });
  it('retains persisted identity if scope state changes while the request is in flight', async () => {
    const s = setup();
    s.fetchResult.mockImplementation(async () => {
      s.manager.updateState('booking', field, null);
      return rejection;
    });
    await expect(s.run()).rejects.toThrow('Request identity changed');
    expect(s.session.peek(field, null)).toBe(s.key);
    expect(s.showToast).not.toHaveBeenCalled();
  });
  it('never clears another authenticated user session', async () => {
    const s = setup();
    const originalStorage = { ...sessionStorage };
    s.fetchResult.mockImplementation(async () => {
      s.changeIdentity();
      return rejection;
    });
    await expect(s.run()).rejects.toThrow('Request key identity changed');
    expect({ ...sessionStorage }).toEqual(originalStorage);
    expect(s.showToast).not.toHaveBeenCalled();
  });
});
