import { describe, expect, it, vi } from 'vitest';
import { act, fireEvent, screen, waitFor } from '@testing-library/react';
import { renderWithShell } from './test-utils';
import { AuraBotShellPanel } from '../AuraBotPanel';
import { useAuraBotShell } from '../AuraBotProvider';
import type { ReactNode } from 'react';

// Force the mock SkillClient path for this suite — VITE_AURABOT_USE_MOCK is
// not set in the vitest env by default, so we stub the module resolver.
vi.mock('../services/skillClient', async () => {
  const real = await vi.importActual<typeof import('../services/skillClient')>(
    '../services/skillClient',
  );
  const mock = await vi.importActual<typeof import('../services/skillClient.mock')>(
    '../services/skillClient.mock',
  );
  return {
    ...real,
    resolveSkillClient: async () => mock.mockSkillClient,
  };
});

function PanelWithOpener({ children }: { children?: ReactNode }) {
  const { setPanelState } = useAuraBotShell();
  return (
    <>
      <button data-testid="open" onClick={() => setPanelState('expanded')}>
        open
      </button>
      <AuraBotShellPanel />
      {children}
    </>
  );
}

describe('AuraBotShellPanel', () => {
  it('shows empty hint when no messages, then echoes user input via mock', async () => {
    const { getByTestId } = renderWithShell(<PanelWithOpener />);
    act(() => {
      fireEvent.click(getByTestId('open'));
    });

    // Empty-state CTA visible.
    expect(document.querySelector('[data-aurabot-example]')).not.toBeNull();

    const input = document.querySelector('[data-aurabot-input]') as HTMLTextAreaElement;
    expect(input).not.toBeNull();
    fireEvent.change(input, { target: { value: 'hello' } });

    const send = document.querySelector('[data-aurabot-send]') as HTMLButtonElement;
    expect(send.disabled).toBe(false);
    fireEvent.click(send);

    // Wait for the round-trip: user message + assistant message rendered.
    await waitFor(() => {
      const messages = document.querySelectorAll('[data-aurabot-message-id]');
      expect(messages.length).toBeGreaterThanOrEqual(2);
    });

    // Assistant text envelope contains the echoed text.
    const textEnvs = document.querySelectorAll('[data-aurabot-envelope="text"]');
    const found = Array.from(textEnvs).some((el) =>
      el.textContent?.includes('hello'),
    );
    expect(found).toBe(true);
  });

  it('renders all four panel layouts when state changes', () => {
    function Helper() {
      const { setPanelState, panelState } = useAuraBotShell();
      return (
        <>
          <span data-testid="state">{panelState}</span>
          <button data-testid="to-expanded" onClick={() => setPanelState('expanded')}>
            expanded
          </button>
          <button data-testid="to-pinned" onClick={() => setPanelState('pinned')}>
            pinned
          </button>
          <button data-testid="to-fullscreen" onClick={() => setPanelState('fullscreen')}>
            fullscreen
          </button>
          <button data-testid="to-hidden" onClick={() => setPanelState('hidden')}>
            hidden
          </button>
          <AuraBotShellPanel />
        </>
      );
    }
    const { getByTestId } = renderWithShell(<Helper />);

    act(() => fireEvent.click(getByTestId('to-expanded')));
    expect(
      document.querySelector('[data-aurabot-panel-state="expanded"]'),
    ).not.toBeNull();

    act(() => fireEvent.click(getByTestId('to-pinned')));
    expect(
      document.querySelector('[data-aurabot-panel-state="pinned"]'),
    ).not.toBeNull();

    act(() => fireEvent.click(getByTestId('to-fullscreen')));
    expect(
      document.querySelector('[data-aurabot-panel-state="fullscreen"]'),
    ).not.toBeNull();

    act(() => fireEvent.click(getByTestId('to-hidden')));
    expect(document.querySelector('[data-aurabot-panel-state]')).toBeNull();
  });

  it('Toggle button is null while panel expanded; restored on close', async () => {
    function Helper() {
      const { panelState, setPanelState } = useAuraBotShell();
      return (
        <>
          <span data-testid="state">{panelState}</span>
          <button data-testid="open" onClick={() => setPanelState('expanded')}>
            open
          </button>
          <AuraBotShellPanel />
        </>
      );
    }
    const { getByTestId } = renderWithShell(<Helper />);
    act(() => fireEvent.click(getByTestId('open')));
    const closeBtn = document.querySelector(
      '[data-aurabot-close]',
    ) as HTMLButtonElement;
    expect(closeBtn).not.toBeNull();
    act(() => fireEvent.click(closeBtn));
    expect(getByTestId('state').textContent).toBe('hidden');
  });
});
