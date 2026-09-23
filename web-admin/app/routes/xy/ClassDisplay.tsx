import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { Navigate, useNavigate, useParams } from 'react-router';
import { useAuth } from '~/contexts/AuthContext';
import { useI18n } from '~/contexts/I18nContext';
import { xyExec } from './eduApi';
import { createRefreshController } from './asyncControl';

interface ScoreRecord {
  rule: string;
  score: number;
  occurredAt: string;
}
interface DisplayStudent {
  pid: string;
  name: string;
  xp: number;
  level: number;
  stage: string;
  weekScore: number;
  nickname: string;
  species: string;
  asset: string;
  records: ScoreRecord[];
}
interface DisplaySnapshot {
  classPid: string;
  className: string;
  slogan: string;
  themePid: string;
  treeStages: string;
  semesterXp: number;
  weekStart: string;
  classes: Array<{ pid: string; name: string }>;
  students: DisplayStudent[];
}
interface TreeStage {
  name: string;
  minXp: number;
  asset: string;
}

const number = (value: number) => new Intl.NumberFormat('zh-CN').format(value);
const dateTime = (raw: string) => raw.slice(0, 16).replace('T', ' ');

/** Read-only 16:9 classroom projection; the plugin command owns scope and public-data filtering. */
export default function ClassDisplay() {
  const { isAuthenticated } = useAuth();
  const { t } = useI18n();
  const { classPid } = useParams();
  const navigate = useNavigate();
  const [snapshot, setSnapshot] = useState<DisplaySnapshot | null>(null);
  const [stages, setStages] = useState<TreeStage[]>([]);
  const [error, setError] = useState('');
  const [selectedPid, setSelectedPid] = useState('');
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(8);
  const compact = pageSize === 6;
  const [imageBroken, setImageBroken] = useState(false);
  const copy = useCallback(
    (key: string, fallback: string) => t(`xy.display.${key}`, undefined, fallback),
    [t],
  );

  const load = useCallback(async () => {
    const result = await xyExec('xy:class_display_snapshot', classPid ? { classPid } : {});
    if (!result.ok) {
      setError(result.message || '班级大屏暂时无法加载');
      return;
    }
    const data = result.data as unknown as DisplaySnapshot;
    if (!data.classPid) {
      setSnapshot(null);
      setStages([]);
      setError('暂无可展示的班级');
      return;
    }
    setSnapshot(data);
    setError('');
    if (!data.themePid) {
      setStages([]);
      return;
    }
    try {
      const parsed = JSON.parse(String(data.treeStages || '[]')) as TreeStage[];
      if (
        !Array.isArray(parsed) ||
        parsed.length !== 5 ||
        !parsed.every((stage) => stage.name && stage.asset && Number.isFinite(Number(stage.minXp)))
      )
        throw new Error('bad stages');
      setStages([...parsed].sort((a, b) => Number(a.minXp) - Number(b.minXp)));
    } catch {
      setStages([]);
      setError('班级大树的阶段配置有误');
    }
  }, [classPid]);

  useEffect(() => {
    if (!isAuthenticated) return;
    const refresh = createRefreshController(load);
    void refresh.request();
    const timer = window.setInterval(() => {
      if (document.visibilityState === 'visible') void refresh.request();
    }, 30000);
    const es = new EventSource('/api/notifications/stream', { withCredentials: true });
    es.addEventListener('data-sync-connected', (event) => {
      try {
        const { connectionId } = JSON.parse(String((event as MessageEvent).data)) as {
          connectionId: number;
        };
        void fetch('/api/data-sync/subscribe', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          credentials: 'include',
          body: JSON.stringify({
            connectionId,
            modelCodes: [
              'xy_classroom',
              'xy_student',
              'xy_enrollment',
              'xy_pet_instance',
              'xy_evaluation',
              'xy_evaluation_line',
              'xy_play_theme',
            ],
          }),
        });
      } catch {
        /* the polling safety net remains active */
      }
    });
    es.addEventListener('data:changed', () => refresh.schedule(750));
    const onVisibilityChange = () => {
      if (document.visibilityState === 'visible') void refresh.request();
    };
    document.addEventListener('visibilitychange', onVisibilityChange);
    return () => {
      refresh.dispose();
      window.clearInterval(timer);
      document.removeEventListener('visibilitychange', onVisibilityChange);
      es.close();
    };
  }, [isAuthenticated, load]);

  const students = snapshot?.students ?? [];
  const selected = students.find((student) => student.pid === selectedPid) ?? null;
  const totalPages = Math.max(1, Math.ceil(students.length / pageSize));
  const visibleStudents = students.slice(page * pageSize, (page + 1) * pageSize);
  const tree = useMemo(() => {
    const xp = snapshot?.semesterXp ?? 0;
    const current = [...stages].reverse().find((stage) => xp >= Number(stage.minXp)) ?? stages[0];
    const next = stages.find((stage) => Number(stage.minXp) > xp);
    return {
      current,
      next,
      progress:
        current && next
          ? Math.max(
              0,
              Math.min(
                100,
                ((xp - Number(current.minXp)) / (Number(next.minXp) - Number(current.minXp))) * 100,
              ),
            )
          : 100,
    };
  }, [snapshot?.semesterXp, stages]);

  useEffect(() => {
    setImageBroken(false);
  }, [tree.current?.asset]);
  useEffect(() => {
    const fitProjection = () => setPageSize(window.innerHeight < 900 ? 6 : 8);
    fitProjection();
    window.addEventListener('resize', fitProjection);
    return () => window.removeEventListener('resize', fitProjection);
  }, []);
  useEffect(() => {
    setPage(0);
    setSelectedPid('');
  }, [snapshot?.classPid]);
  useEffect(() => {
    if (page >= totalPages) setPage(totalPages - 1);
  }, [page, totalPages]);

  if (!isAuthenticated) return <Navigate to="/login" replace />;
  return (
    <main
      className="min-h-screen px-5 py-5 text-[#213D32] sm:px-8 lg:px-10"
      style={{
        background: 'radial-gradient(circle at 8% 8%, #FDFCEB 0%, #F1F6E7 39%, #E8F0E3 100%)',
      }}
      data-testid="class-display"
    >
      <div className="mx-auto max-w-[1900px]">
        <header className="flex flex-wrap items-center justify-between gap-4 border-b border-[#DCE8D4] pb-4">
          <div className="flex items-center gap-4">
            <span
              className="grid h-12 w-12 place-items-center rounded-2xl bg-[#E8B94A] text-2xl shadow-sm"
              aria-hidden="true"
            >
              🐝
            </span>
            <div>
              <p className="text-xs font-bold tracking-[0.2em] text-[#698569]">
                {copy('eyebrow', '蜂耘 · 班级成长')}
              </p>
              <h1
                className="text-2xl font-black tracking-tight sm:text-3xl"
                data-testid="display-title"
              >
                {snapshot?.className || copy('title', '班级大屏')}
              </h1>
            </div>
          </div>
          <div className="flex flex-wrap items-center gap-3">
            {snapshot && snapshot.classes?.length > 1 && (
              <label className="flex items-center gap-2 text-sm font-semibold">
                {copy('chooseClass', '展示班级')}
                <select
                  className="rounded-xl border border-[#D9E5D5] bg-white px-3 py-2"
                  value={snapshot.classPid}
                  onChange={(event) => navigate(`/xy/display/${event.target.value}`)}
                >
                  {snapshot.classes.map((cls) => (
                    <option key={cls.pid} value={cls.pid}>
                      {cls.name}
                    </option>
                  ))}
                </select>
              </label>
            )}
            <span className="rounded-full bg-white/80 px-4 py-2 text-sm font-semibold text-[#547157]">
              {copy('weekRank', '本周积分榜')}
            </span>
            <button
              type="button"
              className="rounded-full border border-[#D9E5D5] bg-white px-4 py-2 text-sm font-semibold"
              onClick={() => navigate('/')}
            >
              {copy('exit', '退出大屏')}
            </button>
          </div>
        </header>

        {error && (
          <div
            role="alert"
            className="mt-4 rounded-xl border border-[#E7BBB1] bg-[#FFF6F3] px-4 py-3 text-sm text-[#914B3F]"
          >
            {error}
          </div>
        )}
        {!snapshot && !error && (
          <div className="mt-12 text-center text-lg text-[#6B806C]">
            {copy('loading', '正在加载班级成长…')}
          </div>
        )}
        {snapshot && (
          <>
            <div className="mt-5 grid gap-5 xl:grid-cols-[minmax(0,1.1fr)_minmax(0,0.9fr)]">
              <section
                className={`relative overflow-hidden rounded-[32px] border border-[#DFE9D6] bg-white/85 shadow-[0_18px_60px_-40px_#416747] ${compact ? 'min-h-[300px] p-4' : 'min-h-[340px] p-5 sm:p-6'}`}
                data-testid="display-tree"
              >
                <div
                  className="absolute -top-20 -right-16 h-64 w-64 rounded-full bg-[#F7E6A0]/40"
                  aria-hidden="true"
                />
                <p className="relative text-xs font-bold tracking-[0.2em] text-[#6B896A]">
                  {copy('treeLabel', '班级大树 · 集体成长')}
                </p>
                <div className="relative flex h-full flex-col items-center gap-5 sm:flex-row">
                  <div
                    className={`grid shrink-0 place-items-center ${compact ? 'h-[210px] w-[210px]' : 'h-[230px] w-[230px] sm:h-[260px] sm:w-[260px]'}`}
                    data-testid="display-tree-artwork"
                  >
                    {tree.current?.asset && !imageBroken ? (
                      <img
                        src={tree.current.asset}
                        alt={`${tree.current.name}班级大树`}
                        className="h-full w-full object-contain drop-shadow-[0_18px_14px_rgba(76,103,52,0.14)]"
                        onError={() => setImageBroken(true)}
                      />
                    ) : (
                      <span className="text-8xl" role="img" aria-label="班级大树">
                        🌱
                      </span>
                    )}
                  </div>
                  <div className="min-w-0 flex-1 self-center">
                    <p className="text-sm font-semibold text-[#789176]">
                      {copy('semesterNectar', '本学期全班花蜜')}
                    </p>
                    <p
                      className="mt-1 text-5xl font-black text-[#2B604A] tabular-nums sm:text-6xl"
                      data-testid="display-tree-total"
                    >
                      {number(snapshot.semesterXp)}
                    </p>
                    <h2 className="mt-4 text-2xl font-bold" data-testid="display-tree-stage">
                      {tree.current?.name || copy('stagePending', '成长阶段待配置')}
                    </h2>
                    {tree.next ? (
                      <>
                        <div
                          className="mt-4 h-3 overflow-hidden rounded-full bg-[#E4EED8]"
                          role="progressbar"
                          aria-valuenow={Math.round(tree.progress)}
                          aria-valuemin={0}
                          aria-valuemax={100}
                        >
                          <div
                            className="h-full rounded-full bg-[#8DB16C] transition-all duration-700"
                            style={{ width: `${tree.progress}%` }}
                          />
                        </div>
                        <p className="mt-2 text-sm font-medium text-[#658064]">
                          {copy('nextStage', '距离下一阶段')}「{tree.next.name}」
                          {copy('remaining', '还差')}{' '}
                          {number(Number(tree.next.minXp) - snapshot.semesterXp)}{' '}
                          {copy('nectar', '花蜜')}
                        </p>
                      </>
                    ) : (
                      <p className="mt-3 text-sm text-[#658064]">
                        {copy('maxStage', '已到当前最高阶段，继续一起成长。')}
                      </p>
                    )}
                  </div>
                </div>
              </section>

              <section
                className={`rounded-[32px] border border-[#DFE9D6] bg-white/85 shadow-[0_18px_60px_-40px_#416747] ${compact ? 'p-4' : 'p-5 sm:p-6'}`}
                data-testid="display-ranking"
              >
                <div className="flex items-end justify-between gap-2">
                  <div>
                    <p className="text-xs font-bold tracking-[0.2em] text-[#6B896A]">
                      {copy('weekly', '本周成长')}
                    </p>
                    <h2 className="mt-1 text-2xl font-black">{copy('rankTitle', '积分排行榜')}</h2>
                  </div>
                  <span className="text-xs text-[#789176]">
                    {snapshot.weekStart} {copy('since', '起 · 净积分')}
                  </span>
                </div>
                <div className={compact ? 'mt-2 space-y-0' : 'mt-3 space-y-1'}>
                  {students.slice(0, 5).map((student, index) => (
                    <button
                      type="button"
                      key={student.pid}
                      className={`flex w-full items-center gap-3 rounded-2xl px-3 text-left transition hover:bg-[#EFF6E9] focus-visible:outline-2 focus-visible:outline-[#35745B] ${compact ? 'py-0' : 'py-1'}`}
                      onClick={() => setSelectedPid(student.pid)}
                      data-testid={`display-rank-${index + 1}`}
                    >
                      <span
                        className={`grid h-9 w-9 shrink-0 place-items-center rounded-xl text-base font-black ${index === 0 ? 'bg-[#FFE4A0] text-[#815D15]' : index === 1 ? 'bg-[#E8ECDF] text-[#5B6B58]' : index === 2 ? 'bg-[#F1E0CE] text-[#8C6746]' : 'bg-[#F0F5E9] text-[#688466]'}`}
                      >
                        {index + 1}
                      </span>
                      <span className="min-w-0 flex-1">
                        <span className="block truncate text-base font-bold">{student.name}</span>
                        <span className="block truncate text-xs text-[#718673]">
                          {student.nickname || student.species || copy('unclaimed', '伙伴待认领')}
                        </span>
                      </span>
                      <strong className="text-xl text-[#2F7053] tabular-nums">
                        {student.weekScore > 0 ? '+' : ''}
                        {student.weekScore}
                      </strong>
                    </button>
                  ))}
                  {students.length === 0 && (
                    <p className="rounded-2xl bg-[#F4F8EE] p-5 text-sm text-[#718673]">
                      {copy('emptyRoster', '班级尚无同学，请先导入班级名册。')}
                    </p>
                  )}
                </div>
                <p className={`${compact ? 'mt-1' : 'mt-3'} text-xs text-[#81957E]`}>
                  {copy('rankingHint', '按本周有效评价的净积分排序；点击同学可看公开评分记录。')}
                </p>
              </section>
            </div>

            <section
              className="mt-4 rounded-[32px] border border-[#DFE9D6] bg-white/75 p-5 sm:p-6"
              data-testid="display-students"
            >
              <div className="flex flex-wrap items-end justify-between gap-3">
                <div>
                  <p className="text-xs font-bold tracking-[0.2em] text-[#6B896A]">
                    {copy('companions', '伙伴图鉴')}
                  </p>
                  <h2 className="mt-1 text-2xl font-black">
                    {copy('classmates', '我们班的同学与蜜蜂')}
                  </h2>
                </div>
                <div className="flex items-center gap-2 text-sm text-[#718673]">
                  <span>
                    {students.length} {copy('students', '位同学')}
                  </span>
                  {totalPages > 1 && (
                    <>
                      <button
                        type="button"
                        disabled={page === 0}
                        onClick={() => setPage(page - 1)}
                        className="rounded-lg border border-[#D9E5D5] bg-white px-3 py-1.5 disabled:opacity-40"
                      >
                        {copy('previous', '上一页')}
                      </button>
                      <span>
                        {page + 1}/{totalPages}
                      </span>
                      <button
                        type="button"
                        disabled={page + 1 >= totalPages}
                        onClick={() => setPage(page + 1)}
                        className="rounded-lg border border-[#D9E5D5] bg-white px-3 py-1.5 disabled:opacity-40"
                      >
                        {copy('next', '下一页')}
                      </button>
                    </>
                  )}
                </div>
              </div>
              <div
                className="mt-4 grid grid-cols-2 gap-3 sm:grid-cols-3 xl:grid-cols-4"
                data-testid="display-pets"
              >
                {visibleStudents.map((student) => (
                  <StudentCard
                    key={student.pid}
                    student={student}
                    onClick={() => setSelectedPid(student.pid)}
                  />
                ))}
              </div>
              {students.length === 0 && (
                <p className="mt-5 rounded-2xl bg-[#F4F8EE] p-6 text-center text-[#718673]">
                  {copy('emptyRoster', '班级尚无同学，请先导入班级名册。')}
                </p>
              )}
            </section>
          </>
        )}
      </div>
      {selected && (
        <StudentDetails student={selected} close={() => setSelectedPid('')} copy={copy} />
      )}
    </main>
  );
}

