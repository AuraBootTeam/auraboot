import React, { useCallback, useEffect, useState } from 'react';
import { Navigate, useNavigate, useParams } from 'react-router';
import { useAuth } from '~/contexts/AuthContext';
import {
  xyList,
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
 * Xiaoya child space (孩子空间) — consumer surface, standalone shell.
 *
 * One companion per student (PRD 10): claim → rename → collect skins →
 * wear → redeem rewards. All mutations run through plugin commands, so the
 * server-side invariants (idempotency, balance, stock, unlock levels) stay
 * authoritative; this page only renders outcomes.
 *
 * Demo routing: /xy/child picks the first active enrollment when no student
 * pid is supplied so the sidebar menu entry works; /xy/child/:studentPid is
 * the family-entry shape.
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
  const [skins, setSkins] = useState<XyRow[]>([]);
  const [owned, setOwned] = useState<Set<string>>(new Set());
  const [rewards, setRewards] = useState<XyRow[]>([]);
  const [thresholds, setThresholds] = useState<number[]>([]);
  const [nickname, setNickname] = useState('');
  const [claimNickname, setClaimNickname] = useState('');
  const [celebrate, setCelebrate] = useState<string>('');
  const [error, setError] = useState<string>('');
  const [loading, setLoading] = useState(true);
  const { assetUrl } = usePetVisual(student, pet);

  const loadAll = useCallback(async (studentPid: string) => {
    setLoading(true);
    setError('');
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
      setSkins(await xyList('xy_pet_skin', [{ field: 'xy_sk_species', value: String(active.xy_pi_species || '') }]));
      const own = await xyList('xy_skin_ownership', [{ field: 'xy_so_student', value: studentPid }]);
      setOwned(new Set(own.map((o) => String(o.xy_so_skin))));
    } else {
      setSpecies(null);
      setSkins([]);
      setOwned(new Set());
      setSpeciesList(await xyList('xy_pet_species', [{ field: 'xy_ps_status', value: 'published' }]));
    }
    if (stu.pid) {
      const enrolls = await xyList('xy_enrollment', [{ field: 'xy_enr_student', value: String(stu.pid) }]);
      const activeEnr = enrolls.find((e) => e.xy_enr_status === 'active');
      if (activeEnr) {
        setRewards(await xyList('xy_reward_sku', [
          { field: 'xy_rs_class', value: String(activeEnr.xy_enr_class) },
          { field: 'xy_rs_status', value: 'active' },
        ]));
      } else {
        setRewards([]);
      }
    }
    setLoading(false);
  }, []);

  useEffect(() => {
    if (!isAuthenticated) return;
    let cancelled = false;
    (async () => {
      if (params.studentPid) {
        await loadAll(String(params.studentPid));
      } else {
        const enrs = await xyList('xy_enrollment', [{ field: 'xy_enr_status', value: 'active' }]);
        if (cancelled) return;
        setEnrollments(enrs);
        if (enrs[0]) await loadAll(String(enrs[0].xy_enr_student));
        else setLoading(false);
      }
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
          小芽 · 班级成长
        </div>
        <button className="rounded-pill px-3 py-1 text-xs" style={{ background: '#FFFFFF22' }} onClick={() => navigate('/')}>
          返回工作台
        </button>
      </div>

      <div className="mx-auto max-w-3xl px-5 pb-24 pt-6">
        {loading ? (
          <div className="text-text-2 text-sm">加载中…</div>
        ) : !student ? (
          <div className="text-text-2 rounded-card border border-[var(--color-border)] bg-[var(--color-panel)] p-6 text-sm" data-testid="child-space-empty">
            没有找到同学档案。请从老师工作台的名册进入,或直接使用 /xy/child/:studentPid 链接。
          </div>
        ) : (
          <>
            {/* demo picker */}
            {enrollments.length > 0 && (
              <div className="text-text-2 mb-4 text-xs">
                演示选择:
                {enrollments.slice(0, 8).map((e) => (
                  <button
                    key={String(e.pid)}
                    className="accent mx-1 rounded-pill px-2 py-0.5"
                    style={{ background: 'var(--color-accent-weak)', color: 'var(--color-accent)' }}
                    onClick={() => navigate(`/xy/child/${String(e.xy_enr_student)}`)}
                  >
                    {String(e.xy_enr_no || '')}
                  </button>
                ))}
              </div>
            )}

            {/* pet hero */}
            <div className="rounded-card-lg border p-6" style={{ background: 'var(--color-panel)', borderColor: 'var(--color-border)', boxShadow: 'var(--shadow-pop)' }} data-testid="child-hero">
              <div className="flex items-center gap-6">
                <PetAvatar assetUrl={assetUrl} speciesCode={speciesCode} size={170} />
                <div className="min-w-0 flex-1">
                  <div className="text-text-2 text-xs">你好,</div>
                  <div className="text-text mb-1 text-2xl font-semibold">{nickname || String(student.xy_stu_name || '')}</div>
                  {pet ? (
                    <>
                      <div className="mb-3 flex flex-wrap items-center gap-2">
                        <span className="rounded-pill px-3 py-1 text-xs font-semibold" style={{ background: 'var(--color-accent-weak)', color: 'var(--color-accent)' }}>
                          Lv.{level} {STAGE_LABEL[stage]}
                        </span>
                        <span className="rounded-pill px-3 py-1 text-xs font-semibold" style={{ background: '#DCEFB4', color: '#4C6B3C' }} data-testid="child-coins">
                          ⭐ 星币 {coins}
                        </span>
                        <span className="text-text-2 text-xs">{String(pet.xy_pi_nickname || '未命名')} · {species ? String(species.xy_ps_name) : ''}</span>
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
                          onClick={() => run(() => xyExec('xy:update_pi', { xy_pi_nickname: String(pet.xy_pi_nickname || '') }, petPid), '')}
                        >
                          保存昵称
                        </button>
                      </div>
                    </>
                  ) : (
                    <div data-testid="claim-panel">
                      <div className="text-text-2 mb-2 text-sm">还没有伙伴,选一只认领吧(认领不会清零已有成长):</div>
                      <div className="mb-3 flex flex-wrap gap-2">
                        {speciesList.map((sp) => (
                          <button
                            key={String(sp.pid)}
                            className="flex items-center gap-2 rounded-[var(--radius-card)] border px-3 py-2 text-sm"
                            style={{ borderColor: 'var(--color-border)', background: 'var(--color-subtle)' }}
                            onClick={() => {
                              const enr = enrollments.find((e) => String(e.xy_enr_student) === String(student.pid));
                              void run(() => xyExec('xy_pet_instance:claim', {
                                student: String(student.pid),
                                class: String(enr?.xy_enr_class || ''),
                                species: String(sp.pid),
                                nickname: claimNickname || String(student.xy_stu_name || '') + '的伙伴',
                              }), `和 ${String(sp.xy_ps_name)} 成为伙伴啦!`);
                            }}
                          >
                            <span className="text-xl">{SPECIES_EMOJI[String(sp.xy_ps_code)] || '🌱'}</span>
                            {String(sp.xy_ps_name)}
                          </button>
                        ))}
                      </div>
                      <input
                        className="text-text h-9 w-56 rounded-[var(--radius-control)] border px-3 text-sm outline-none"
                        style={{ borderColor: 'var(--color-border)' }}
                        placeholder="给它起个名字(可选)"
                        value={claimNickname}
                        onChange={(e2) => setClaimNickname(e2.target.value)}
                      />
                    </div>
                  )}
                </div>
              </div>
            </div>

            {/* skins */}
            {pet && skins.length > 0 && (
              <div className="mt-5" data-testid="skin-shop">
                <div className="text-text mb-2 text-sm font-semibold">皮肤收藏</div>
                <div className="grid grid-cols-2 gap-3 sm:grid-cols-3">
                  {skins.map((sk) => {
                    const skinPid = String(sk.pid);
                    const isOwned = owned.has(skinPid);
                    const worn = String(pet.xy_pi_worn_skin || '') === skinPid;
                    const price = Number(sk.xy_sk_price_coins ?? 0);
                    const minLv = Number(sk.xy_sk_min_level ?? 1);
                    return (
                      <div key={skinPid} className="rounded-card border p-3" style={{ borderColor: worn ? 'var(--color-accent)' : 'var(--color-border)', background: 'var(--color-panel)' }}>
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
