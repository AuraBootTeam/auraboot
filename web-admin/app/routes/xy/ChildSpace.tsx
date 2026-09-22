import React, { useCallback, useEffect, useState } from 'react';
import { Navigate, useNavigate, useParams } from 'react-router';
import { useAuth } from '~/contexts/AuthContext';
import {
  xyList,
  xyListChecked,
  xyGet,
  xyExec,
  loadThresholds,
  levelFor,
  stageFor,
  STAGE_LABEL,
  SPECIES_EMOJI,
  type XyRow,
} from './eduApi';
import { PetAvatar, usePetVisual } from './PetAvatar';

/**
 * Fengyun child space (孩子空间) — consumer surface, standalone shell.
 *
 * One companion per student (PRD 10): claim → rename → collect skins →
 * wear → redeem rewards. All mutations run through plugin commands, so the
 * server-side invariants (idempotency, balance, stock, unlock levels) stay
 * authoritative; this page only renders outcomes.
 *
 * /xy/child picks the first student inside the operator's server-enforced
 * classroom scope; /xy/child/:studentPid is accepted only when that student
 * is present in the same scoped roster.
 */
export default function ChildSpace() {
  const { isAuthenticated } = useAuth();
  const params = useParams();
  const navigate = useNavigate();
  const [student, setStudent] = useState<XyRow | null>(null);
  const [enrollments, setEnrollments] = useState<XyRow[]>([]);
  const [pet, setPet] = useState<XyRow | null>(null);
  const [species, setSpecies] = useState<XyRow | null>(null);
  const [speciesList, setSpeciesList] = useState<XyRow[]>([]);
  const [catalogError, setCatalogError] = useState('');
  const [skins, setSkins] = useState<XyRow[]>([]);
  const [owned, setOwned] = useState<Set<string>>(new Set());
  const [rewards, setRewards] = useState<XyRow[]>([]);
  const [thresholds, setThresholds] = useState<number[]>([]);
  const [nickname, setNickname] = useState('');
  const [claimNickname, setClaimNickname] = useState('');
  const [claimSpeciesPid, setClaimSpeciesPid] = useState('');
  const [studentQuery, setStudentQuery] = useState('');
  const [classFilter, setClassFilter] = useState('');
  const [skinPreviews, setSkinPreviews] = useState<Record<string, string>>({});
  const [switchSpeciesPid, setSwitchSpeciesPid] = useState('');
  const [celebrate, setCelebrate] = useState<string>('');
  const [error, setError] = useState<string>('');
  const [loading, setLoading] = useState(true);
  const { assetUrl } = usePetVisual(student, pet);

  const loadAll = useCallback(async (studentPid: string) => {
    setLoading(true);
    setError('');
    setCatalogError('');
    setClaimSpeciesPid('');
    const stu = await xyGet('xy_student', studentPid);
    if (!stu) {
      setStudent(null);
      setLoading(false);
      return;
    }
    setStudent(stu);
    setNickname(String(stu.xy_stu_name || ''));
    setThresholds(await loadThresholds());
    const pets = await xyList('xy_pet_instance', [{ field: 'xy_pi_student', value: studentPid }]);
    const active = pets.find((p) => p.xy_pi_status === 'active') || null;
    setPet(active);
    if (active) {
      const sp = await xyGet('xy_pet_species', String(active.xy_pi_species || ''));
      setSpecies(sp);
      const speciesSkins = await xyList('xy_pet_skin', [{ field: 'xy_sk_species', value: String(active.xy_pi_species || '') }]);
      setSkins(speciesSkins);
      const currentStage = String(stu.xy_stu_stage || 'stage_1');
      const previewRows = await Promise.all(speciesSkins.map(async (skin) => {
        const matching = await xyList('xy_skin_asset', [
          { field: 'xy_sa_species', value: String(active.xy_pi_species || '') },
          { field: 'xy_sa_skin', value: String(skin.pid || '') },
          { field: 'xy_sa_stage', value: currentStage },
        ]);
        return [String(skin.pid || ''), String(matching[0]?.xy_sa_asset || '')] as const;
      }));
      setSkinPreviews(Object.fromEntries(previewRows));
      const own = await xyList('xy_skin_ownership', [{ field: 'xy_so_student', value: studentPid }]);
      setOwned(new Set(own.map((o) => String(o.xy_so_skin))));
      setSwitchSpeciesPid('');
    } else {
      setSpecies(null);
      setSkins([]);
      setSkinPreviews({});
      setOwned(new Set());
    }
    if (stu.pid) {
      const enrolls = await xyList('xy_enrollment', [{ field: 'xy_enr_student', value: String(stu.pid) }]);
      const activeEnr = enrolls.find((e) => e.xy_enr_status === 'active');
      if (activeEnr) {
        const cls = await xyGet('xy_classroom', String(activeEnr.xy_enr_class));
        const published = await xyListChecked('xy_pet_species', [{ field: 'xy_ps_status', value: 'published' }]);
        setCatalogError(published.error);
        setSpeciesList(published.rows.filter((sp) => String(sp.xy_ps_theme) === String(cls?.xy_cls_theme)));
        setRewards(await xyList('xy_reward_sku', [
          { field: 'xy_rs_class', value: String(activeEnr.xy_enr_class) },
          { field: 'xy_rs_status', value: 'active' },
        ]));
      } else {
        setSpeciesList([]);
        setRewards([]);
      }
    }
    setLoading(false);
  }, []);

  useEffect(() => {
    if (!isAuthenticated) return;
    let cancelled = false;
    (async () => {
      const scoped = await xyExec('xy:child_space_students');
      if (cancelled) return;
      if (!scoped.ok) {
        setError(scoped.message || '无法加载可管理的同学');
        setLoading(false);
        return;
      }
      const classrooms = Array.isArray(scoped.data.classrooms)
        ? scoped.data.classrooms as XyRow[]
        : [];
      const enrs = classrooms.flatMap((classroom) => {
        const students = Array.isArray(classroom.students) ? classroom.students as XyRow[] : [];
        return students.map((row) => ({
          pid: `${String(classroom.classPid)}:${String(row.pid)}`,
          xy_enr_student: row.pid,
          xy_enr_class: classroom.classPid,
          xy_enr_no: row.xy_stu_code,
          xy_enr_student_name: row.xy_stu_name,
          xy_enr_class_name: classroom.className,
        }));
      });
      setEnrollments(enrs);
      const requestedPid = params.studentPid ? String(params.studentPid) : '';
      const selected = requestedPid
        ? enrs.find((e) => String(e.xy_enr_student) === requestedPid)
        : enrs[0];
      if (requestedPid && !selected) {
        setStudent(null);
        setError('你只能管理自己班级的同学');
        setLoading(false);
        return;
      }
      if (selected) await loadAll(String(selected.xy_enr_student));
      else setLoading(false);
    })();
    return () => {
      cancelled = true;
    };
  }, [isAuthenticated, params.studentPid, loadAll]);

  if (!isAuthenticated) return <Navigate to="/login" replace />;

  const run = async (fn: () => Promise<{ ok: boolean; message: string }>, successMsg: string) => {
    setError('');
    const r = await fn();
    if (r.ok) {
      if (successMsg) setCelebrate(successMsg);
      const pid = student?.pid;
      if (pid) await loadAll(String(pid));
    } else {
      setError(r.message || '操作失败');
    }
  };

  const xp = Number(student?.xy_stu_xp_total ?? 0);
  const level = levelFor(xp, thresholds.length ? thresholds : [0]);
  const stage = stageFor(level);
  const nextIdx = thresholds.findIndex((min) => min > xp);
  const nextMin = nextIdx === -1 ? null : thresholds[nextIdx];
  const prevMin = nextIdx === -1 ? xp : thresholds[Math.max(0, nextIdx - 1)];
  const progressPct = nextMin == null ? 100 : Math.min(100, Math.round(((xp - prevMin) / Math.max(1, nextMin - prevMin)) * 100));
  const speciesCode = String(species?.xy_ps_code || '');
  const coins = Number(student?.xy_stu_coin_balance ?? 0);
  const petPid = pet?.pid ? String(pet.pid) : '';
  const claimSpecies = speciesList.find((item) => String(item.pid) === claimSpeciesPid);
  const classes = Array.from(new Map(enrollments.map((enrollment) => [
    String(enrollment.xy_enr_class),
    { pid: String(enrollment.xy_enr_class), name: String(enrollment.xy_enr_class_name || '未命名班级') },
  ])).values());
  const visibleRoster = enrollments.filter((enrollment) => {
    if (classFilter && String(enrollment.xy_enr_class) !== classFilter) return false;
    const haystack = `${String(enrollment.xy_enr_student_name || '')} ${String(enrollment.xy_enr_no || '')}`;
    return haystack.toLocaleLowerCase().includes(studentQuery.trim().toLocaleLowerCase());
  });

  return (
    <div className="min-h-screen" style={{ background: 'var(--color-bg)' }} data-testid="child-space">
      {/* top rail */}
      <div className="flex items-center justify-between px-6 py-3" style={{ background: 'var(--color-inverse)', color: 'var(--color-inverse-text)' }}>
        <div className="flex items-center gap-2 text-sm font-semibold tracking-wide">
          <span className="grid h-7 w-7 rotate-[-6deg] grid-cols-2 gap-0.5">
            <span className="rounded-[8px_8px_3px_8px]" style={{ background: 'var(--color-accent)' }} />
            <span className="rounded-[8px_8px_3px_8px] rotate-90" style={{ background: '#A8C98F' }} />
            <span className="rounded-[8px_8px_3px_8px] -rotate-90" style={{ background: '#D7E7AD' }} />
            <span className="rounded-[8px_8px_3px_8px] rotate-180" style={{ background: 'var(--color-accent)', opacity: 0.75 }} />
          </span>
          蜂耘 · 班级成长
        </div>
        <button className="rounded-pill px-3 py-1 text-xs" style={{ background: '#FFFFFF22' }} onClick={() => navigate('/')}>
          返回工作台
        </button>
      </div>

      <div className="mx-auto max-w-6xl px-5 pb-24 pt-8">
        <div className="mb-6">
          <p className="text-xs font-bold tracking-[0.18em] text-[#64846A]">家庭与伙伴 / 班级学生</p>
          <h1 className="mt-2 text-3xl font-bold text-[#213D32]">孩子空间</h1>
          <p className="mt-2 text-sm text-[#667A6B]">先选择本班同学，再认领蜜蜂、设置昵称或更换装扮。成长和评分记录不会因换蜂种而清零。</p>
        </div>
        {loading ? (
          <div className="rounded-card-lg border border-[var(--color-border)] bg-[var(--color-panel)] p-8 text-sm text-[#667A6B]">正在加载同学与伙伴…</div>
        ) : !student ? (
          <div className="text-text-2 rounded-card border border-[var(--color-border)] bg-[var(--color-panel)] p-6 text-sm" data-testid="child-space-empty">
            {error || '当前没有可管理的同学。请先在班级名册中导入学生。'}
          </div>
        ) : (
          <>
            <div className="grid items-start gap-5 lg:grid-cols-[268px_minmax(0,1fr)]">
              <aside className="rounded-card-lg border border-[var(--color-border)] bg-[var(--color-panel)] p-4" aria-label="选择同学" data-testid="child-student-roster">
                <div className="flex items-center justify-between gap-2">
                  <h2 className="text-base font-bold text-[#213D32]">选择同学</h2>
                  <span className="text-xs text-[#789176]">{enrollments.length} 人</span>
                </div>
                {classes.length > 1 && (
                  <select aria-label="筛选班级" className="mt-4 w-full rounded-[var(--radius-control)] border border-[var(--color-border)] bg-white px-3 py-2 text-sm" value={classFilter} onChange={(event) => setClassFilter(event.target.value)}>
                    <option value="">全部班级</option>
                    {classes.map((cls) => <option key={cls.pid} value={cls.pid}>{cls.name}</option>)}
                  </select>
                )}
                <input aria-label="搜索同学" className="mt-3 w-full rounded-[var(--radius-control)] border border-[var(--color-border)] bg-white px-3 py-2 text-sm" placeholder="搜索姓名或学号" value={studentQuery} onChange={(event) => setStudentQuery(event.target.value)} />
                <div className="mt-3 max-h-[620px] space-y-1 overflow-y-auto pr-1">
                  {visibleRoster.map((enrollment) => {
                    const active = String(enrollment.xy_enr_student) === String(student.pid);
                    return (
                      <button key={String(enrollment.pid)} type="button" className={`flex w-full items-center justify-between rounded-xl px-3 py-2.5 text-left text-sm transition ${active ? 'bg-[#E8F2E5] font-bold text-[#255A43]' : 'text-[#405C4B] hover:bg-[#F1F6EC]'}`} aria-current={active ? 'true' : undefined} onClick={() => navigate(`/xy/child/${String(enrollment.xy_enr_student)}`)}>
                        <span className="truncate">{String(enrollment.xy_enr_student_name || '未命名同学')}</span>
                        <span className="ml-2 shrink-0 text-xs font-normal text-[#829581]">{String(enrollment.xy_enr_no || '')}</span>
                      </button>
                    );
                  })}
                  {visibleRoster.length === 0 && <p className="px-2 py-5 text-center text-sm text-[#789176]">没有匹配的同学</p>}
                </div>
              </aside>
              <div className="min-w-0">

            {/* pet hero */}
            <div className="rounded-card-lg border p-6" style={{ background: 'var(--color-panel)', borderColor: 'var(--color-border)', boxShadow: 'var(--shadow-pop)' }} data-testid="child-hero">
              <div className="flex flex-col gap-6 sm:flex-row">
                {pet ? <PetAvatar assetUrl={assetUrl} speciesCode={speciesCode} size={300} /> : (
                  <div className="grid h-[300px] w-[300px] shrink-0 place-items-center rounded-[28px] bg-[#F1F6E9]">
                    {claimSpecies ? <SpeciesCover url={String(claimSpecies.xy_ps_cover || '')} code={String(claimSpecies.xy_ps_code || '')} size={270} /> : <span className="text-8xl" aria-hidden="true">🐝</span>}
                  </div>
                )}
                <div className="min-w-0 flex-1">
                  <div className="text-text-2 text-xs">正在管理的同学 · {nickname || String(student.xy_stu_name || '')}</div>
                  {pet ? (
                    <>
                      <h2 className="mt-1 text-3xl font-bold text-[#213D32]">{String(pet.xy_pi_nickname || '未命名伙伴')}</h2>
                      <p className="mb-3 mt-1 text-sm font-semibold text-[#64846A]">{species ? String(species.xy_ps_name) : '成长伙伴'} · 一起采蜜成长</p>
                      <div className="mb-3 flex flex-wrap items-center gap-2">
                        <span className="rounded-pill px-3 py-1 text-xs font-semibold" style={{ background: 'var(--color-accent-weak)', color: 'var(--color-accent)' }}>
                          Lv.{level} {STAGE_LABEL[stage]}
                        </span>
                        <span className="rounded-pill px-3 py-1 text-xs font-semibold" style={{ background: '#DCEFB4', color: '#4C6B3C' }} data-testid="child-coins">
                          ⭐ 星币 {coins}
                        </span>
                      </div>
                      <div className="mb-1 flex justify-between text-xs" style={{ color: 'var(--color-text-3)' }}>
                        <span>成长值 {xp}</span>
                        <span>{nextMin == null ? '当前方案最高等级,成长继续记录' : `下一级 ${nextMin}`}</span>
                      </div>
                      <div className="h-2 overflow-hidden rounded-pill" style={{ background: '#E3EAD6' }}>
                        <div className="h-full rounded-pill transition-all duration-500" style={{ width: `${progressPct}%`, background: '#93B275' }} data-testid="child-progress" />
                      </div>
                      <div className="mt-3 flex items-center gap-2">
                        <input
                          className="text-text h-9 w-44 rounded-[var(--radius-control)] border px-3 text-sm outline-none"
                          style={{ borderColor: 'var(--color-border)', background: 'var(--color-panel)' }}
                          value={String(pet.xy_pi_nickname || '')}
                          onChange={(e2) => setPet({ ...pet, xy_pi_nickname: e2.target.value })}
                          aria-label="伙伴昵称"
                        />
                        <button
                          className="rounded-[var(--radius-control)] px-3 py-2 text-xs font-semibold text-white"
                          style={{ background: 'var(--color-accent)' }}
                          onClick={() => run(() => xyExec('xy_pet_instance:rename', { nickname: String(pet.xy_pi_nickname || '') }, petPid), '')}
                        >
                          保存昵称
                        </button>
                      </div>
                      {speciesList.some((sp) => String(sp.pid) !== String(species?.pid)) && (
                        <div className="mt-4 rounded-card border p-3" style={{ borderColor: 'var(--color-border)', background: 'var(--color-subtle)' }} data-testid="switch-species-panel">
                          <label htmlFor="switch-bee-species" className="text-text block text-sm font-semibold">切换蜂种</label>
                          <p className="text-text-2 mt-1 text-xs">昵称、等级和成长记录保留；换蜂种后先穿该蜂种的默认外观，旧皮肤仍在收藏中。</p>
                          <div className="mt-2 flex flex-wrap gap-2">
                            <select id="switch-bee-species" className="text-text h-9 rounded-[var(--radius-control)] border px-2 text-sm" style={{ borderColor: 'var(--color-border)', background: 'var(--color-panel)' }} value={switchSpeciesPid} onChange={(e) => setSwitchSpeciesPid(e.target.value)}>
                              <option value="">选择新蜂种</option>
                              {speciesList.filter((sp) => String(sp.pid) !== String(species?.pid)).map((sp) => (
                                <option key={String(sp.pid)} value={String(sp.pid)}>{String(sp.xy_ps_name)}</option>
                              ))}
                            </select>
                            <button type="button" disabled={!switchSpeciesPid} className="rounded-[var(--radius-control)] px-3 py-2 text-xs font-semibold text-white disabled:opacity-50" style={{ background: 'var(--color-accent)' }} onClick={() => void run(() => xyExec('xy_pet_instance:switch_species', { species: switchSpeciesPid }, petPid), '蜂种已切换，成长记录仍在。')}>
                              确认切换
                            </button>
                          </div>
                        </div>
                      )}
                    </>
                  ) : (
                    <div data-testid="claim-panel">
                      <h2 className="mb-2 text-2xl font-semibold text-[#213D32]">{nickname || String(student.xy_stu_name || '')}</h2>
                      <p className="text-sm font-semibold text-[#335946]">还没有认领伙伴</p>
                      <p className="mt-1 text-sm text-[#718673]">选择喜欢的蜂种，再给它起个名字；认领不会清零已有成长。</p>
                      {catalogError ? (
                        <div className="mt-4 rounded-xl border border-[#E4D9A9] bg-[#FFFBEA] px-4 py-3 text-sm text-[#765D2A]" role="alert" data-testid="claim-catalog-error">
                          暂时无法读取蜂种目录：{catalogError}。请刷新页面或联系学校管理员。
                        </div>
                      ) : speciesList.length === 0 ? (
                        <div className="mt-4 rounded-xl border border-[#E4D9A9] bg-[#FFFBEA] px-4 py-3 text-sm text-[#765D2A]" role="status" data-testid="claim-catalog-empty">
                          本校尚未启用蜂种和阶段素材，请学校管理员完成蜂耘内容初始化后再认领。
                        </div>
                      ) : (
                        <>
                          <div className="mt-4 grid grid-cols-2 gap-2 sm:grid-cols-3" role="group" aria-label="选择蜂种">
                            {speciesList.map((sp) => {
                              const selected = String(sp.pid) === claimSpeciesPid;
                              return (
                                <button key={String(sp.pid)} type="button" aria-pressed={selected} className={`rounded-2xl border p-3 text-center transition ${selected ? 'border-[#35745B] bg-[#EAF4E8] shadow-sm' : 'border-[#E1E8D2] bg-white hover:border-[#8FC1A5]'}`} onClick={() => setClaimSpeciesPid(String(sp.pid))}>
                                  <span className="mx-auto grid h-28 w-28 place-items-center"><SpeciesCover url={String(sp.xy_ps_cover || '')} code={String(sp.xy_ps_code || '')} size={108} /></span>
                                  <span className="mt-1 block text-sm font-semibold text-[#213D32]">{String(sp.xy_ps_name)}</span>
                                </button>
                              );
                            })}
                          </div>
                          <label htmlFor="claim-bee-nickname" className="mt-4 block text-sm font-semibold text-[#335946]">伙伴昵称（选填）</label>
                          <input id="claim-bee-nickname" className="mt-2 h-10 w-full rounded-[var(--radius-control)] border border-[var(--color-border)] bg-white px-3 text-sm outline-none focus:border-[#35745B]" placeholder="例如：小蜜糖" value={claimNickname} onChange={(event) => setClaimNickname(event.target.value)} />
                          <button type="button" disabled={!claimSpeciesPid} className="mt-4 w-full rounded-[var(--radius-control)] bg-[#35745B] px-4 py-2.5 text-sm font-semibold text-white disabled:cursor-not-allowed disabled:opacity-40" onClick={() => {
                            const enr = enrollments.find((item) => String(item.xy_enr_student) === String(student.pid));
                            void run(() => xyExec('xy_pet_instance:claim', {
                              student: String(student.pid),
                              class: String(enr?.xy_enr_class || ''),
                              species: claimSpeciesPid,
                              nickname: claimNickname.trim() || `${String(student.xy_stu_name || '')}的伙伴`,
                            }), `和 ${String(claimSpecies?.xy_ps_name || '新伙伴')} 成为伙伴啦!`);
                          }}>确认认领{claimSpecies ? ` · ${String(claimSpecies.xy_ps_name)}` : ''}</button>
                        </>
                      )}
                    </div>
                  )}
                </div>
              </div>
            </div>

            {/* skins */}
            {pet && skins.length > 0 && (
              <div className="mt-5" data-testid="skin-shop">
                <div className="mb-1 text-lg font-bold text-[#213D32]">伙伴装扮</div>
                <p className="mb-3 text-sm text-[#718673]">只显示当前蜂种可穿戴的皮肤；切换蜂种不会丢失已收藏皮肤。</p>
                <div className="grid grid-cols-2 gap-3 sm:grid-cols-3">
                  {skins.map((sk) => {
                    const skinPid = String(sk.pid);
                    const isOwned = owned.has(skinPid);
                    const worn = String(pet.xy_pi_worn_skin || '') === skinPid;
                    const price = Number(sk.xy_sk_price_coins ?? 0);
                    const minLv = Number(sk.xy_sk_min_level ?? 1);
                    return (
                      <div key={skinPid} className="rounded-card border p-3" style={{ borderColor: worn ? 'var(--color-accent)' : 'var(--color-border)', background: 'var(--color-panel)' }}>
                        <div className="grid h-36 place-items-center rounded-xl bg-[#F1F6E9]">
                          <SpeciesCover url={skinPreviews[skinPid] || ''} code={speciesCode} size={132} />
                        </div>
                        <div className="text-text text-sm font-semibold">{String(sk.xy_sk_name)}</div>
                        <div className="text-text-2 mt-1 text-xs">
                          {isOwned ? '已拥有' : `Lv.${minLv} 解锁 · ⭐ ${price}`}
                        </div>
                        <button
                          className="mt-2 w-full rounded-[var(--radius-control)] px-2 py-1.5 text-xs font-semibold"
                          style={worn
                            ? { background: 'var(--color-accent-weak)', color: 'var(--color-accent)' }
                            : { background: 'var(--color-accent)', color: '#fff' }}
                          disabled={worn}
                          onClick={() => run(
                            () => (isOwned
                              ? xyExec('xy_pet_instance:wear_skin', { skin: skinPid }, petPid)
                              : xyExec('xy_pet_instance:purchase_skin', { skin: skinPid }, petPid)),
                            isOwned ? '已经换上新装扮!' : '新皮肤进入收藏,点击穿戴!',
                          )}
                        >
                          {worn ? '穿戴中' : isOwned ? '穿戴' : '购买'}
                        </button>
                      </div>
                    );
                  })}
                </div>
              </div>
            )}

            {/* rewards shelf */}
            {rewards.length > 0 && (
              <div className="mt-5" data-testid="reward-shelf">
                <div className="text-text mb-2 text-sm font-semibold">心愿奖励(兑换后由老师发放)</div>
                <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
                  {rewards.map((rs) => {
                    const total = Number(rs.xy_rs_stock_total ?? 0);
                    const left = total === 0 ? null : total - Number(rs.xy_rs_reserved ?? 0) - Number(rs.xy_rs_fulfilled ?? 0);
                    return (
                      <div key={String(rs.pid)} className="flex items-center justify-between rounded-card border p-4" style={{ borderColor: 'var(--color-border)', background: 'var(--color-panel)' }}>
                        <div className="min-w-0">
                          <div className="text-text truncate text-sm font-semibold">🎁 {String(rs.xy_rs_name)}</div>
                          <div className="text-text-2 mt-1 text-xs">
                            ⭐ {String(rs.xy_rs_price_coins)}{left != null ? ` · 剩 ${left} 份` : ''}
                          </div>
                        </div>
                        <button
                          className="ml-3 shrink-0 rounded-pill px-4 py-2 text-xs font-semibold text-white"
                          style={{ background: 'var(--color-accent)' }}
                          onClick={() => run(() => xyExec('xy_reward_order:redeem', { sku: String(rs.pid), student: String(student.pid) }), '兑换成功,等老师发放哦!')}
                        >
                          兑换
                        </button>
                      </div>
                    );
                  })}
                </div>
              </div>
            )}

            {error && (
              <div className="mt-4 rounded-[var(--radius-control)] px-4 py-3 text-sm" style={{ background: '#FFF0EB', color: '#A76251' }} data-testid="child-error" role="alert">
                {error}
              </div>
            )}
              </div>
            </div>
          </>
        )}
      </div>

      {/* celebrate overlay */}
      {celebrate && (
        <div className="fixed inset-0 z-50 grid place-items-center backdrop-blur-sm" style={{ background: '#264B3066' }} data-testid="celebrate" onClick={() => setCelebrate('')}>
          <div className="rounded-[28px] border-4 px-16 py-9 text-center" style={{ background: '#F7FAED', borderColor: '#E1EBCC' }}>
            <div className="text-5xl">🎉</div>
            <div className="mt-2 text-xl font-semibold" style={{ color: '#324F3A' }}>{celebrate}</div>
          </div>
        </div>
      )}
    </div>
  );
}

function SpeciesCover({ url, code, size = 40 }: { url: string; code: string; size?: number }) {
  const [broken, setBroken] = useState(false);
  useEffect(() => setBroken(false), [url]);
  return url && !broken
    ? <img src={url} alt="" style={{ width: size, height: size }} className="object-contain" onError={() => setBroken(true)} />
    : <span style={{ fontSize: size * 0.65 }} aria-hidden="true">{SPECIES_EMOJI[code] || '🐝'}</span>;
}
