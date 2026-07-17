import '@testing-library/jest-dom';
import { cleanup } from '@testing-library/react';
import { afterEach, beforeEach, vi } from 'vitest';

// Mock React Router
vi.mock('react-router', async () => {
  const actual = await vi.importActual<typeof import('react-router')>('react-router');
  const mockSession = {
    get: vi.fn(),
    set: vi.fn(),
    unset: vi.fn(),
  };

  const mockCookieSessionStorage = {
    getSession: vi.fn(async () => mockSession),
    commitSession: vi.fn(async () => 'session=mock'),
    destroySession: vi.fn(async () => 'session='),
  };

  return {
    ...actual,
    useActionData: vi.fn(() => null),
    useLoaderData: vi.fn(() => ({})),
    useNavigate: vi.fn(() => vi.fn()),
    useLocation: vi.fn(() => ({ pathname: '/', search: '', hash: '', state: null })),
    useParams: vi.fn(() => ({})),
    useSearchParams: vi.fn(() => [new URLSearchParams(), vi.fn()]),
    redirect: vi.fn((url: string, init?: ResponseInit | number) =>
      typeof init === 'number' ? { url, status: init } : { url, ...init },
    ),
    createCookieSessionStorage: vi.fn(() => mockCookieSessionStorage),
  };
});

afterEach(() => {
  cleanup();
});

function createStorageMock() {
  const storage = new Map<string, string>();
  return {
    getItem: (key: string) => storage.get(key) ?? null,
    setItem: (key: string, value: string) => {
      storage.set(key, value);
    },
    removeItem: (key: string) => {
      storage.delete(key);
    },
    clear: () => {
      storage.clear();
    },
  };
}

// This setup file is shared by every project, but BFF/server tests opt into
// `@vitest-environment node` — where there is no DOM to stub. Guard the browser-only
// mocks so those files can load instead of dying on `window is not defined`.
const hasDom = typeof window !== 'undefined';

beforeEach(() => {
  if (!hasDom) return;
  Object.defineProperty(window, 'localStorage', {
    value: createStorageMock(),
    configurable: true,
  });
  Object.defineProperty(window, 'sessionStorage', {
    value: createStorageMock(),
    configurable: true,
  });
});

if (hasDom) {
  // Mock window.matchMedia
  Object.defineProperty(window, 'matchMedia', {
    writable: true,
    value: vi.fn().mockImplementation((query) => ({
      matches: false,
      media: query,
      onchange: null,
      addListener: vi.fn(), // deprecated
      removeListener: vi.fn(), // deprecated
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      dispatchEvent: vi.fn(),
    })),
  });
}

class MockResizeObserver implements ResizeObserver {
  constructor(_callback: ResizeObserverCallback) {}

  observe = vi.fn();
  unobserve = vi.fn();
  disconnect = vi.fn();
}

class MockIntersectionObserver implements IntersectionObserver {
  readonly root = null;
  readonly rootMargin = '';
  readonly thresholds = [];

  constructor(_callback: IntersectionObserverCallback) {}

  observe = vi.fn();
  unobserve = vi.fn();
  disconnect = vi.fn();
  takeRecords = vi.fn(() => []);
}

globalThis.ResizeObserver = MockResizeObserver;
globalThis.IntersectionObserver = MockIntersectionObserver;

if (hasDom) {
  // Mock scrollIntoView
  Element.prototype.scrollIntoView = vi.fn();

  // Mock HTMLElement methods
  HTMLElement.prototype.scrollTo = vi.fn();
  HTMLElement.prototype.scroll = vi.fn();
}

// Mock console methods to reduce noise in tests
global.console = {
  ...console,
  log: vi.fn(),
  debug: vi.fn(),
  info: vi.fn(),
  warn: vi.fn(),
  error: vi.fn(),
};
