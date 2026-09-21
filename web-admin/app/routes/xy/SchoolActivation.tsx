import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { Link, Navigate, useNavigate } from 'react-router';
import {
  ArrowLeftIcon,
  ArrowPathIcon,
  CheckCircleIcon,
  ClipboardDocumentIcon,
  ExclamationTriangleIcon,
  UserGroupIcon,
} from '@heroicons/react/24/outline';
import { useAuth } from '~/contexts/AuthContext';
import { xyExec, xyList, type XyRow } from './eduApi';

interface TeacherCode {
  code: string;
  expiredAt?: string;
}

interface SetupState {
  semester: XyRow | null;
  classes: XyRow[];
  enrollments: XyRow[];
  teacherCode: TeacherCode | null;
}

const emptyState: SetupState = { semester: null, classes: [], enrollments: [], teacherCode: null };

export function semesterDateError(name: string, startDate: string, endDate: string): string {
  if (!name.trim()) return '请填写学期名称';
  if (startDate && endDate && endDate < startDate) return '结束日期不能早于开始日期';
  return '';
}

export function academicSemesterName(date = new Date()): string {
  const year = date.getFullYear();
  const month = date.getMonth() + 1;
  if (month >= 8) return `${year}-${year + 1}学年上学期`;
  return `${year - 1}-${year}学年下学期`;
}

async function loadTeacherCode(): Promise<TeacherCode | null> {
  const response = await fetch('/_action/xy/teacher-code');
  const body = await response.json();
  if (!response.ok || !body.success) throw new Error(body.error || '学校教师码加载失败');
  return body.data || null;
}

