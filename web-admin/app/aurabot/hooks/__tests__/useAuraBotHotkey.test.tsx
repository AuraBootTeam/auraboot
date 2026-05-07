import { describe, expect, it, vi } from 'vitest';
import { fireEvent } from '@testing-library/react';
import { renderWithShell } from '../../__tests__/test-utils';
import { useAuraBotShell } from '../../AuraBotProvider';
import { useAuraBotHotkey } from '../useAuraBotHotkey';

function HotkeyHarness({ onFocusInput }: { onFocusInput?: () => void }) {
  const { panelState } = useAuraBotShell();
  useAuraBotHotkey({ onFocusInput });
  return <div data-testid="state">{panelState}</div>;
}

describe('useAuraBotHotkey', () => {
  it('Cmd+K toggles hidden ↔ expanded', () => {
    const { getByTestId } = renderWithShell(<HotkeyHarness />);
    expect(getByTestId('state').textContent).toBe('hidden');

    fireEvent.keyDown(document, { key: 'k', metaKey: true });
    expect(getByTestId('state').textContent).toBe('expanded');

    fireEvent.keyDown(document, { key: 'k', metaKey: true });
    expect(getByTestId('state').textContent).toBe('hidden');
  });

  it('Cmd+Shift+K opens panel and calls onFocusInput', async () => {
    const onFocus = vi.fn();
    const { getByTestId } = renderWithShell(
      <HotkeyHarness onFocusInput={onFocus} />,
    );
    fireEvent.keyDown(document, { key: 'k', metaKey: true, shiftKey: true });
    expect(getByTestId('state').textContent).toBe('expanded');
    // queueMicrotask is sync-ish; await a microtask flush
    await Promise.resolve();
    expect(onFocus).toHaveBeenCalled();
  });

  it('Escape minimizes from expanded but not from fullscreen', () => {
    function Helper() {
      const { setPanelState, panelState } = useAuraBotShell();
      useAuraBotHotkey();
      return (
        <>
          <div data-testid="state">{panelState}</div>
          <button data-testid="to-full" onClick={() => setPanelState('fullscreen')}>
            full
          </button>
          <button data-testid="to-expand" onClick={() => setPanelState('expanded')}>
            expand
          </button>
        </>
      );
    }
    const { getByTestId } = renderWithShell(<Helper />);

    fireEvent.click(getByTestId('to-full'));
    expect(getByTestId('state').textContent).toBe('fullscreen');
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(getByTestId('state').textContent).toBe('fullscreen');

    fireEvent.click(getByTestId('to-expand'));
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(getByTestId('state').textContent).toBe('hidden');
  });

  it('ignores Cmd+K inside [data-no-aurabot-hotkey]', () => {
    function Helper() {
      const { panelState } = useAuraBotShell();
      useAuraBotHotkey();
      return (
        <div>
          <div data-testid="state">{panelState}</div>
          <div data-no-aurabot-hotkey>
            <input data-testid="opt-out" />
          </div>
        </div>
      );
    }
    const { getByTestId } = renderWithShell(<Helper />);
    const input = getByTestId('opt-out') as HTMLInputElement;
    input.focus();
    fireEvent.keyDown(document, { key: 'k', metaKey: true });
    // Panel must remain hidden because the key event originated inside the
    // opt-out subtree.
    expect(getByTestId('state').textContent).toBe('hidden');
  });
});
