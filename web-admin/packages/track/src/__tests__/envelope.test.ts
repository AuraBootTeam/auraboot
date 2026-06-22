import { it, expect, vi } from 'vitest';
import { buildEvent, generateEventId, sanitizeRoute } from '../envelope';

it('builds a flat camelCase page_view envelope', () => {
  const e = buildEvent({
    eventName: 'page_view',
    eventCategory: 'navigation',
    clientSessionId: 's1',
    props: { routeTemplate: '/p/c/x' },
  });
  expect(e.eventName).toBe('page_view');
  expect(e.schemaVersion).toBe('1');
  expect(e.source).toBe('web');
  expect(e.eventId).toMatch(/^[0-9A-HJKMNP-TV-Z]{26}$/); // ULID
  expect(e.occurredAt).toBeTruthy();
});

it('generates ULID entropy without Math.random', () => {
  const randomSpy = vi.spyOn(Math, 'random').mockImplementation(() => {
    throw new Error('Math.random must not be used for event ids');
  });
  try {
    expect(generateEventId()).toMatch(/^[0-9A-HJKMNP-TV-Z]{26}$/);
    expect(randomSpy).not.toHaveBeenCalled();
  } finally {
    randomSpy.mockRestore();
  }
});

it('sanitizeRoute strips ids and query', () => {
  expect(sanitizeRoute('/p/c/order_list/9182734?tab=x')).toBe('/p/c/order_list/:id');
});

it('carries anonId into the envelope when provided (public/anonymous mode)', () => {
  const e = buildEvent({
    eventName: 'page_view',
    eventCategory: 'navigation',
    clientSessionId: 's1',
    anonId: 'anon-abc-123',
    props: {},
  });
  expect(e.anonId).toBe('anon-abc-123');
});

it('omits anonId when not provided (authenticated mode unchanged)', () => {
  const e = buildEvent({
    eventName: 'page_view',
    eventCategory: 'navigation',
    clientSessionId: 's1',
    props: {},
  });
  expect(e.anonId).toBeUndefined();
});