export default function SchoolActivation() {
  const { isAuthenticated, hasPermission, hasRole } = useAuth();
  const navigate = useNavigate();
  const schoolAdmin = hasRole('xy_school_admin') || hasPermission('xy.school.manage');
  const [state, setState] = useState<SetupState>(emptyState);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [semesterOpen, setSemesterOpen] = useState(false);
  const [semesterName, setSemesterName] = useState(() => academicSemesterName());
  const [startDate, setStartDate] = useState('');
  const [endDate, setEndDate] = useState('');
  const [semesterConfirmed, setSemesterConfirmed] = useState(false);
  const [savingSemester, setSavingSemester] = useState(false);
  const [savingCode, setSavingCode] = useState(false);
  const [resetOpen, setResetOpen] = useState(false);
  const [selectedClass, setSelectedClass] = useState('');

  const load = useCallback(async () => {
    if (!schoolAdmin) return;
    setLoading(true);
    setError('');
    try {
      const [semesters, classes, enrollments, teacherCode] = await Promise.all([
        xyList('xy_semester', [{ field: 'xy_sem_status', value: 'active' }]),
        xyList('xy_classroom', [{ field: 'xy_cls_status', value: 'active' }]),
        xyList('xy_enrollment', [{ field: 'xy_enr_status', value: 'active' }]),
        loadTeacherCode(),
      ]);
      setState({ semester: semesters[0] || null, classes, enrollments, teacherCode });
      setSelectedClass((current) => current || (classes[0]?.pid ? String(classes[0].pid) : ''));
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '启用状态加载失败');
    } finally {
      setLoading(false);
    }
  }, [schoolAdmin]);

  useEffect(() => {
    void load();
  }, [load]);

  const requiredSteps = useMemo(
    () => [Boolean(state.semester), state.classes.length > 0, state.enrollments.length > 0],
    [state],
  );
  const completed = requiredSteps.filter(Boolean).length;

  if (!isAuthenticated) return <Navigate to="/login" replace />;

  const openSemester = async () => {
    const validation = semesterDateError(semesterName, startDate, endDate);
    if (validation) {
      setError(validation);
      return;
    }
    if (!semesterConfirmed) {
      setError('请先确认学期切换影响');
      return;
    }
    setSavingSemester(true);
    setError('');
    setNotice('');
    const result = await xyExec('xy:semester_open', {
      name: semesterName.trim(),
      ...(startDate ? { start_date: startDate } : {}),
      ...(endDate ? { end_date: endDate } : {}),
    });
    setSavingSemester(false);
    if (!result.ok) {
      setError(result.message || '开启学期失败');
      return;
    }
    setSemesterOpen(false);
    setSemesterConfirmed(false);
    setNotice('新学期已开启');
    await load();
  };

  const updateTeacherCode = async (action: 'generate' | 'reset') => {
    setSavingCode(true);
    setError('');
    setNotice('');
    try {
      const response = await fetch('/_action/xy/teacher-code', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ action }),
      });
      const body = await response.json();
      if (!response.ok || !body.success) throw new Error(body.error || '学校教师码操作失败');
      setState((current) => ({ ...current, teacherCode: body.data || null }));
      setResetOpen(false);
      setNotice(action === 'reset' ? '学校教师码已重置，旧码已失效' : '学校教师码已生成');
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '学校教师码操作失败');
    } finally {
      setSavingCode(false);
    }
  };

  const copyTeacherCode = async () => {
    if (!state.teacherCode?.code) return;
    try {
      await navigator.clipboard.writeText(state.teacherCode.code);
      setNotice('学校教师码已复制');
    } catch {
      setError('复制失败，请手动选择教师码');
    }
  };

  if (!schoolAdmin) {
    return (
      <main
        className="min-h-screen bg-slate-50 px-6 py-16"
        data-testid="school-activation-no-access"
      >
        <div className="mx-auto max-w-xl rounded-2xl border border-amber-200 bg-white p-8 text-center shadow-sm">
          <ExclamationTriangleIcon className="mx-auto h-12 w-12 text-amber-500" />
          <h1 className="mt-5 text-2xl font-semibold text-slate-950">此页面仅供学校管理员使用</h1>
          <p className="mt-3 text-sm leading-6 text-slate-600">
            班主任和任课老师请在老师工作台或微信小程序完成日常操作。
          </p>
          <button
            className="mt-7 rounded-lg bg-emerald-700 px-5 py-2.5 text-sm font-semibold text-white"
            onClick={() => navigate('/')}
          >
            返回工作台
          </button>
        </div>
      </main>
    );
  }

  const stepCard = (
    step: number,
    done: boolean,
    title: string,
    description: string,
    action: React.ReactNode,
  ) => (
    <section className="rounded-2xl border border-slate-200 bg-white p-6 shadow-sm">
      <div className="flex items-start gap-4">
        <div
          className={`mt-0.5 flex h-9 w-9 shrink-0 items-center justify-center rounded-full ${done ? 'bg-emerald-100 text-emerald-700' : 'bg-amber-100 text-amber-700'}`}
        >
          {done ? (
            <CheckCircleIcon className="h-6 w-6" />
          ) : (
            <span className="text-sm font-bold">{step}</span>
          )}
        </div>
        <div className="min-w-0 flex-1">
          <h2 className="text-lg font-semibold text-slate-950">{title}</h2>
          <p className="mt-1 text-sm leading-6 text-slate-600">{description}</p>
          <div className="mt-4">{action}</div>
        </div>
      </div>
    </section>
  );

  return (
    <main className="min-h-screen bg-slate-50" data-testid="school-activation">
      <div className="border-b border-emerald-900/10 bg-emerald-950 px-6 py-4 text-white">
        <div className="mx-auto flex max-w-6xl items-center justify-between">
          <div>
            <span className="font-semibold">蜂耘</span>
            <span className="ml-2 text-sm text-emerald-100">学校启用中心</span>
          </div>
          <Link
            to="/"
            className="inline-flex items-center gap-2 text-sm font-medium text-emerald-50"
          >
            <ArrowLeftIcon className="h-4 w-4" />
            返回工作台
          </Link>
        </div>
      </div>

      <div className="mx-auto max-w-6xl px-6 py-10">
        <div className="grid gap-8 lg:grid-cols-[minmax(0,1fr)_360px]">
          <div>
            <p className="text-sm font-semibold text-emerald-700">开学准备</p>
            <h1 className="mt-2 text-3xl font-semibold tracking-tight text-slate-950">
              让学校可以开始评分
            </h1>
            <p className="mt-3 max-w-2xl text-sm leading-6 text-slate-600">
              按顺序完成学期、班级和名册。学校教师码可随时生成，老师在微信小程序入校后再绑定班级。
            </p>

            <div className="mt-7 rounded-2xl bg-emerald-900 p-6 text-white">
              <div className="flex items-end justify-between gap-4">
                <div>
                  <div className="text-sm text-emerald-100">必做进度</div>
                  <div className="mt-1 text-3xl font-semibold" data-testid="setup-progress">
                    {completed}/3
                  </div>
                </div>
                <div className="text-right text-sm text-emerald-100">
                  {completed === 3 ? '已具备基础评分条件' : `还差 ${3 - completed} 步`}
                </div>
              </div>
              <div className="mt-4 h-2 overflow-hidden rounded-full bg-white/15">
                <div
                  className="h-full rounded-full bg-lime-300 transition-all"
                  style={{ width: `${(completed / 3) * 100}%` }}
                />
              </div>
            </div>

            {error && (
              <div
                className="mt-5 rounded-xl border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700"
                role="alert"
              >
                {error}
              </div>
            )}
            {notice && (
              <div
                className="mt-5 rounded-xl border border-emerald-200 bg-emerald-50 px-4 py-3 text-sm text-emerald-800"
                role="status"
              >
                {notice}
              </div>
            )}

            {loading ? (
              <div className="mt-6 rounded-2xl border border-slate-200 bg-white p-10 text-center text-sm text-slate-500">
                正在核对学校启用状态…
              </div>
            ) : (
              <div className="mt-6 space-y-4">
                {stepCard(
                  1,
                  Boolean(state.semester),
                  '1. 开启当前学期',
                  state.semester
                    ? `当前：${String(state.semester.xy_sem_name || '')}`
                    : '学期是评分和成长数据的时间边界。首次启用前必须开启。',
                  <button
                    data-testid="open-semester"
                    className="rounded-lg border border-emerald-700 px-4 py-2 text-sm font-semibold text-emerald-800"
                    onClick={() => setSemesterOpen(true)}
                  >
                    {state.semester ? '切换新学期' : '开启新学期'}
                  </button>,
                )}

                {semesterOpen && (
                  <section
                    className="rounded-2xl border-2 border-amber-300 bg-amber-50 p-6"
                    data-testid="semester-confirm-panel"
                  >
                    <h2 className="text-lg font-semibold text-slate-950">确认开启新学期</h2>
                    <p className="mt-2 text-sm leading-6 text-slate-700">
                      提交后，当前学期会关闭并成为只读历史；学生蜂蜜余额继续保留，当期花蜜从新学期重新累计。此操作不可直接撤销。
                    </p>
                    <div className="mt-5 grid gap-4 sm:grid-cols-2">
                      <label className="text-sm font-medium text-slate-700 sm:col-span-2">
                        学期名称
                        <input
                          aria-label="学期名称"
                          className="mt-1 w-full rounded-lg border border-slate-300 bg-white px-3 py-2.5"
                          value={semesterName}
                          onChange={(event) => setSemesterName(event.target.value)}
                        />
                      </label>
                      <label className="text-sm font-medium text-slate-700">
                        开始日期
                        <input
                          aria-label="开始日期"
                          type="date"
                          className="mt-1 w-full rounded-lg border border-slate-300 bg-white px-3 py-2.5"
                          value={startDate}
                          onChange={(event) => setStartDate(event.target.value)}
                        />
                      </label>
                      <label className="text-sm font-medium text-slate-700">
                        结束日期
                        <input
                          aria-label="结束日期"
                          type="date"
                          className="mt-1 w-full rounded-lg border border-slate-300 bg-white px-3 py-2.5"
                          value={endDate}
                          onChange={(event) => setEndDate(event.target.value)}
                        />
                      </label>
                    </div>
                    <label className="mt-5 flex items-start gap-3 text-sm text-slate-700">
                      <input
                        type="checkbox"
                        className="mt-1"
                        checked={semesterConfirmed}
                        onChange={(event) => setSemesterConfirmed(event.target.checked)}
                      />
                      我已了解切换影响，并确认现在开启新学期
                    </label>
                    <div className="mt-5 flex gap-3">
                      <button
                        className="rounded-lg px-4 py-2 text-sm font-medium text-slate-700"
                        onClick={() => setSemesterOpen(false)}
                      >
                        取消
                      </button>
                      <button
                        data-testid="confirm-open-semester"
                        className="rounded-lg bg-amber-700 px-4 py-2 text-sm font-semibold text-white disabled:opacity-50"
                        disabled={savingSemester || !semesterConfirmed}
                        onClick={() => void openSemester()}
                      >
                        {savingSemester ? '正在开启…' : '确认开启'}
                      </button>
                    </div>
                  </section>
                )}

                {stepCard(
                  2,
                  state.classes.length > 0,
                  '2. 创建班级并选择玩法',
                  state.classes.length > 0
                    ? `已有 ${state.classes.length} 个启用班级。玩法在建班时选定，创建后锁定。`
                    : '创建第一个班级时选择玩法包，决定成长周期、货币名称和班级树。',
                  <Link
                    className="inline-flex rounded-lg bg-emerald-700 px-4 py-2 text-sm font-semibold text-white"
                    to="/p/xy_classroom/new"
                  >
                    {state.classes.length ? '继续管理班级' : '创建第一个班级'}
                  </Link>,
                )}

                {stepCard(
                  3,
                  state.enrollments.length > 0,
                  '3. 导入学生名册',
                  state.enrollments.length > 0
                    ? `已有 ${state.enrollments.length} 条在读班级关系。`
                    : '在电脑端上传 .xlsx 或 UTF-8 .csv，先预览问题行，再确认写入。',
                  state.classes.length ? (
                    <div className="flex flex-wrap gap-3">
                      <select
                        aria-label="导入班级"
                        className="rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm"
                        value={selectedClass}
                        onChange={(event) => setSelectedClass(event.target.value)}
                      >
                        {state.classes.map((item) => (
                          <option key={String(item.pid)} value={String(item.pid)}>
                            {String(item.xy_cls_alias || item.xy_cls_name)}
                          </option>
                        ))}
                      </select>
                      <button
                        className="rounded-lg bg-emerald-700 px-4 py-2 text-sm font-semibold text-white"
                        onClick={() => navigate(`/xy/import/${selectedClass}`)}
                      >
                        导入这个班级的名册
                      </button>
                    </div>
                  ) : (
                    <span className="text-sm text-slate-500">请先创建班级</span>
                  ),
                )}
              </div>
            )}
          </div>

          <aside className="space-y-5">
            <section
              className="rounded-2xl border border-emerald-200 bg-white p-6 shadow-sm"
              data-testid="teacher-code-card"
            >
              <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-emerald-100 text-emerald-700">
                <UserGroupIcon className="h-6 w-6" />
              </div>
              <h2 className="mt-4 text-xl font-semibold text-slate-950">学校教师码</h2>
              <p className="mt-2 text-sm leading-6 text-slate-600">
                全校老师使用同一个码在微信小程序入校，入校角色固定为“老师”。之后再选择科目并绑定班级。
              </p>
              {state.teacherCode ? (
                <div className="mt-5 rounded-xl bg-emerald-50 p-4">
                  <div className="text-xs font-medium text-emerald-700">当前学校教师码</div>
                  <div
                    className="mt-1 font-mono text-2xl font-bold tracking-wider break-all text-emerald-950"
                    data-testid="teacher-code"
                  >
                    {state.teacherCode.code}
                  </div>
                  {state.teacherCode.expiredAt && (
                    <div className="mt-2 text-xs text-emerald-700">
                      有效期至 {String(state.teacherCode.expiredAt).slice(0, 10)}
                    </div>
                  )}
                </div>
              ) : (
                <div className="mt-5 rounded-xl bg-slate-50 p-4 text-sm text-slate-600">
                  还没有有效的学校教师码。
                </div>
              )}
              <div className="mt-4 grid gap-2">
                {state.teacherCode ? (
                  <>
                    <button
                      className="inline-flex items-center justify-center gap-2 rounded-lg bg-emerald-700 px-4 py-2.5 text-sm font-semibold text-white"
                      onClick={() => void copyTeacherCode()}
                    >
                      <ClipboardDocumentIcon className="h-4 w-4" />
                      复制学校教师码
                    </button>
                    <button
                      className="inline-flex items-center justify-center gap-2 rounded-lg border border-slate-300 px-4 py-2.5 text-sm font-medium text-slate-700"
                      onClick={() => setResetOpen(true)}
                    >
                      <ArrowPathIcon className="h-4 w-4" />
                      重置学校教师码
                    </button>
                  </>
                ) : (
                  <button
                    className="rounded-lg bg-emerald-700 px-4 py-2.5 text-sm font-semibold text-white disabled:opacity-50"
                    disabled={savingCode}
                    onClick={() => void updateTeacherCode('generate')}
                  >
                    生成学校教师码
                  </button>
                )}
              </div>
            </section>
            <section className="rounded-2xl border border-slate-200 bg-white p-6">
              <h2 className="font-semibold text-slate-950">老师入校后做什么？</h2>
              <ol className="mt-3 space-y-2 text-sm leading-6 text-slate-600">
                <li>1. 微信小程序输入学校教师码</li>
                <li>2. 选择任教学科和班级</li>
                <li>3. 班主任可认领空席位或新建班级</li>
                <li>4. 名册就绪后开始评分</li>
              </ol>
              <Link
                to="/p/xy_teacher"
                className="mt-4 inline-block text-sm font-semibold text-emerald-700"
              >
                查看教师管理 →
              </Link>
            </section>
          </aside>
        </div>
      </div>

      {resetOpen && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-slate-950/50 px-4"
          role="dialog"
          aria-modal="true"
          aria-labelledby="reset-title"
        >
          <div
            className="w-full max-w-md rounded-2xl bg-white p-6 shadow-2xl"
            data-testid="teacher-code-reset-dialog"
          >
            <h2 id="reset-title" className="text-xl font-semibold text-slate-950">
              重置学校教师码？
            </h2>
            <p className="mt-3 text-sm leading-6 text-slate-600">
              系统会生成新码并立即作废旧码。尚未入校的老师必须使用新码，已经入校的老师不受影响。
            </p>
            <div className="mt-6 flex justify-end gap-3">
              <button
                className="rounded-lg px-4 py-2 text-sm font-medium text-slate-700"
                onClick={() => setResetOpen(false)}
              >
                取消
              </button>
              <button
                className="rounded-lg bg-red-700 px-4 py-2 text-sm font-semibold text-white disabled:opacity-50"
                disabled={savingCode}
                onClick={() => void updateTeacherCode('reset')}
              >
                {savingCode ? '正在重置…' : '确认重置'}
              </button>
            </div>
          </div>
        </div>
      )}
    </main>
  );
}