function StudentCard({ student, onClick }: { student: DisplayStudent; onClick: () => void }) {
  const [broken, setBroken] = useState(false);
  useEffect(() => setBroken(false), [student.asset]);
  return (
    <button
      type="button"
      onClick={onClick}
      className="min-w-0 rounded-2xl border border-[#E0EAD9] bg-[#FAFCF7] p-3 text-left transition hover:-translate-y-0.5 hover:border-[#9CBF93] hover:shadow-md focus-visible:outline-2 focus-visible:outline-[#35745B]"
      data-testid={`display-student-${student.pid}`}
    >
      <div className="mx-auto grid h-40 w-40 place-items-center sm:h-44 sm:w-44">
        {student.asset && !broken ? (
          <img
            src={student.asset}
            alt={`${student.name}的${student.species}`}
            className={
              student.asset.endsWith('.png')
                ? 'h-full w-full scale-[1.2] object-contain'
                : 'h-full w-full object-contain'
            }
            onError={() => setBroken(true)}
          />
        ) : (
          <span className="text-6xl" role="img" aria-label="待认领伙伴">
            🐝
          </span>
        )}
      </div>
      <strong className="mt-1 block truncate text-base">{student.name}</strong>
      <span className="block truncate text-xs text-[#658064]">
        {student.species
          ? `${student.nickname || '等待起昵称'} · ${student.species}`
          : '伙伴待认领'}
      </span>
      <div className="mt-2 flex items-center justify-between gap-1 text-xs">
        <span className="rounded-full bg-[#E9F2DE] px-2 py-0.5 font-bold text-[#47704D]">
          Lv.{student.level || 1}
        </span>
        <span className="font-semibold text-[#6F876A] tabular-nums">{student.xp} 花蜜</span>
      </div>
    </button>
  );
}

