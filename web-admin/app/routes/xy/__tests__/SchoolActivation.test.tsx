import { createRoutesStub } from 'react-router';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import SchoolActivation, { academicSemesterName, semesterDateError } from '../SchoolActivation';

const auth = vi.hoisted(() => ({ admin: true }));
const api = vi.hoisted(() => ({
  rows: new Map<string, Array<Record<string, unknown>>>(),
  exec: vi.fn(),
  list: vi.fn((model: string) => Promise.resolve(api.rows.get(model) || [])),
}));

vi.mock('~/contexts/AuthContext', () => ({
  useAuth: () => ({
    isAuthenticated: true,
    hasRole: (code: string) => auth.admin && code === 'xy_school_admin',
    hasPermission: (code: string) => auth.admin && code === 'xy.school.manage',
  }),
}));

vi.mock('../eduApi', () => ({
  xyList: api.list,
  xyExec: api.exec,
}));

function renderPage() {
  const Stub = createRoutesStub([{ path: '/', Component: SchoolActivation }]);
  return render(<Stub />);
}

describe('SchoolActivation', () => {
  beforeEach(() => {
    auth.admin = true;
    api.rows.clear();
    api.exec.mockReset();
    api.list.mockClear();
    api.exec.mockResolvedValue({ ok: true, message: '', data: {} });
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: true,
        json: async () => ({ success: true, data: null }),
      }),
    );
  });

  it('shows the ordered empty-school checklist and the teacher-code action', async () => {
    renderPage();
    expect(await screen.findByTestId('setup-progress')).toHaveTextContent('0/2');
    expect(screen.getByRole('heading', { name: '1. 开启当前学期' })).toBeVisible();
    expect(screen.getByRole('heading', { name: '2. 创建班级并选择玩法' })).toBeVisible();
    expect(screen.queryByRole('heading', { name: /导入学生名册/ })).not.toBeInTheDocument();
    expect(screen.getByText(/名册、评分等教学工作由班主任和任课老师负责/)).toBeVisible();
    expect(screen.getByRole('button', { name: '生成学校教师码' })).toBeVisible();
  });

  it('does not request enrollment data that belongs to teaching roles', async () => {
    renderPage();
    await screen.findByTestId('setup-progress');
    expect(api.list).not.toHaveBeenCalledWith('xy_enrollment', expect.anything());
  });

  it('opens the irreversible semester confirmation before executing the command', async () => {
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByRole('button', { name: '开启新学期' }));

    expect(screen.getByTestId('semester-confirm-panel')).toHaveTextContent('当前学期会关闭');
    expect(screen.getByTestId('semester-confirm-panel')).toHaveTextContent('蜂蜜余额继续保留');
    expect(screen.getByRole('button', { name: '确认开启' })).toBeDisabled();

    await user.click(screen.getByRole('checkbox'));
    await user.click(screen.getByRole('button', { name: '确认开启' }));
    await waitFor(() =>
      expect(api.exec).toHaveBeenCalledWith(
        'xy:semester_open',
        expect.objectContaining({ name: expect.stringContaining('学期') }),
      ),
    );
  });

  it('generates a teacher code through the fixed product endpoint', async () => {
    const user = userEvent.setup();
    const fetchMock = vi.mocked(fetch);
    fetchMock
      .mockResolvedValueOnce({
        ok: true,
        json: async () => ({ success: true, data: null }),
      } as Response)
      .mockResolvedValueOnce({
        ok: true,
        json: async () => ({ success: true, data: { code: 'TEACH-123' } }),
      } as Response);
    renderPage();
    await user.click(await screen.findByRole('button', { name: '生成学校教师码' }));
    expect(await screen.findByTestId('teacher-code')).toHaveTextContent('TEACH-123');
    expect(fetchMock).toHaveBeenLastCalledWith(
      '/_action/xy/teacher-code',
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({ action: 'generate' }),
      }),
    );
  });

  it('rejects non-school-admin access', async () => {
    auth.admin = false;
    renderPage();
    expect(await screen.findByTestId('school-activation-no-access')).toHaveTextContent(
      '仅供学校管理员',
    );
    expect(fetch).not.toHaveBeenCalled();
  });
});

describe('school activation helpers', () => {
  it('validates semester input and derives the academic term', () => {
    expect(semesterDateError('', '', '')).toBe('请填写学期名称');
    expect(semesterDateError('2026-2027学年上学期', '2026-09-01', '2026-08-31')).toBe(
      '结束日期不能早于开始日期',
    );
    expect(semesterDateError('2026-2027学年上学期', '2026-09-01', '2027-01-31')).toBe('');
    expect(academicSemesterName(new Date('2026-09-21T00:00:00+08:00'))).toBe('2026-2027学年上学期');
  });
});
