import React, { useEffect, useState } from 'react';
import { Navigate, useNavigate, useParams } from 'react-router';
import { useAuth } from '~/contexts/AuthContext';
import { xyList, xyGet, xyExec } from './eduApi';

/**
 * Fengyun roster import (FR-017, PC-only per owner decision) — four steps over
 * the two-phase engine command xy:submit_ij:
 *   1 上传   file (CSV UTF-8 / Xlsx, <=2MB, <=500 rows) -> phase upload
 *   2 预览   counts (新增/跳过/问题) + problem list from the server validation
 *   3 确认   phase confirm applies the frozen valid set atomically
 *   4 结果   counts + job code (audit trail in xy_import_job)
 *
 * Excel import is PC-only by decision; the mini-program never imports.
 */
type Step = 'upload' | 'preview' | 'result';

interface Preview {
  jobPid: string;
  totalRows: number;
  validRows: number;
  skipCount: number;
  issueCount: number;
  problems: string[];
}

export function selectImportClass(
  classes: Array<{ pid: string; name: string }>,
  requestedPid: string,
) {
  return classes.find((item) => item.pid === requestedPid) || classes[0];
}

export default function RosterImport() {
  const { isAuthenticated } = useAuth();
  const navigate = useNavigate();
  const params = useParams();
  const [step, setStep] = useState<Step>('upload');
  const [classes, setClasses] = useState<Array<{ pid: string; name: string }>>([]);
  const [classPid, setClassPid] = useState('');
  const [fileName, setFileName] = useState('');
  const [contentB64, setContentB64] = useState('');
  const [preview, setPreview] = useState<Preview | null>(null);
  const [result, setResult] = useState<{
    created: number;
    skipped: number;
    jobCode: string;
  } | null>(null);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    let cancelled = false;
    xyList('xy_classroom')
      .then((rows) => {
        const active = rows
          .filter((c) => c.xy_cls_status === 'active')
          .map((c) => ({ pid: String(c.pid), name: String(c.xy_cls_alias || c.xy_cls_name) }));
        if (cancelled) return;
        setClasses(active);
        const requested = String(params.classPid || '');
        const selected = selectImportClass(active, requested);
        if (selected) setClassPid(selected.pid);
      })
      .catch(() => {});
    return () => {
      cancelled = true;
    };
  }, [params.classPid]);

  if (!isAuthenticated) return <Navigate to="/login" replace />;

  const onFile = (files: FileList | null) => {
    const file = files && files[0];
    if (!file) return;
    setError('');
    setFileName(file.name);
    if (file.size > 2 * 1024 * 1024) {
      setError('文件超过 2MB 限制');
      setContentB64('');
      return;
    }
    const reader = new FileReader();
    reader.onload = () => setContentB64(String(reader.result || '').split(',')[1] || '');
    reader.onerror = () => setError('文件读取失败');
    reader.readAsDataURL(file);
  };

  const doUpload = async () => {
    if (!classPid || !contentB64 || !fileName) {
      setError('请选择班级和文件');
      return;
    }
    setBusy(true);
    setError('');
    try {
      const r = await xyExec('xy:submit_ij', {
        class: classPid,
        filename: fileName,
        contentBase64: contentB64,
        phase: 'upload',
      });
      if (!r.ok) throw new Error(r.message || '上传失败');
      const d = r.data as unknown as {
        jobPid?: string;
        totalRows?: number;
        validRows?: number;
        skipCount?: number;
        issueCount?: number;
        problems?: string[];
      };
      setPreview({
        jobPid: String(d.jobPid || ''),
        totalRows: Number(d.totalRows || 0),
        validRows: Number(d.validRows || 0),
        skipCount: Number(d.skipCount || 0),
        issueCount: Number(d.issueCount || 0),
        problems: d.problems || [],
      });
      setStep('preview');
    } catch (e) {
      setError((e as Error).message);
    }
    setBusy(false);
  };

  const doConfirm = async () => {
    if (!preview) return;
    setBusy(true);
    setError('');
    try {
      const r = await xyExec('xy:submit_ij', { phase: 'confirm', jobId: preview.jobPid });
      if (!r.ok) throw new Error(r.message || '确认失败');
      const d = r.data as unknown as { created?: number; skipped?: number };
      let jobCode = preview.jobPid;
      try {
        const job = await xyGet('xy_import_job', preview.jobPid);
        if (job) jobCode = String(job.xy_ij_code || jobCode);
      } catch {
        /* audit lookup is best-effort */
      }
      setResult({ created: Number(d.created || 0), skipped: Number(d.skipped || 0), jobCode });
      setStep('result');
    } catch (e) {
      setError((e as Error).message);
    }
    setBusy(false);
  };

  return (
    <div
      className="min-h-screen"
      style={{ background: 'var(--color-bg)' }}
      data-testid="roster-import"
    >
      <div className="mx-auto max-w-3xl px-6 pt-6 pb-24">
        <div className="mb-2 text-sm font-medium" style={{ color: 'var(--color-text-3)' }}>
          蜂耘 · 名册导入（PC 专用）
        </div>
        <div className="h1 mb-1" style={{ fontSize: 40, fontWeight: 650 }}>
          导入班级名册
        </div>
        <div className="muted mb-5">
          支持 .xlsx 与 UTF-8 .csv,≤2MB、≤500
          行;模板四列:学号,姓名,小组,座位号。学号按文本保留前导零。
        </div>

        {step === 'upload' && (
          <div className="card" data-testid="import-step-upload">
            <div className="mb-3 font-semibold">第一步 · 选择班级与文件</div>
            <select
              className="mb-4 w-full rounded-[var(--radius-control)] border px-3 py-3"
              style={{ borderColor: 'var(--color-border)', background: 'var(--color-panel)' }}
              value={classPid}
              onChange={(e) => setClassPid(e.target.value)}
              data-testid="import-class"
            >
              {classes.map((c) => (
                <option key={c.pid} value={c.pid}>
                  {c.name}
                </option>
              ))}
            </select>
            <input
              type="file"
              accept=".csv,.xlsx"
              data-testid="import-file"
              onChange={(e) => onFile(e.target.files)}
              className="block w-full rounded-[var(--radius-control)] border px-3 py-3 text-sm"
              style={{ borderColor: 'var(--color-border)' }}
            />
            <button
              className="btn-primary mt-4 rounded-[var(--radius-control)] px-5 py-3 font-semibold text-white"
              style={{ background: 'var(--color-accent)' }}
              disabled={busy || !contentB64 || !classPid}
              onClick={doUpload}
              data-testid="import-upload-btn"
            >
              解析并预览
            </button>
            {error && (
              <div className="mt-3 text-sm" style={{ color: '#A76251' }} role="alert">
                {error}
              </div>
            )}
          </div>
        )}

        {step === 'preview' && preview && (
          <div className="card" data-testid="import-step-preview">
            <div className="mb-3 font-semibold">第二步 · 预览确认</div>
            <div className="mb-4 grid grid-cols-4 gap-3 text-center">
              <div
                className="rounded-[var(--radius-card)] border p-3"
                style={{ borderColor: 'var(--color-border)' }}
              >
                <div className="text-xs" style={{ color: 'var(--color-text-3)' }}>
                  总行数
                </div>
                <div className="text-2xl font-bold">{preview.totalRows}</div>
              </div>
              <div
                className="rounded-[var(--radius-card)] border p-3"
                style={{ borderColor: '#A0BB84', background: '#F0F6E6' }}
              >
                <div className="text-xs" style={{ color: '#4C6B3C' }}>
                  可导入
                </div>
                <div
                  className="text-2xl font-bold"
                  style={{ color: '#35745B' }}
                  data-testid="preview-valid"
                >
                  {preview.validRows}
                </div>
              </div>
              <div
                className="rounded-[var(--radius-card)] border p-3"
                style={{ borderColor: 'var(--color-border)' }}
              >
                <div className="text-xs" style={{ color: 'var(--color-text-3)' }}>
                  已存在跳过
                </div>
                <div className="text-2xl font-bold">{preview.skipCount}</div>
              </div>
              <div
                className="rounded-[var(--radius-card)] border p-3"
                style={{
                  borderColor: preview.issueCount ? '#EBD8BD' : 'var(--color-border)',
                  background: preview.issueCount ? '#FFF6ED' : 'transparent',
                }}
              >
                <div className="text-xs" style={{ color: '#B69361' }}>
                  问题行
                </div>
                <div
                  className="text-2xl font-bold"
                  style={{ color: '#B69361' }}
                  data-testid="preview-issues"
                >
                  {preview.issueCount}
                </div>
              </div>
            </div>
            {preview.problems.length > 0 && (
              <ul
                className="mb-4 list-disc rounded-[var(--radius-control)] p-4 pl-8 text-sm"
                style={{ background: '#FFF6ED', color: '#A76251' }}
              >
                {preview.problems.map((p, i) => (
                  <li key={i}>{p}</li>
                ))}
              </ul>
            )}
            <div className="muted mb-4 text-xs">
              确认后按冻结的行集原子导入:新同学建档 0
              余额、待认领;已存在学号跳过;不会覆盖任何积分或历史。
            </div>
            <div className="flex gap-3">
              <button
                className="rounded-[var(--radius-control)] px-4 py-2 text-sm"
                onClick={() => setStep('upload')}
              >
                上一步
              </button>
              <button
                className="rounded-[var(--radius-control)] px-5 py-2 font-semibold text-white"
                style={{ background: 'var(--color-accent)' }}
                disabled={busy || preview.validRows === 0}
                onClick={doConfirm}
                data-testid="import-confirm"
              >
                确认导入 {preview.validRows} 人
              </button>
            </div>
          </div>
        )}

        {step === 'result' && result && (
          <div className="card" data-testid="import-step-result">
            <div className="mb-2 text-lg font-bold" style={{ color: '#35745B' }}>
              导入完成
            </div>
            <div className="text-sm">
              新增 {result.created} 人,跳过(已存在){result.skipped} 人。任务编号{' '}
              <code>{result.jobCode}</code> 已留档。
            </div>
            <div className="mt-4 flex gap-3">
              <button
                className="rounded-[var(--radius-control)] px-4 py-2 text-sm"
                onClick={() => {
                  setStep('upload');
                  setPreview(null);
                  setResult(null);
                  setContentB64('');
                  setFileName('');
                }}
              >
                继续导入
              </button>
              <button
                className="rounded-[var(--radius-control)] border px-4 py-2 text-sm"
                style={{ borderColor: 'var(--color-border)' }}
                onClick={() => navigate('/')}
              >
                返回工作台
              </button>
            </div>
          </div>
        )}

        {error && step !== 'upload' && (
          <div
            className="mt-4 rounded-[var(--radius-control)] px-4 py-3 text-sm"
            style={{ background: '#FFF0EB', color: '#A76251' }}
            role="alert"
          >
            {error}
          </div>
        )}
      </div>
    </div>
  );
}
