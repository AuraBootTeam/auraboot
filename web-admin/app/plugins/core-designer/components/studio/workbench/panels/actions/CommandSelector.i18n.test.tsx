import React from 'react';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { CommandSelector } from './CommandSelector';
import type { CommandDefinitionDTO } from './types';
import pluginCommands from '../../../../../../../../../plugins/platform-admin/config/commands.json';

const f = vi.hoisted(() => ({ locale: 'en-US' }));
vi.mock('~/contexts/I18nContext', () => ({ useI18n: () => ({ locale: f.locale }) }));
const imported = pluginCommands.find(c => c.code === 'admin:create_member')!;
const command: CommandDefinitionDTO = {
  pid: 'command-1', code: imported.code, displayName: 'Create member',
  modelCode: imported.modelCode, description: imported.description,
  localizedDescriptions: imported.extension!.localizedDescriptions,
};
const legacy: CommandDefinitionDTO = { pid: 'legacy-1', code: 'legacy:save', displayName: 'Legacy save', modelCode: 'member', description: 'Legacy description' };
const changed = vi.fn();
const refreshed = vi.fn();
function props() { return { commands: [command], loading: false, error: null as string | null, onChange: changed, onRefresh: refreshed }; }
afterEach(cleanup);
beforeEach(() => { vi.clearAllMocks(); f.locale = 'en-US'; });

describe('command selector descriptions and localized state semantics', () => {
  for (const locale of ['en-US', 'zh-CN']) {
    const en = locale === 'en-US';
    it(`${locale} resolves the real plugin description and emits the original command identity`, () => {
      f.locale = locale; render(<CommandSelector {...props()} />);
      const toggle = screen.getAllByRole('button')[0];
      fireEvent.click(toggle);
      const description = command.localizedDescriptions![locale];
      expect(screen.getByText(description)).toBeTruthy();
      if (en) expect(screen.queryByText(command.description!)).toBeNull();
      fireEvent.click(screen.getByRole('button', { name: new RegExp('Create member') }));
      expect(changed).toHaveBeenCalledExactlyOnceWith(command.code, command);
      expect(toggle).toHaveAttribute('aria-expanded', 'false');
      expect(screen.queryByText(description)).toBeNull();
    });
    it(`${locale} loading blocks selection and duplicate refresh`, () => {
      f.locale = locale; render(<CommandSelector {...props()} loading />);
      expect(screen.getByText(en ? 'Loading...' : '\u52a0\u8f7d\u4e2d...')).toBeTruthy();
      const toggle = screen.getAllByRole('button')[0];
      const button = screen.getAllByRole('button')[1];
      expect(toggle).toBeDisabled(); expect(button).toBeDisabled();
      fireEvent.click(toggle); fireEvent.click(button);
      expect(changed).not.toHaveBeenCalled(); expect(refreshed).not.toHaveBeenCalled();
    });
    it(`${locale} successful empty state and explicit refresh remain distinct`, () => {
      f.locale = locale; render(<CommandSelector {...props()} commands={[]} />);
      fireEvent.click(screen.getAllByRole('button')[0]);
      expect(screen.getByText(en ? 'No available commands' : '\u6682\u65e0\u53ef\u7528\u547d\u4ee4')).toBeTruthy();
      fireEvent.click(screen.getAllByRole('button')[1]);
      expect(refreshed).toHaveBeenCalledTimes(1); expect(changed).not.toHaveBeenCalled();
    });
    it(`${locale} permission failure preserves the error and permits only an explicit refresh`, () => {
      f.locale = locale; render(<CommandSelector {...props()} commands={[]} error="403: forbidden" />);
      const toggle = screen.getAllByRole('button')[0];
      expect(toggle).toBeDisabled(); fireEvent.click(toggle);
      expect(screen.getByText('403: forbidden')).toBeTruthy();
      expect(screen.queryByText(en ? 'No available commands' : '\u6682\u65e0\u53ef\u7528\u547d\u4ee4')).toBeNull();
      fireEvent.click(screen.getAllByRole('button')[1]);
      expect(refreshed).toHaveBeenCalledTimes(1); expect(changed).not.toHaveBeenCalled();
    });
  }
  it('legacy scalar descriptions still render without inventing a translation', () => {
    render(<CommandSelector {...props()} commands={[legacy]} />);
    fireEvent.click(screen.getAllByRole('button')[0]);
    expect(screen.getByText('Legacy description')).toBeTruthy();
  });
  it('changing locale preserves the selected command and open dropdown without emitting a change', () => {
    const p = { ...props(), value: command.code };
    const view = render(<CommandSelector {...p} />);
    fireEvent.click(screen.getAllByRole('button')[0]);
    expect(screen.getByText(command.localizedDescriptions!['en-US'])).toBeTruthy();
    f.locale = 'zh-CN'; view.rerender(<CommandSelector {...p} />);
    expect(screen.getByText(command.localizedDescriptions!['zh-CN'])).toBeTruthy();
    expect(screen.queryByText(command.localizedDescriptions!['en-US'])).toBeNull();
    expect(screen.getByRole('button', { name: '\u5173\u8054\u547d\u4ee4' })).toHaveAttribute('aria-expanded', 'true');
    expect(changed).not.toHaveBeenCalled(); expect(refreshed).not.toHaveBeenCalled();
  });
});
