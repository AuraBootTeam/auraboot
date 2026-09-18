import React, { useCallback, useEffect, useState } from 'react';
import { Navigate, useNavigate, useParams } from 'react-router';
import { useAuth } from '~/contexts/AuthContext';
import { useTenantTheme } from '~/contexts/TenantThemeContext';
import { xyList, xyGet, SPECIES_EMOJI, type XyRow } from './eduApi';
import { PetAvatar, usePetVisual } from './PetAvatar';

/**
 * Xiaoya class display (班级大屏) — read-only surface for the classroom screen:
 * class card, shared-goal progress, recent praise ticker, deduction records and
 * the companion parade. No student rows are editable here. Negative records are
 * shown since the PRD 8.3 / FR-039 口径变更 (deductions public with mandatory
 * reason) — deduction batches render in their own card, never mixed into praise.
 *
 * Auth: same session as the console (revocable read-only session tokens stay
 * out of V4 scope — recorded in the acceptance report).
 * Data freshness: platform data-sync SSE push (FR-036), with a 5s polling
 * safety net for dropped connections.
 */
export default function ClassDisplay() {
  const { isAuthenticated, user } = useAuth();
  const tenantTheme = useTenantTheme();
  const params = useParams();
  const navigate = useNavigate();
  const [classroom, setClassroom] = useState<XyRow | null>(null);
  const [students, setStudents] = useState<XyRow[]>([]);
  const [pets, setPets] = useState<XyRow[]>([]);
  const [goal, setGoal] = useState<XyRow | null>(null);
  const [progress, setProgress] = useState(0);
  const [praise, setPraise] = useState<XyRow[]>([]);
  const [deductions, setDeductions] = useState<XyRow[]>([]);
  const [loaded, setLoaded] = useState(false);

  const load = useCallback(async (classPid: string) => {
    const cls = classPid ? await xyGet('xy_classroom', classPid) : (await xyList('xy_classroom'))[0] ?? null;
    if (!cls) {
      setLoaded(true);
      return;
    }
    setClassroom(cls);
    const enrs = await xyList('xy_enrollment', [
      { field: 'xy_enr_class', value: String(cls.pid) },
      { field: 'xy_enr_status', value: 'active' },
    ]);
    const stus: XyRow[] = [];
    for (const e of enrs) {
      const s = await xyGet('xy_student', String(e.xy_enr_student));
      if (s && s.xy_stu_status === 'active') stus.push(s);
    }
    setStudents(stus);
    const allPets = await xyList('xy_pet_instance');
    setPets(allPets.filter((p) => p.xy_pi_status === 'active' && stus.some((s) => String(s.pid) === String(p.xy_pi_student))));
    const goals = await xyList('xy_class_goal', [
      { field: 'xy_cg_class', value: String(cls.pid) },
      { field: 'xy_cg_status', value: 'active' },
    ]);
    const g = goals[0] ?? null;
    setGoal(g);
    if (g) {
      const entries = await xyList('xy_ledger_entry', [
        { field: 'xy_le_goal', value: String(g.pid) },
        { field: 'xy_le_currency', value: 'energy' },
      ]);
      setProgress(entries.reduce((acc, e) => acc + Number(e.xy_le_amount ?? 0), 0));
    }
    setPraise(await xyList('xy_evaluation', [
      { field: 'xy_ev_class', value: String(cls.pid) },
      { field: 'xy_ev_status', value: 'effective' },
      { field: 'xy_ev_visibility', value: 'public' },
    ]));
    setDeductions(await xyList('xy_evaluation', [
      { field: 'xy_ev_class', value: String(cls.pid) },
      { field: 'xy_ev_status', value: 'effective' },
      { field: 'xy_ev_kind', value: 'deduct' },
    ]));
    setLoaded(true);
  }, []);

  useEffect(() => {
    if (!isAuthenticated) return;
    const classPid = String(params.classPid || '');
    load(classPid);

    // FR-036: real-time push over the platform data-sync SSE channel. The screen
    // subscribes to the models it renders, so any command touching them (evaluation
    // submitted, ledger entry, goal update) delivers a named `data:changed` event
    // and the data reloads within the push latency. Named events never reach
    // `onmessage`, so both listeners must be explicit. A 5s poll stays as the
    // safety net while EventSource re-establishes a dropped connection.
    const models = [
      'xy_classroom',
      'xy_enrollment',
      'xy_student',
      'xy_pet_instance',
      'xy_class_goal',
      'xy_ledger_entry',
      'xy_evaluation',
    ];
    const es = new EventSource('/api/notifications/stream', { withCredentials: true });
    es.addEventListener('data-sync-connected', (event) => {
      try {
        const { connectionId } = JSON.parse(String((event as MessageEvent).data)) as { connectionId: number };
        void fetch('/api/data-sync/subscribe', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          credentials: 'include',
          body: JSON.stringify({ connectionId, modelCodes: models }),
        });
      } catch {
        // Malformed handshake frame — the fallback poll keeps the screen correct.
      }
    });
    es.addEventListener('data:changed', () => void load(classPid));

    const timer = setInterval(() => load(classPid), 5000);
    return () => {
      es.close();
      clearInterval(timer);
    };
  }, [isAuthenticated, params.classPid, load]);

  if (!isAuthenticated) return <Navigate to="/login" replace />;

  const name = classroom ? String(classroom.xy_cls_alias || classroom.xy_cls_name || '') : '';
  const target = goal ? Number(goal.xy_cg_target_energy ?? 0) : 0;
  const pct = target > 0 ? Math.min(100, Math.round((progress / target) * 100)) : 0;

  return (
    <div className="min-h-screen p-8" style={{ background: '#EEF3E1' }} data-testid="class-display">
      <div className="mx-auto max-w-6xl">
        <div className="flex items-center justify-between">
          <div>
            <div className="text-xs font-semibold tracking-[2px]" style={{ color: '#7C9271' }}>CLASS GROWTH · 共同目标</div>
            <h1 className="mt-1 text-4xl font-bold tracking-tight" style={{ color: '#213D32' }} data-testid="display-title">
              {loaded ? name || '班级大屏' : '加载中…'}
            </h1>
            {classroom && <p className="mt-1 text-sm" style={{ color: '#8C9B79' }}>{String(classroom.xy_cls_slogan || '')}</p>}
            {tenantTheme?.brandName && (
              <p className="mt-1 text-xs font-semibold tracking-wide" style={{ color: '#7C9271' }} data-testid="display-brand">{tenantTheme.brandName}</p>
            )}
          </div>
          <div className="flex items-center gap-3">
            <span className="rounded-pill px-3 py-1.5 text-xs" style={{ background: '#FFFFFF99', color: '#426B3E' }} data-testid="display-identity">
              大屏账号:{user?.name || '未登录'}
            </span>
            <button className="rounded-pill px-3 py-1.5 text-xs" style={{ background: '#FFFFFF66', color: '#426B3E' }} onClick={() => navigate('/')}>
              退出大屏
            </button>
          </div>
        </div>

        <div className="mt-8 grid items-center gap-10 lg:grid-cols-[1.2fr_1fr]">
          {/* goal progress */}
          <div className="rounded-card-lg border p-8" style={{ background: '#FFFFFFB5', borderColor: '#E1E8D2' }} data-testid="display-goal">
            <div className="text-lg font-semibold" style={{ color: '#213D32' }}>
              {goal ? String(goal.xy_cg_name) : '暂无进行中的目标'}
            </div>
            {goal && (
              <>
                <div className="mt-4 flex items-baseline gap-2">
                  <span className="text-5xl font-bold" style={{ color: '#35745B' }} data-testid="display-progress">{progress}</span>
                  <span className="text-base" style={{ color: '#91A17A' }}>/ {target} 能量</span>
                </div>
                <div className="mt-4 h-3 overflow-hidden rounded-pill" style={{ background: '#E3EAD6' }}>
                  <div className="h-full rounded-pill transition-all duration-700" style={{ width: `${pct}%`, background: '#93B275' }} />
                </div>
                <p className="mt-2 text-xs" style={{ color: '#91A17A' }}>每一次公开表扬都会为全班目标添一份能量(冲正已自动纠正)</p>
              </>
            )}
          </div>

          {/* recent praise */}
          <div className="rounded-card-lg border p-6" style={{ background: '#FFFFFFB5', borderColor: '#E1E8D2' }} data-testid="display-praise">
            <div className="mb-3 text-sm font-semibold" style={{ color: '#213D32' }}>最近的表扬</div>
            <div className="space-y-3">
              {praise.slice(0, 6).map((p) => (
                <div key={String(p.pid)} className="flex items-center gap-3 border-b pb-3 text-sm last:border-0 last:pb-0" style={{ borderColor: '#EEF1E6' }}>
                  <span className="grid h-8 w-8 shrink-0 place-items-center rounded-[10px]" style={{ background: '#EEF3E4' }}>🌟</span>
                  <div className="min-w-0">
                    <div className="truncate font-medium" style={{ color: '#213D32' }}>
                      {String(p.xy_ev_rule_name)} {p.xy_ev_target_type === 'class' ? '· 全班' : p.xy_ev_target_type === 'group' ? '· 小组' : ''} +{String(p.xy_ev_score)}
                    </div>
                    <div className="text-xs" style={{ color: '#9AA88C' }}>{String(p.xy_ev_target_count ?? '')} 位同学 · {String(p.xy_ev_occurred_at || '').slice(0, 16).replace('T', ' ')}</div>
                  </div>
                </div>
              ))}
              {praise.length === 0 && <div className="text-sm" style={{ color: '#9AA88C' }}>还没有记录,今天也要加油哦</div>}
            </div>
          </div>
        </div>

        {/* recent deductions (PRD 8.3 口径变更: 扣分记录大屏公开, reason 必填留痕) */}
        {deductions.length > 0 && (
          <div className="mt-8 rounded-card-lg border p-6" style={{ background: '#FFF9F5B5', borderColor: '#F0D2D0' }} data-testid="display-deduct">
            <div className="mb-3 text-sm font-semibold" style={{ color: '#8C3A36' }}>扣分记录 · 共同改进</div>
            <div className="space-y-3">
              {deductions.slice(0, 6).map((d) => (
                <div key={String(d.pid)} className="flex items-center gap-3 border-b pb-3 text-sm last:border-0 last:pb-0" style={{ borderColor: '#F6E8E6' }}>
                  <span className="grid h-8 w-8 shrink-0 place-items-center rounded-[10px]" style={{ background: '#FBEFEE' }}>📝</span>
                  <div className="min-w-0">
                    <div className="truncate font-medium" style={{ color: '#213D32' }}>
                      {String(d.xy_ev_rule_name)} {d.xy_ev_target_type === 'class' ? '· 全班' : d.xy_ev_target_type === 'group' ? '· 小组' : ''} {String(d.xy_ev_score)}
                    </div>
                    <div className="text-xs" style={{ color: '#B08583' }}>{String(d.xy_ev_reason || '')} · {String(d.xy_ev_target_count ?? '')} 位同学 · {String(d.xy_ev_occurred_at || '').slice(0, 16).replace('T', ' ')}</div>
                  </div>
                </div>
              ))}
            </div>
          </div>
        )}

        {/* pet parade */}
        <div className="mt-8 grid grid-cols-3 gap-4 sm:grid-cols-6" data-testid="display-pets">
          {pets.map((p) => (
            <ParadePet key={String(p.pid)} pet={p} />
          ))}
          {pets.length === 0 && (
            <div className="col-span-full rounded-card border p-6 text-center text-sm" style={{ borderColor: '#E1E8D2', color: '#8D9D7C' }}>
              伙伴们还在等小朋友认领~
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

function ParadePet({ pet }: { pet: XyRow }) {
  // Each parade pet resolves its own stage-aware asset (fallback chain inside).
  const [student, setStudent] = useState<XyRow | null>(null);
  const [species, setSpecies] = useState<XyRow | null>(null);
  useEffect(() => {
    (async () => {
      setStudent(await xyGet('xy_student', String(pet.xy_pi_student)));
      setSpecies(await xyGet('xy_pet_species', String(pet.xy_pi_species)));
    })();
  }, [pet.pid]);
  const { assetUrl } = usePetVisual(student, pet);
  return (
    <div className="rounded-card border p-3 text-center" style={{ background: '#FFFFFFB5', borderColor: '#E1E8D2' }}>
      <div className="mx-auto w-fit">
        <PetAvatar assetUrl={assetUrl} speciesCode={String(species?.xy_ps_code || '')} size={96} />
      </div>
      <div className="mt-2 truncate text-xs font-semibold" style={{ color: '#213D32' }}>{String(pet.xy_pi_nickname || '待命名')}</div>
      <div className="text-[11px]" style={{ color: '#8D9D7C' }}>Lv.{Number(student?.xy_stu_level ?? 1)} {SPECIES_EMOJI[String(species?.xy_ps_code)] || ''}</div>
    </div>
  );
}
