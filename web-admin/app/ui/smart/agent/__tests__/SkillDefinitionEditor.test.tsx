import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { SkillDefinitionEditor } from '../SkillDefinitionEditor';
const mocks = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), navigate: vi.fn(), allowed: new Set(['acp_agent_skill', 'acp_agent_definition', 'acp.runtime.manage']) }));
vi.mock('~/shared/services/http-client', () => ({ get: mocks.get, post: mocks.post }));
vi.mock('react-router', () => ({ Link: ({ children, to }: any) => <a href={to}>{children}</a>, useNavigate: () => mocks.navigate }));
vi.mock('~/contexts/I18nContext', () => ({ useI18n: () => ({ locale: 'en', t: (_: string, _params: unknown, fallback: string) => fallback }) }));
vi.mock('~/contexts/ToastContext', () => ({ useToastContext: () => ({ showSuccessToast: vi.fn() }) }));
vi.mock('~/contexts/AuthContext', () => ({ usePermissions: () => ({ hasPermission: (code: string) => mocks.allowed.has(code) }) }));
vi.mock('~/plugins/core-aurabot/components-internal/ChatBiResultCard', () => ({ ChatBiResultCard: () => <div /> }));
vi.mock('~/plugins/core-aurabot/components-internal/ResultContractView', () => ({ ResultContractView: ({ contract }: any) => <div>{contract.status} {contract.textSummary}</div> }));
const record = { pid: 'skill-record', skill_code: 'review', skill_name: 'BOM review', skill_status: 'active', skill_level: 'atomic', execution_mode: 'template', skill_tools: ['list:part'], skill_input_schema: { type: 'object', properties: {} } };
const ok = (data: unknown) => ({ code: '0', data });
beforeEach(() => {
  Element.prototype.scrollIntoView = vi.fn();
  mocks.get.mockReset(); mocks.post.mockReset(); mocks.allowed = new Set(['acp_agent_skill', 'acp_agent_definition', 'acp.runtime.manage']);
  mocks.get.mockImplementation(async (path: string) => path.includes('registry') ? ok({ tools: [{ toolCode: 'list:part', toolName: 'Read parts' }] }) : ok(record));
});
describe('SkillDefinitionEditor governance', () => {
  it('uses no nested form inside its DSL form host and requires fresh consent for every real run', async () => {
    mocks.post.mockResolvedValue(ok({ status: 'SUCCESS', textSummary: 'Reviewed 3 current parts' }));
    const { container } = render(<form><SkillDefinitionEditor runtime={{ recordPid: record.pid }} /></form>);
    await screen.findByDisplayValue('BOM review');
    expect(container.querySelectorAll('form')).toHaveLength(1);
    const run = screen.getByRole('button', { name: 'Run once' });
    expect(run).toBeDisabled();
    fireEvent.click(screen.getByLabelText('I reviewed the input and allow this real execution.'));
    fireEvent.click(run);
    expect(await screen.findByTestId('skill-trial-result')).toHaveTextContent('Reviewed 3 current parts');
    expect(mocks.post).toHaveBeenCalledWith('/api/agent/skills/review/execute', { input: {}, userMessage: '' });
    expect(run).toBeDisabled();
  });
  it('does not describe an approval stop as success and never retries it automatically', async () => {
    mocks.post.mockResolvedValue(ok({ status: 'FAILED', data: { approvalRequired: true, approvalPid: 'approval' } }));
    render(<SkillDefinitionEditor runtime={{ recordPid: record.pid }} />);
    await screen.findByDisplayValue('BOM review');
    fireEvent.click(screen.getByLabelText('I reviewed the input and allow this real execution.'));
    fireEvent.click(screen.getByRole('button', { name: 'Run once' }));
    expect(await screen.findByTestId('skill-trial-result')).toHaveTextContent('Paused for confirmation or approval');
    expect(mocks.post).toHaveBeenCalledTimes(1);
    expect(screen.getByRole('button', { name: 'Run once' })).toBeDisabled();
  });
  it('blocks running unsaved edits and viewers without runtime permission', async () => {
    mocks.allowed.delete('acp.runtime.manage');
    render(<SkillDefinitionEditor runtime={{ recordPid: record.pid }} />);
    await screen.findByDisplayValue('BOM review');
    expect(screen.getByRole('button', { name: 'Run once' })).toBeDisabled();
    fireEvent.change(screen.getByLabelText('Skill name'), { target: { value: 'Changed' } });
    expect(screen.getByRole('button', { name: 'Choose a colleague' })).toBeDisabled();
    expect(mocks.post).not.toHaveBeenCalled();
  });
  it('fails closed when an existing definition cannot load', async () => {
    mocks.get.mockImplementation(async (path: string) => path.includes('registry') ? ok({ tools: [] }) : { code: '403', message: 'Permission denied' });
    render(<SkillDefinitionEditor runtime={{ recordPid: record.pid }} />);
    expect(await screen.findByRole('alert')).toHaveTextContent('Permission denied');
    await waitFor(() => expect(screen.getByRole('button', { name: 'Save skill' })).toBeDisabled());
    expect(mocks.post).not.toHaveBeenCalled();
  });
});
