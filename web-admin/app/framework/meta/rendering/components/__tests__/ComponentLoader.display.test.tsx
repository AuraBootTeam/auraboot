import React from 'react';
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { getRuntimeComponentEntry } from '../runtime-component-loaders';

describe('registered display component module', () => {
  it('loads the actual registered export through every display alias and renders plain facts', async () => {
    for (const alias of ['SmartDisplay', 'Display', 'display']) {
      const entry = getRuntimeComponentEntry(alias);
      expect(entry).toBeDefined();
      const module = await entry!.loader();
      const Component = module[entry!.exportName];
      expect(typeof Component).toBe('function');
      const view = render(<Component label="Tax rate" value="13%" appearance="plain" />);
      expect(screen.getByText('13%')).toBeVisible();
      expect(screen.queryByRole('textbox')).not.toBeInTheDocument();
      view.unmount();
    }
  });
  it('keeps zero distinct from missing data and treats text as text', async () => {
    const entry = getRuntimeComponentEntry('SmartDisplay')!;
    const module = await entry.loader();
    const Component = module[entry.exportName];
    expect(typeof Component).toBe('function');
    const view = render(<Component value={0} appearance="plain" />);
    expect(screen.getByText('0')).toBeVisible();
    view.rerender(<Component value={null} appearance="plain" />);
    expect(screen.getByText('-')).toBeVisible();
    view.rerender(<Component value={'<img src=x onerror=bad>'} appearance="plain" />);
    expect(screen.getByText('<img src=x onerror=bad>')).toBeVisible();
    expect(screen.queryByRole('img')).not.toBeInTheDocument();
  });
});
