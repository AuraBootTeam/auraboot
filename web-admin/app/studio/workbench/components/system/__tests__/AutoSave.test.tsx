import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { AutoSave } from '~/studio/workbench/components/system/AutoSave';

const mockVersionManager = {
  createVersion: vi.fn(),
};

vi.mock('~/studio/services/managers', () => ({
  getVersionManager: () => mockVersionManager,
}));

const schema = {
  id: 'page',
  name: 'Test',
  version: '1.0',
  meta: {
    title: '测试',
    createdAt: '',
    updatedAt: '',
  },
  components: [],
};

describe('AutoSave (studio implementation)', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    mockVersionManager.createVersion.mockResolvedValue(undefined);
    (globalThis.navigator as any) = {
      onLine: true,
      sendBeacon: vi.fn(),
    };
  });

  it('renders status indicator', () => {
    render(<AutoSave pageId="p1" schema={schema as any} />);
    expect(screen.getByText('未保存')).toBeInTheDocument();
  });

  it('renders manual save button disabled initially', () => {
    render(<AutoSave pageId="p1" schema={schema as any} />);
    const manualButtons = screen.getAllByTitle('没有未保存的更改');
    expect(manualButtons.length).toBeGreaterThan(0);
    manualButtons.forEach((button: HTMLElement) =>
      expect(button as HTMLButtonElement).toBeDisabled(),
    );
  });
});
