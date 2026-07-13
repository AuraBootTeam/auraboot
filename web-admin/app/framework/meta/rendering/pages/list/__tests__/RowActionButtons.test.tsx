import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, cleanup } from '@testing-library/react';
import { RowActionButtons } from '../RowActionButtons';
import type { ButtonConfig } from '~/framework/meta/schemas/types';

const buttons: ButtonConfig[] = [
  { code: 'view', label: 'View' } as ButtonConfig,
  { code: 'edit', label: 'Edit' } as ButtonConfig,
  { code: 'delete', label: 'Delete', danger: true } as ButtonConfig,
];

const record = { id: 1, name: 'task-1' };

const setup = (override?: Partial<Parameters<typeof RowActionButtons>[0]>) => {
  const handleAction = vi.fn();
  const props = {
    buttons,
    record,
    evaluateVisibleWhen: () => true,
    resolveButtonLabel: (btn: ButtonConfig) =>
      typeof btn.label === 'string' ? btn.label : btn.code,
    handleAction,
    ...(override || {}),
  };
  const utils = render(<RowActionButtons {...props} />);
  return { ...utils, handleAction };
};

describe('RowActionButtons — More actions dropdown', () => {
  beforeEach(() => {
    cleanup();
    // jsdom defaults innerWidth/innerHeight to 1024x768; override via Object.defineProperty
    // to keep position math deterministic across the suite.
    Object.defineProperty(window, 'innerWidth', { configurable: true, value: 1280 });
    Object.defineProperty(window, 'innerHeight', { configurable: true, value: 800 });
  });

  it('renders the More actions trigger when there are >= 2 visible buttons', () => {
    setup();
    expect(screen.getByTestId('row-action-more')).toBeInTheDocument();
    // Dropdown is closed by default — portal not mounted.
    expect(screen.queryByTestId('row-action-dropdown')).not.toBeInTheDocument();
  });

  it('opens the dropdown menu on trigger click and renders all overflow buttons', () => {
    const { handleAction } = setup();

    fireEvent.click(screen.getByTestId('row-action-more'));

    const dropdown = screen.getByTestId('row-action-dropdown');
    expect(dropdown).toBeInTheDocument();
    // Primary "view" stays inline; "edit" + "delete" go into dropdown.
    expect(screen.getByTestId('row-action-edit')).toBeInTheDocument();
    expect(screen.getByTestId('row-action-delete')).toBeInTheDocument();

    // Position must be committed in a single layout pass — never stuck at (0, 0).
    // (We assert "ready" state by checking visibility is not 'hidden'.)
    expect((dropdown as HTMLElement).style.visibility).not.toBe('hidden');

    // Clicking a menu item dispatches handleAction and closes the dropdown.
    fireEvent.click(screen.getByTestId('row-action-delete'));
    expect(handleAction).toHaveBeenCalledTimes(1);
    expect(handleAction).toHaveBeenCalledWith(
      expect.objectContaining({ code: 'delete' }),
      record,
    );
    expect(screen.queryByTestId('row-action-dropdown')).not.toBeInTheDocument();
  });

  it('signals open state via data-row-actions-open so the row wrapper keeps the trigger visible', () => {
    setup();

    const trigger = screen.getByTestId('row-action-more');
    const wrapper = trigger.closest('[data-row-actions-open]') ||
      trigger.parentElement; // before open, attribute is absent
    // Pre-open: attribute should not be set on the relative wrapper.
    expect(trigger.parentElement?.getAttribute('data-row-actions-open')).toBeNull();

    fireEvent.click(trigger);
    expect(trigger.parentElement?.getAttribute('data-row-actions-open')).toBe('true');

    // Voids unused-var lint
    void wrapper;
  });

  it('closes when clicking outside both trigger and dropdown', () => {
    setup();
    fireEvent.click(screen.getByTestId('row-action-more'));
    expect(screen.getByTestId('row-action-dropdown')).toBeInTheDocument();

    // mousedown outside the menu and the trigger closes the dropdown.
    fireEvent.mouseDown(document.body);
    expect(screen.queryByTestId('row-action-dropdown')).not.toBeInTheDocument();
  });

  it('renders single visible button inline (no More-actions trigger)', () => {
    setup({ buttons: [{ code: 'view', label: 'View' } as ButtonConfig] });
    expect(screen.getByTestId('row-action-view')).toBeInTheDocument();
    expect(screen.queryByTestId('row-action-more')).not.toBeInTheDocument();
  });

  it('lays out every `inline: true` button in the row, not just the first', () => {
    // ux-design-system.md §3 — up to 3 row actions render inline, rest collapse.
    setup({
      buttons: [
        { code: 'view', label: 'View', inline: true } as ButtonConfig,
        { code: 'edit', label: 'Edit' } as ButtonConfig,
        { code: 'publish', label: 'Publish', inline: true } as ButtonConfig,
      ],
    });
    expect(screen.getByTestId('row-action-view')).toBeInTheDocument();
    expect(screen.getByTestId('row-action-publish')).toBeInTheDocument();
    // The non-opted-in button stays behind the overflow trigger.
    expect(screen.queryByTestId('row-action-edit')).not.toBeInTheDocument();
    expect(screen.getByTestId('row-action-more')).toBeInTheDocument();
  });

  it('drops the overflow trigger when every button is inline', () => {
    setup({
      buttons: [
        { code: 'view', label: 'View', inline: true } as ButtonConfig,
        { code: 'edit', label: 'Edit', inline: true } as ButtonConfig,
      ],
    });
    expect(screen.getByTestId('row-action-view')).toBeInTheDocument();
    expect(screen.getByTestId('row-action-edit')).toBeInTheDocument();
    expect(screen.queryByTestId('row-action-more')).not.toBeInTheDocument();
  });

  it('makes inline labels physically unable to wrap', () => {
    // Root cause of the 65px-tall rows: "统一设计器" wrapped onto a second line
    // inside a 112px column. `truncate` implies whitespace-nowrap, so the worst
    // case is now an ellipsis, never a taller row.
    setup({ buttons: [{ code: 'edit_unified', label: '统一设计器' } as ButtonConfig] });
    const button = screen.getByTestId('row-action-edit_unified');
    expect(button.className).toContain('truncate');
    expect(button).toHaveAttribute('title', '统一设计器');
  });
});
