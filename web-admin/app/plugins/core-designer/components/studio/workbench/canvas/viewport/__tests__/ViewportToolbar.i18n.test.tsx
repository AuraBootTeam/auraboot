import React from 'react';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { parse } from 'yaml';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { I18nProvider } from '~/contexts/I18nContext';
import { ViewportToolbar } from '../ViewportToolbar';
import { DEVICE_PRESETS } from '../devices';
import type { UseViewportResult } from '../types';

const dictionary = (locale: string) => parse(readFileSync(
  path.resolve(process.cwd(), `../platform/src/main/resources/i18n.${locale}.yaml`), 'utf8',
));
const zh = dictionary('zh-CN').designer_viewport;
const viewport = (overrides: Partial<UseViewportResult> = {}): UseViewportResult => ({
  zoom: 1, pan: { x: 0, y: 0 }, deviceWidth: null, deviceName: null,
  isPanning: false, transform: '', containerStyle: {}, canvasStyle: {},
  setZoom: vi.fn(), zoomIn: vi.fn(), zoomOut: vi.fn(), zoomToFit: vi.fn(),
  zoomToSelection: vi.fn(), resetZoom: vi.fn(), setPan: vi.fn(), panBy: vi.fn(),
  resetPan: vi.fn(), setDevice: vi.fn(), reset: vi.fn(), ...overrides,
});
function localized(locale: string, state: UseViewportResult) {
  return render(<I18nProvider initialLocale={locale} initialData={dictionary(locale)}>
    <ViewportToolbar viewport={state} />
  </I18nProvider>);
}
beforeEach(() => localStorage.clear());
afterEach(() => cleanup());

describe('viewport toolbar localization and action values', () => {
  it.each([
    ['en-US', 'Zoom out (Ctrl + -)', 'Zoom in (Ctrl + +)', 'Fit canvas', 'Reset (100%)', 'Panning'],
    ['zh-CN', zh.zoom_out, zh.zoom_in, zh.fit_canvas, zh.reset_zoom, zh.panning],
  ])('localizes zoom and panning in %s without changing actions', (locale, out, into, fit, reset, panning) => {
    const state = viewport({ isPanning: true });
    localized(locale, state);
    expect(screen.getByText(panning)).toBeInTheDocument();
    fireEvent.click(screen.getByTitle(out));
    fireEvent.click(screen.getByTitle(into));
    expect(state.zoomOut).toHaveBeenCalledTimes(1);
    expect(state.zoomIn).toHaveBeenCalledTimes(1);
    fireEvent.click(screen.getByRole('button', { name: '100%' }));
    fireEvent.click(screen.getByRole('button', { name: '75%' }));
    expect(state.setZoom).toHaveBeenCalledExactlyOnceWith(0.75);
    expect(screen.queryByRole('button', { name: fit })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '100%' }));
    fireEvent.click(screen.getByRole('button', { name: fit }));
    expect(state.zoomToFit).toHaveBeenCalledTimes(1);
    fireEvent.click(screen.getByRole('button', { name: '100%' }));
    fireEvent.click(screen.getByRole('button', { name: reset }));
    expect(state.resetZoom).toHaveBeenCalledTimes(1);
  });

  it.each([
    ['en-US', 'Responsive', ['Desktop', 'Laptop', 'Tablet landscape', 'Tablet', 'Mobile', 'Small mobile']],
    ['zh-CN', zh.responsive, ['desktop', 'laptop', 'tablet-landscape', 'tablet', 'mobile', 'mobile-small'].map(id => zh.devices[id])],
  ])('localizes device labels in %s and emits stable IDs', (locale, responsive, labels) => {
    const state = viewport();
    localized(locale, state);
    for (let i = 0; i < DEVICE_PRESETS.length; i++) {
      fireEvent.click(screen.getByRole('button', { name: new RegExp(responsive) }));
      fireEvent.click(screen.getByRole('button', { name: new RegExp(`${labels[i]}\\s*${DEVICE_PRESETS[i].width}px`) }));
      expect(state.setDevice).toHaveBeenLastCalledWith(DEVICE_PRESETS[i].id);
    }
    fireEvent.click(screen.getByRole('button', { name: new RegExp(responsive) }));
    fireEvent.click(screen.getByRole('button', { name: `🖥️${responsive}`, exact: true }));
    expect(state.setDevice).toHaveBeenLastCalledWith(null);
    expect(state.setDevice).toHaveBeenCalledTimes(7);
  });

  it.each([['en-US', 'Tablet landscape'], ['zh-CN', zh.devices['tablet-landscape']]])(
    'translates a stored preset name in %s without mutating the state', (locale, label) => {
      const state = viewport({ deviceName: DEVICE_PRESETS[2].name, deviceWidth: 1024 });
      localized(locale, state);
      expect(screen.getByRole('button', { name: label })).toBeInTheDocument();
      expect(state.deviceName).toBe(DEVICE_PRESETS[2].name);
      expect(state.setDevice).not.toHaveBeenCalled();
    },
  );
});
