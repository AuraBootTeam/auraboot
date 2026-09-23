import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Maximize2, Sprout, Trophy, X } from 'lucide-react';
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
  studentNo: string;
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
const warmedImageUrls = new Set<string>();

function warmImageCache(urls: string[]) {
  if (typeof Image === 'undefined') return;
  for (const url of new Set(urls.filter(Boolean))) {
    if (warmedImageUrls.has(url)) continue;
    warmedImageUrls.add(url);
    const image = new Image();
    image.decoding = 'async';
    image.src = url;
    void image.decode?.().catch(() => warmedImageUrls.delete(url));
  }
}

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
  const [expandedPanel, setExpandedPanel] = useState<'tree' | 'ranking' | null>(null);
  const [compact, setCompact] = useState(false);
  const [imageBroken, setImageBroken] = useState(false);
  const forceRefreshRef = useRef(false);
  const copy = useCallback(
    (key: string, fallback: string) => t(`xy.display.${key}`, undefined, fallback),
    [t],
  );

  const load = useCallback(async () => {
    const refresh = forceRefreshRef.current;
    forceRefreshRef.current = false;
    const result = await xyExec('xy:class_display_snapshot', {
      ...(classPid ? { classPid } : {}),
      ...(refresh ? { refresh: true } : {}),
    });
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
    es.addEventListener('data:changed', () => {
      forceRefreshRef.current = true;
      refresh.schedule(750);
    });
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
  const rosterStudents = useMemo(() => {
    const collator = new Intl.Collator('zh-CN', { numeric: true, sensitivity: 'base' });
    return [...students].sort(
      (left, right) =>
        collator.compare(left.studentNo || left.name, right.studentNo || right.name) ||
        collator.compare(left.name, right.name),
    );
  }, [students]);
  const rankedStudents = useMemo(
    () =>
      [...students].sort(
        (left, right) =>
          right.weekScore - left.weekScore || left.name.localeCompare(right.name, 'zh-CN'),
      ),
    [students],
  );
  const selected = students.find((student) => student.pid === selectedPid) ?? null;
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
    warmImageCache([tree.current?.asset || '', ...students.map((student) => student.asset)]);
  }, [students, tree.current?.asset]);
  useEffect(() => {
    const fitProjection = () => setCompact(window.innerHeight < 900);
    fitProjection();
    window.addEventListener('resize', fitProjection);
    return () => window.removeEventListener('resize', fitProjection);
  }, []);
  useEffect(() => {
    setSelectedPid('');
    setExpandedPanel(null);
  }, [snapshot?.classPid]);

  if (!isAuthenticated) return <Navigate to="/login" replace />;
  return (
    <main
      className="min-h-screen px-4 py-3 text-[#213D32] sm:px-6 lg:px-8 2xl:h-screen 2xl:overflow-hidden"
      style={{
        background: 'radial-gradient(circle at 8% 8%, #FDFCEB 0%, #F1F6E7 39%, #E8F0E3 100%)',
      }}
      data-testid="class-display"
    >
      <div className="mx-auto flex min-h-[calc(100vh-24px)] max-w-[1900px] flex-col 2xl:h-[calc(100vh-24px)] 2xl:min-h-0">
        <header className="flex shrink-0 flex-wrap items-center justify-between gap-3 border-b border-[#DCE8D4] pb-3">
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
              {copy('weekRank', '本周花蜜榜')}
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
            <div className="mt-3 grid shrink-0 grid-cols-2 gap-3" data-testid="display-overview">
              <OverviewButton
                icon={<Sprout className="h-5 w-5" aria-hidden="true" />}
                eyebrow={copy('treeLabel', '班级大树 · 集体成长')}
                title={tree.current?.name || copy('stagePending', '成长阶段待配置')}
                metric={`${number(snapshot.semesterXp)} ${copy('nectar', '花蜜')}`}
                action={copy('expandGrowth', '放大查看集体成长')}
                onClick={() => setExpandedPanel('tree')}
                testId="display-tree-summary"
              />
              <OverviewButton
                icon={<Trophy className="h-5 w-5" aria-hidden="true" />}
                eyebrow={copy('weekly', '本周成长')}
                title={copy('rankTitle', '花蜜排行榜')}
                metric={
                  rankedStudents[0]
                    ? `${rankedStudents[0].name} · ${rankedStudents[0].weekScore > 0 ? '+' : ''}${rankedStudents[0].weekScore}`
                    : copy('emptyRosterShort', '暂无同学')
                }
                action={copy('expandRanking', '放大查看花蜜排行榜')}
                onClick={() => setExpandedPanel('ranking')}
                testId="display-ranking-summary"
              />
            </div>

            <section
              className="mt-3 flex min-h-0 flex-1 flex-col rounded-[28px] border border-[#DFE9D6] bg-white/80 p-3 sm:p-4 2xl:overflow-hidden"
              data-testid="display-students"
            >
              <div className="flex shrink-0 flex-wrap items-end justify-between gap-2">
                <div>
                  <p className="text-xs font-bold tracking-[0.2em] text-[#6B896A]">
                    {copy('companions', '伙伴图鉴')}
                  </p>
                  <h2 className={`${compact ? 'text-lg' : 'mt-0.5 text-xl'} font-black`}>
                    {copy('classmates', '我们班的同学与蜜蜂')}
                  </h2>
                </div>
                <p className="text-right text-sm font-semibold text-[#607760]">
                  {students.length} {copy('students', '位同学')} ·{' '}
                  {copy('allVisible', '无分页 · 可连续滚动')}
                </p>
              </div>
              <div
                className={`mt-2 grid min-h-0 flex-1 auto-rows-[184px] gap-2 2xl:auto-rows-fr ${
                  compact
                    ? 'grid-cols-3 sm:grid-cols-4 lg:grid-cols-6 xl:grid-cols-8 2xl:grid-cols-12'
                    : 'grid-cols-4 sm:grid-cols-6 lg:grid-cols-8 xl:grid-cols-10 2xl:grid-cols-12'
                }`}
                data-testid="display-pets"
              >
                {rosterStudents.map((student) => (
                  <StudentCard
                    key={student.pid}
                    student={student}
                    compact={compact}
                    nectarLabel={copy('nectar', '花蜜')}
                    nicknameFallbackLabel={copy('unclaimed', '伙伴待认领')}
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
      {expandedPanel === 'tree' && snapshot && (
        <DisplayDialog
          title={copy('treeLabel', '班级大树 · 集体成长')}
          closeLabel={copy('close', '关闭')}
          onClose={() => setExpandedPanel(null)}
          testId="display-tree-dialog"
        >
          <TreePanel
            snapshot={snapshot}
            tree={tree}
            imageBroken={imageBroken}
            setImageBroken={setImageBroken}
            copy={copy}
          />
        </DisplayDialog>
      )}
      {expandedPanel === 'ranking' && snapshot && (
        <DisplayDialog
          title={copy('rankTitle', '花蜜排行榜')}
          closeLabel={copy('close', '关闭')}
          onClose={() => setExpandedPanel(null)}
          testId="display-ranking-dialog"
        >
          <RankingPanel
            students={rankedStudents}
            weekStart={snapshot.weekStart}
            onSelect={(student) => {
              setExpandedPanel(null);
              setSelectedPid(student.pid);
            }}
            copy={copy}
          />
        </DisplayDialog>
      )}
    </main>
  );
}

function OverviewButton({
  icon,
  eyebrow,
  title,
  metric,
  action,
  onClick,
  testId,
}: {
  icon: React.ReactNode;
  eyebrow: string;
  title: string;
  metric: string;
  action: string;
  onClick: () => void;
  testId: string;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      className="group flex min-w-0 items-center gap-3 rounded-2xl border border-[#DFE9D6] bg-white/85 px-3 py-2.5 text-left shadow-[0_12px_36px_-30px_#416747] transition hover:border-[#9CBF93] hover:bg-white focus-visible:outline-2 focus-visible:outline-[#35745B]"
      aria-label={action}
      aria-haspopup="dialog"
      data-testid={testId}
    >
      <span className="grid h-10 w-10 shrink-0 place-items-center rounded-xl bg-[#EDF5E7] text-[#39705A]">
        {icon}
      </span>
      <span className="min-w-0 flex-1">
        <span className="block truncate text-[10px] font-bold tracking-[0.16em] text-[#6B896A]">
          {eyebrow}
        </span>
        <span className="mt-0.5 flex min-w-0 items-baseline gap-2">
          <strong className="truncate text-base">{title}</strong>
          <span className="truncate text-xs font-semibold text-[#6F876A] tabular-nums">
            {metric}
          </span>
        </span>
      </span>
      <Maximize2
        className="h-4 w-4 shrink-0 text-[#789176] transition group-hover:scale-110 group-hover:text-[#35745B]"
        aria-hidden="true"
      />
    </button>
  );
}

function StudentCard({
  student,
  compact,
  nectarLabel,
  nicknameFallbackLabel,
  onClick,
}: {
  student: DisplayStudent;
  compact: boolean;
  nectarLabel: string;
  nicknameFallbackLabel: string;
  onClick: () => void;
}) {
  const [broken, setBroken] = useState(false);
  useEffect(() => setBroken(false), [student.asset]);
  return (
    <button
      type="button"
      onClick={onClick}
      className="relative min-h-0 min-w-0 overflow-hidden rounded-xl border border-[#DCE8D5] bg-[radial-gradient(circle_at_45%_44%,#FFFFFF_0%,#F6FAF0_58%,#EDF4E7_100%)] text-center transition hover:-translate-y-0.5 hover:border-[#9CBF93] hover:shadow-md focus-visible:outline-2 focus-visible:outline-[#35745B]"
      data-testid={`display-student-${student.pid}`}
      aria-label={`${student.name}，${student.nickname || student.species || nicknameFallbackLabel}，${student.xp} ${nectarLabel}`}
    >
      <span
        className={`absolute top-1 right-1 z-10 rounded-full bg-[#E9F2DE]/95 font-black text-[#47704D] tabular-nums shadow-sm ${compact ? 'px-1.5 py-0.5 text-[9px]' : 'px-2 py-0.5 text-[10px]'}`}
        data-testid={`display-nectar-${student.pid}`}
      >
        {number(student.xp)} {nectarLabel}
      </span>
      <div
        className="absolute inset-0 grid place-items-center"
        data-testid={`display-bee-artwork-${student.pid}`}
      >
        {student.asset && !broken ? (
          <img
            src={student.asset}
            alt={`${student.name}的${student.species}`}
            className={
              student.asset.endsWith('.png')
                ? 'h-full w-full scale-[1.22] object-contain drop-shadow-[0_8px_8px_rgba(74,102,56,0.14)]'
                : 'h-full w-full object-contain'
            }
            loading="eager"
            decoding="async"
            onError={() => setBroken(true)}
          />
        ) : (
          <span className={compact ? 'text-3xl' : 'text-5xl'} role="img" aria-label="待认领伙伴">
            🐝
          </span>
        )}
      </div>
      <span
        className={`absolute right-1.5 bottom-1.5 z-10 block max-w-[78%] rounded-lg border border-white/75 bg-white/85 text-right shadow-[0_4px_14px_rgba(60,92,55,0.16)] backdrop-blur-[2px] ${compact ? 'px-1.5 py-1' : 'px-2 py-1'}`}
        data-testid={`display-student-caption-${student.pid}`}
      >
        <strong
          className={`block max-w-full truncate leading-tight ${compact ? 'text-xs' : 'text-sm'}`}
        >
          {student.name}
        </strong>
        <span
          className="mt-0.5 block max-w-full truncate text-[10px] leading-tight font-semibold text-[#4F7657]"
          data-testid={`display-student-nickname-${student.pid}`}
        >
          {student.nickname || student.species || nicknameFallbackLabel}
        </span>
      </span>
    </button>
  );
}

function DisplayDialog({
  title,
  closeLabel,
  onClose,
  testId,
  children,
}: {
  title: string;
  closeLabel: string;
  onClose: () => void;
  testId: string;
  children: React.ReactNode;
}) {
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);
  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-[#152D25]/70 p-4 backdrop-blur-sm"
      role="presentation"
      onMouseDown={(event) => {
        if (event.target === event.currentTarget) onClose();
      }}
    >
      <section
        role="dialog"
        aria-modal="true"
        aria-label={title}
        className="relative max-h-[92vh] w-full max-w-6xl overflow-auto rounded-[30px] border border-[#DCE8D4] bg-[#FAFCF7] p-5 shadow-2xl sm:p-7"
        data-testid={testId}
      >
        <button
          type="button"
          onClick={onClose}
          className="absolute top-4 right-4 z-10 grid h-10 w-10 place-items-center rounded-full border border-[#D9E5D5] bg-white text-[#547157] shadow-sm transition hover:bg-[#F0F5E9] focus-visible:outline-2 focus-visible:outline-[#35745B]"
          aria-label={closeLabel}
        >
          <X className="h-5 w-5" aria-hidden="true" />
        </button>
        {children}
      </section>
    </div>
  );
}

function TreePanel({
  snapshot,
  tree,
  imageBroken,
  setImageBroken,
  copy,
}: {
  snapshot: DisplaySnapshot;
  tree: { current?: TreeStage; next?: TreeStage; progress: number };
  imageBroken: boolean;
  setImageBroken: (value: boolean) => void;
  copy: (key: string, fallback: string) => string;
}) {
  return (
    <div
      className="relative overflow-hidden rounded-[26px] bg-white p-5 sm:p-8"
      data-testid="display-tree"
    >
      <div
        className="absolute -top-20 -right-16 h-64 w-64 rounded-full bg-[#F7E6A0]/40"
        aria-hidden="true"
      />
      <p className="relative text-xs font-bold tracking-[0.2em] text-[#6B896A]">
        {copy('treeLabel', '班级大树 · 集体成长')}
      </p>
      <div className="relative mt-2 grid items-center gap-6 lg:grid-cols-[minmax(420px,1.3fr)_minmax(0,0.7fr)]">
        <div
          className="grid h-[min(62vh,560px)] min-h-[320px] w-full place-items-center rounded-[24px] bg-[#F7FAF2]"
          data-testid="display-tree-artwork"
        >
          {tree.current?.asset && !imageBroken ? (
            <img
              src={tree.current.asset}
              alt={`${tree.current.name}班级大树`}
              className="h-full w-full scale-[1.06] object-contain drop-shadow-[0_22px_18px_rgba(76,103,52,0.18)]"
              loading="eager"
              decoding="async"
              fetchPriority="high"
              onError={() => setImageBroken(true)}
            />
          ) : (
            <span className="text-8xl" role="img" aria-label="班级大树">
              🌱
            </span>
          )}
        </div>
        <div className="min-w-0 flex-1">
          <p className="text-sm font-semibold text-[#789176]">
            {copy('semesterNectar', '本学期全班花蜜')}
          </p>
          <p
            className="mt-1 text-6xl font-black text-[#2B604A] tabular-nums"
            data-testid="display-tree-total"
          >
            {number(snapshot.semesterXp)}
          </p>
          <h2 className="mt-4 text-3xl font-bold" data-testid="display-tree-stage">
            {tree.current?.name || copy('stagePending', '成长阶段待配置')}
          </h2>
          {tree.next ? (
            <>
              <div
                className="mt-5 h-3 overflow-hidden rounded-full bg-[#E4EED8]"
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
              <p className="mt-3 text-sm font-medium text-[#658064]">
                {copy('nextStage', '距离下一阶段')}「{tree.next.name}」{copy('remaining', '还差')}{' '}
                {number(Number(tree.next.minXp) - snapshot.semesterXp)} {copy('nectar', '花蜜')}
              </p>
            </>
          ) : (
            <p className="mt-3 text-sm text-[#658064]">
              {copy('maxStage', '已到当前最高阶段，继续一起成长。')}
            </p>
          )}
        </div>
      </div>
    </div>
  );
}

function RankingPanel({
  students,
  weekStart,
  onSelect,
  copy,
}: {
  students: DisplayStudent[];
  weekStart: string;
  onSelect: (student: DisplayStudent) => void;
  copy: (key: string, fallback: string) => string;
}) {
  return (
    <div data-testid="display-ranking">
      <p className="text-xs font-bold tracking-[0.2em] text-[#6B896A]">
        {copy('weekly', '本周成长')}
      </p>
      <div className="mt-1 flex items-end justify-between gap-3 pr-12">
        <div className="flex items-center gap-3">
          <h2 className="text-3xl font-black">{copy('rankTitle', '花蜜排行榜')}</h2>
          <span className="rounded-full bg-[#E9F2DE] px-3 py-1 text-xs font-bold text-[#47704D]">
            {copy('topTen', '前10名')}
          </span>
        </div>
        <span className="text-xs text-[#789176]">
          {weekStart} {copy('since', '起 · 净花蜜')}
        </span>
      </div>
      <div className="mt-5 space-y-2">
        {students.slice(0, 10).map((student, index) => (
          <button
            type="button"
            key={student.pid}
            className="flex w-full items-center gap-4 rounded-2xl bg-white px-4 py-2.5 text-left transition hover:bg-[#EFF6E9] focus-visible:outline-2 focus-visible:outline-[#35745B]"
            onClick={() => onSelect(student)}
            data-testid={`display-rank-${index + 1}`}
          >
            <span
              className={`grid h-10 w-10 shrink-0 place-items-center rounded-xl text-base font-black ${index === 0 ? 'bg-[#FFE4A0] text-[#815D15]' : index === 1 ? 'bg-[#E8ECDF] text-[#5B6B58]' : index === 2 ? 'bg-[#F1E0CE] text-[#8C6746]' : 'bg-[#F0F5E9] text-[#688466]'}`}
            >
              {index + 1}
            </span>
            <span className="min-w-0 flex-1">
              <span className="block truncate text-base font-bold">{student.name}</span>
              <span className="block truncate text-xs text-[#718673]">
                {copy('studentNo', '学号')}{' '}
                {student.studentNo || copy('missingStudentNoShort', '未设置')} ·{' '}
                {student.nickname || student.species || copy('unclaimed', '伙伴待认领')}
              </span>
            </span>
            <strong className="text-xl text-[#2F7053] tabular-nums">
              {student.weekScore > 0 ? '+' : ''}
              {student.weekScore} {copy('nectar', '花蜜')}
            </strong>
          </button>
        ))}
        {students.length === 0 && (
          <p className="rounded-2xl bg-[#F4F8EE] p-5 text-sm text-[#718673]">
            {copy('emptyRoster', '班级尚无同学，请先导入班级名册。')}
          </p>
        )}
      </div>
      <p className="mt-4 text-xs text-[#81957E]">
        {copy('rankingHint', '按本周有效评价的净花蜜排序；点击同学可看公开评分记录。')}
      </p>
    </div>
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
