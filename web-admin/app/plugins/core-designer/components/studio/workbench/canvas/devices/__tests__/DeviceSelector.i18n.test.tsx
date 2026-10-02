import React from 'react';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { parse } from 'yaml';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { I18nProvider } from '~/contexts/I18nContext';
import { DeviceSelector } from '../DeviceSelector';

const dictionary = (locale: string) => parse(readFileSync(
  path.resolve(process.cwd(), `../platform/src/main/resources/i18n.${locale}.yaml`), 'utf8',
));
function localized(locale: string, element: React.ReactNode) {
  return render(<I18nProvider initialLocale={locale} initialData={dictionary(locale)}>{element}</I18nProvider>);
}
beforeEach(() => localStorage.clear());
afterEach(() => cleanup());

describe('device selector localization and emitted values', () => {
  it.each([
    ['en-US', 'Desktop (1920×1080)', 'Desktop', 'Laptop', 'Tablet', 'Mobile', 'Custom dimensions', 'Switch to landscape'],
    ['zh-CN', '桌面 (1920×1080)', '桌面', '笔记本', '平板', '手机', '自定义尺寸', '切换为横屏'],
  ])('localizes presets and groups in %s while retaining device IDs', (locale, selected, desktop, laptop, tablet, mobile, custom, orientation) => {
    const onDeviceChange = vi.fn();
    const onOrientationChange = vi.fn();
    localized(locale, <DeviceSelector selectedId="desktop-1920" orientation="portrait"
      onDeviceChange={onDeviceChange} onOrientationChange={onOrientationChange} />);
    fireEvent.click(screen.getByRole('button', { name: selected }));
    for (const label of [desktop, laptop, tablet, mobile, custom]) expect(screen.getByText(label)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /iPhone 15 Pro Max/ }));
    expect(onDeviceChange).toHaveBeenCalledWith('iphone-15-pro-max');
    expect(screen.queryByText(mobile)).not.toBeInTheDocument();
    fireEvent.click(screen.getByTitle(orientation));
    expect(onOrientationChange).toHaveBeenCalledWith('landscape');
  });

  it.each([
    ['en-US', 'Desktop (1920×1080)', 'Custom dimensions', 'Width', 'Height', 'Apply', 'Cancel'],
    ['zh-CN', '桌面 (1920×1080)', '自定义尺寸', '宽度', '高度', '应用', '取消'],
  ])('localizes custom editing in %s without changing the stored shape', (locale, selected, custom, width, height, apply, cancel) => {
    const onDeviceChange = vi.fn();
    const onCustomDeviceChange = vi.fn();
    localized(locale, <DeviceSelector selectedId="desktop-1920" orientation="portrait"
      onDeviceChange={onDeviceChange} onOrientationChange={vi.fn()} onCustomDeviceChange={onCustomDeviceChange} />);
    fireEvent.click(screen.getByRole('button', { name: selected }));
    fireEvent.click(screen.getByRole('button', { name: custom }));
    expect(screen.getByRole('button', { name: cancel })).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText(width), { target: { value: '1280' } });
    fireEvent.change(screen.getByLabelText(height), { target: { value: '720' } });
    fireEvent.click(screen.getByRole('button', { name: apply }));
    expect(onCustomDeviceChange).toHaveBeenCalledWith({ name: '自定义', width: 1280, height: 720 });
    expect(onDeviceChange).toHaveBeenCalledWith('custom');
  });
});
