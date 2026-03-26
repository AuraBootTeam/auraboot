import '@testing-library/jest-dom';
import { vi } from 'vitest';

// Mock React Router
vi.mock('react-router', () => {
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
    useActionData: vi.fn(() => null),
    useLoaderData: vi.fn(() => ({})),
    useNavigate: vi.fn(() => vi.fn()),
    useLocation: vi.fn(() => ({ pathname: '/', search: '', hash: '', state: null })),
    useParams: vi.fn(() => ({})),
    useSearchParams: vi.fn(() => [new URLSearchParams(), vi.fn()]),
    redirect: vi.fn((url: string) => ({ url })),
    createCookieSessionStorage: vi.fn(() => mockCookieSessionStorage),
  };
});

// Mock window.matchMedia
Object.defineProperty(window, 'matchMedia', {
  writable: true,
  value: vi.fn().mockImplementation(query => ({
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