function StudentDetails({
  student,
  close,
  copy,
}: {
  student: DisplayStudent;
  close: () => void;
  copy: (key: string, fallback: string) => string;
}) {
  const [broken, setBroken] = useState(false);
  useEffect(() => setBroken(false), [student.asset]);
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') close();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [close]);
  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-[#152D25]/65 p-4"
      role="presentation"
      onMouseDown={(event) => {
        if (event.target === event.currentTarget) close();
      }}
    >
      <section
        role="dialog"
        aria-modal="true"
        aria-labelledby="display-detail-title"
        className="max-h-[90vh] w-full max-w-3xl overflow-auto rounded-[30px] bg-[#FAFCF7] p-6 shadow-2xl sm:p-8"
        data-testid="display-student-detail"
      >
        <div className="flex items-start justify-between gap-4">
          <div>
            <p className="text-xs font-bold tracking-[0.2em] text-[#6B896A]">
              {copy('studentGrowth', '同学成长档案')}
            </p>
            <h2 id="display-detail-title" className="mt-1 text-3xl font-black">
              {student.name}
            </h2>
            <p className="mt-1 text-sm text-[#718673]">
              {student.nickname || copy('unclaimed', '伙伴待认领')} · {student.species}
            </p>
          </div>
          <button
            type="button"
            onClick={close}
            className="rounded-full border border-[#D9E5D5] bg-white px-4 py-2 text-sm font-semibold"
            aria-label={copy('close', '关闭详情')}
          >
            {copy('close', '关闭')}
          </button>
        </div>
        <div className="mt-5 flex flex-wrap items-center gap-6 rounded-2xl bg-[#EEF5E9] p-4">
          <div className="grid h-48 w-48 place-items-center sm:h-56 sm:w-56">
            {student.asset && !broken ? (
              <img
                src={student.asset}
                alt={`${student.species} ${student.stage}`}
                className={
                  student.asset.endsWith('.png')
                    ? 'h-full w-full scale-[1.2] object-contain'
                    : 'h-full w-full object-contain'
                }
                onError={() => setBroken(true)}
              />
            ) : (
              <span className="text-7xl">🐝</span>
            )}
          </div>
          <div className="flex flex-1 flex-wrap gap-6">
            <div>
              <span className="block text-xs text-[#718673]">{copy('level', '成长等级')}</span>
              <strong className="text-3xl">Lv.{student.level || 1}</strong>
            </div>
            <div>
              <span className="block text-xs text-[#718673]">{copy('nectar', '花蜜')}</span>
              <strong className="text-3xl">{number(student.xp)}</strong>
            </div>
            <div>
              <span className="block text-xs text-[#718673]">
                {copy('weeklyScore', '本周净积分')}
              </span>
              <strong className="text-3xl">
                {student.weekScore > 0 ? '+' : ''}
                {student.weekScore}
              </strong>
            </div>
          </div>
        </div>
        <h3 className="mt-6 text-lg font-bold">{copy('publicRecords', '公开评分记录')}</h3>
        <p className="mt-1 text-xs text-[#718673]">
          {copy('recordPrivacy', '这里只显示公开记录；私密评价和扣分原因请老师在管理端查看。')}
        </p>
        <div className="mt-3 divide-y divide-[#E2EBD9]">
          {student.records.map((record, index) => (
            <div
              key={`${record.occurredAt}-${index}`}
              className="flex items-center gap-3 py-3 text-sm"
            >
              <span className="min-w-0 flex-1 font-semibold">{record.rule}</span>
              <span className="text-[#718673]">{dateTime(record.occurredAt)}</span>
              <strong className={record.score >= 0 ? 'text-[#35745B]' : 'text-[#995F56]'}>
                {record.score > 0 ? '+' : ''}
                {record.score}
              </strong>
            </div>
          ))}
          {student.records.length === 0 && (
            <p className="py-5 text-sm text-[#718673]">
              {copy('noPublicRecords', '暂无公开评分记录')}
            </p>
          )}
        </div>
      </section>
    </div>
  );
}
