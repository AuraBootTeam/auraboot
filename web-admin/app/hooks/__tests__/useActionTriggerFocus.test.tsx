import { useState } from 'react';
import { act, fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { useActionTriggerFocus } from '../useActionTriggerFocus';

function Harness({ pending }: { pending: Promise<void> }) {
  const [busy, setBusy] = useState(false);const capture = useActionTriggerFocus(busy);
  return <><button disabled={busy} onClick={async () => {
    capture();setBusy(true);await pending;setBusy(false);
  }}>Action</button><input aria-label="Other input" /></>;
}

describe('action trigger focus after a disabled pending action', () => {
  it('restores the original connected trigger after cancellation reenables it', async () => {
    let finish!: () => void;render(<Harness pending={new Promise(resolve => { finish = resolve; })} />);
    const trigger = screen.getByRole('button');trigger.focus();fireEvent.click(trigger);
    expect(trigger).toBeDisabled();trigger.blur();
    await act(async () => finish());
    expect(trigger).toBeEnabled();expect(trigger).toHaveFocus();
  });

  it('does not steal focus when the user has moved to another control', async () => {
    let finish!: () => void;render(<Harness pending={new Promise(resolve => { finish = resolve; })} />);
    const trigger = screen.getByRole('button');trigger.focus();fireEvent.click(trigger);
    const other = screen.getByRole('textbox');other.focus();
    await act(async () => finish());expect(other).toHaveFocus();
  });
});
