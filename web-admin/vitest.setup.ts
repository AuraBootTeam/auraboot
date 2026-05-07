import '@testing-library/jest-dom';
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

// @testing-library/react auto-runs cleanup after each test in React 16+

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

beforeEach(() => {
  Object.defineProperty(window, 'localStorage', {
    value: createStorageMock(),
    configurable: true,
  });
  Object.defineProperty(window, 'sessionStorage', {
    value: createStorageMock(),
    configurable: true,
  });
});

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

// Mock ResizeObserver
global.ResizeObserver = vi.fn().mockImplementation(() => ({
  observe: vi.fn(),
  unobserve: vi.fn(),
  disconnect: vi.fn(),
}));

// Mock IntersectionObserver
global.IntersectionObserver = vi.fn().mockImplementation(() => ({
  observe: vi.fn(),
  unobserve: vi.fn(),
  disconnect: vi.fn(),
}));

// Mock scrollIntoView
Element.prototype.scrollIntoView = vi.fn();

// Mock HTMLElement methods
HTMLElement.prototype.scrollTo = vi.fn();
HTMLElement.prototype.scroll = vi.fn();

// Mock console methods to reduce noise in tests
global.console = {
  ...console,
  log: vi.fn(),
  debug: vi.fn(),
  info: vi.fn(),
  warn: vi.fn(),
  error: vi.fn(),
};
