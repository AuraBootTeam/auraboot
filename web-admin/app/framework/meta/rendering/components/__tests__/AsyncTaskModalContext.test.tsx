import { render, screen, fireEvent } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
import {
  AsyncTaskModalProvider,
  AsyncTaskModalHost,
  useAsyncTaskModalSink,
} from '../AsyncTaskModalContext';

function Driver() {
  const sink = useAsyncTaskModalSink()!;
  return (
    <div>
      <button
        onClick={() =>
          sink.setActiveTask({
            status: 'running',
            taskLabel: '同步物料',
            progress: 40,
            progressMessage: '{"processed":4,"total":10,"ok":3,"failed":1,"skipped":0}',
          })
        }
      >
        start
      </button>
    </div>
  );
}

function setup() {
  return render(
    <AsyncTaskModalProvider>
      <Driver />
      <AsyncTaskModalHost />
    </AsyncTaskModalProvider>,
  );
}

describe('AsyncTaskModalHost minimize → chip', () => {
  it('shows the modal then collapses to a chip on 后台运行, and re-expands on click', () => {
    setup();
    fireEvent.click(screen.getByText('start'));
    // Modal is shown (full).
    expect(screen.getByText('同步物料')).toBeTruthy();
    // Collapse to background.
    fireEvent.click(screen.getByText('后台运行'));
    const chip = screen.getByTestId('async-task-chip');
    expect(chip.textContent).toContain('同步物料 40%');
    expect(chip.textContent).toContain('3/10'); // live ok/total from progress json
    // Re-expand.
    fireEvent.click(chip);
    expect(screen.getByText('同步物料')).toBeTruthy();
  });
});
