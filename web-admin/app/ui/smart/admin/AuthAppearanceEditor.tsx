import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react';
import { createPortal } from 'react-dom';
import { useI18n } from '~/contexts/I18nContext';
import { useRootLoaderData } from '~/root-data';
import { COMMUNITY_BRANDING } from '~/config/branding';
import { resolveAuthAppearance, resolveAuthContent, type AuthAppearance, type AuthContentField, type AuthLocale } from '~/config/auth-appearance';
import { get, put, post } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';
import AuthAppearanceShell from '~/auth/AuthAppearanceShell';
import appearanceCss from '~/auth/auth-appearance.css?inline';

interface Revision { version: number; action: string; actorId: number; createdAt: string }
interface AppearanceView { version: number; publishedVersion: number; draft: AuthAppearance | null; published: AuthAppearance | null; history: Revision[] }
const INITIAL: AuthAppearance = { version: 1, template: 'split', defaultLocale: 'zh-CN' };
const CONTENT: AuthContentField[] = ['badge', 'headline', 'headlineEm', 'lead', 'features', 'loginTitle', 'loginLead', 'mobileLead'];
const CONTENT_LABELS: Record<AuthContentField, [string, string]> = { badge: ['品牌标签', 'Badge'], headline: ['主标题', 'Headline'], headlineEm: ['强调文字', 'Highlighted text'], lead: ['品牌介绍', 'Introduction'], features: ['卖点', 'Features'], loginTitle: ['登录标题', 'Login title'], loginLead: ['登录说明', 'Login description'], mobileLead: ['手机简短介绍', 'Mobile introduction'] };
const CONTROL = 'w-full rounded-control border border-border-strong bg-panel px-3 py-2 text-text focus:outline-none focus:ring-2 focus:ring-accent-weak';
const BUTTON = 'rounded-control border border-border-strong bg-panel px-3 py-2 text-sm text-text disabled:opacity-50';

/** Typed custom block hosted by the administration DSL page. */
export function AuthAppearanceEditor() {
  const { t, locale } = useI18n();
  const zh = locale.startsWith('zh');
  const label = (key: string, cn: string, en: string) => t(`auth.appearance.${key}`, undefined, zh ? cn : en);
  const explainError = (cause: unknown) => {
    const message = cause instanceof Error ? cause.message : String(cause);
    const messages: Record<string, string> = {
      '$i18n:auth.appearance.versionConflict': label('versionConflict', '配置已被其他管理员更新。你的修改仍保留，请重新加载后再编辑。', 'Another administrator updated the configuration. Your edits are retained; reload before editing again.'),
      '$i18n:auth.appearance.commercialRequired': label('commercialRequired', '需要启用有效的商业品牌配置。', 'Valid commercial branding must be enabled.'),
      '$i18n:auth.appearance.draftRequired': label('draftRequired', '请先保存草稿。', 'Save a draft first.'),
      '$i18n:auth.appearance.releaseNotFound': label('releaseNotFound', '未找到所选历史发布版本，请重新加载。', 'The selected release was not found. Reload the history.'),
      '$i18n:auth.appearance.assetMissing': label('assetMissing', '所选图片不存在，请重新上传后发布。', 'An image is missing. Upload it again before publishing.'),
    };
    return messages[message] ?? (message.startsWith('$i18n:') ? label('invalidConfig', '配置尚不完整，请检查文案和图片。', 'Configuration is incomplete. Check the copy and images.') : message);
  };
  const branding = useRootLoaderData()?.branding ?? COMMUNITY_BRANDING;
  const [view, setView] = useState<AppearanceView>();
  const [draft, setDraft] = useState<AuthAppearance>(INITIAL);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [dirty, setDirty] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [device, setDevice] = useState<'desktop' | 'mobile'>('desktop');
  const [previewTheme, setPreviewTheme] = useState<'light' | 'dark'>('light');
  const [previewPage, setPreviewPage] = useState<'login' | 'signup' | 'recovery'>('login');
  const [rollbackVersion, setRollbackVersion] = useState('');
  const [confirm, setConfirm] = useState<'publish' | 'rollback' | 'reload' | null>(null);
  const accept = useCallback((next: AppearanceView) => {
    setView(next);
    setDraft(next.draft ?? next.published ?? branding.authAppearance ?? INITIAL);
    setDirty(false);
    setError('');
  }, [branding.authAppearance]);
  const load = useCallback(async () => {
    setLoading(true); setError('');
    try {
      const result = await get<AppearanceView>('/api/admin/auth-appearance');
      if (!ResultHelper.isSuccess(result) || !result.data) throw new Error(result.message);
      accept(result.data);
    } catch (cause) { setError(explainError(cause)); }
    finally { setLoading(false); }
  }, [accept]);
  useEffect(() => { void load(); }, [load]);
  const change = (next: AuthAppearance) => { setDraft(next); setDirty(true); setNotice(''); setError(''); };
  const mutate = async (operation: 'save' | 'publish' | 'rollback') => {
    if (!view || busy) return;
    setBusy(true); setError(''); setNotice('');
    try {
      let appearance: AuthAppearance | undefined;
      if (operation === 'save') {
        try { appearance = resolveAuthAppearance(draft); }
        catch { throw new Error(label('invalidConfig', '配置尚不完整。请检查默认语言文案、图片地址和背景模板所需图片。', 'Configuration is incomplete. Check default-language copy, image URLs, and the background image required by the background template.')); }
      }
      const result = operation === 'save'
        ? await put<AppearanceView>('/api/admin/auth-appearance/draft', { expectedVersion: view.version, appearance })
        : await post<AppearanceView>(`/api/admin/auth-appearance/${operation}`, { expectedVersion: view.version, ...(operation === 'rollback' ? { targetVersion: Number(rollbackVersion) } : {}) });
      if (!ResultHelper.isSuccess(result) || !result.data) throw new Error(result.message);
      accept(result.data);
      setNotice(label(operation === 'save' ? 'saved' : 'released', operation === 'save' ? '草稿已保存，线上页面未改变。' : '已发布。新打开的认证页面将使用此版本。', operation === 'save' ? 'Draft saved. The live appearance has not changed.' : 'Published. Newly opened authentication pages will use this version.'));
      setConfirm(null);
    } catch (cause) { setError(explainError(cause)); }
    finally { setBusy(false); }
  };
  useEffect(() => {
    const preventLoss = (event: BeforeUnloadEvent) => {
      if (dirty) { event.preventDefault(); event.returnValue = ''; }
    };
    window.addEventListener('beforeunload', preventLoss);
    return () => window.removeEventListener('beforeunload', preventLoss);
  }, [dirty]);
  const upload = async (key: 'logoUrl' | 'darkLogoUrl' | 'heroUrl' | 'darkHeroUrl' | 'backgroundUrl' | 'darkBackgroundUrl', file?: File) => {
    if (!file || busy) return;
    setError(''); setBusy(true);
    try {
      if (file.size > 5 * 1024 * 1024 || !['image/png', 'image/jpeg', 'image/webp'].includes(file.type)) {
        throw new Error(label('uploadHint', '上传 PNG、JPEG 或 WebP，最大 5 MB。', 'Upload PNG, JPEG, or WebP, up to 5 MB.'));
      }
      const body = new FormData(); body.append('file', file);
      const response = await fetch('/api/admin/auth-appearance/assets', { method: 'POST', body });
      const result = await response.json();
      if (!response.ok || !ResultHelper.isSuccess(result) || typeof result.data?.url !== 'string') throw new Error(result.message || label('uploadFailed', '图片上传失败。', 'Image upload failed.'));
      change({ ...draft, images: { ...(draft.images ?? { mode: 'none' }), [key]: result.data.url } });
    } catch (cause) { setError(cause instanceof Error ? cause.message : String(cause)); }
    finally { setBusy(false); }
  };
  const theme = draft.theme ?? {};
  const images = draft.images ?? { mode: 'none' as const };
  const field = (title: string, control: ReactNode, hint?: string) => <label key={title} className="block space-y-1"><span className="text-sm font-medium text-text">{title}</span>{control}{hint && <span className="block text-xs text-text-2">{hint}</span>}</label>;
  const section = (title: string, children: ReactNode) => <fieldset className="space-y-4 rounded-card border border-border bg-panel p-4"><legend className="px-1 font-semibold text-text">{title}</legend>{children}</fieldset>;
  if (loading) return <div role="status" className="p-6 text-text-2">{label('loading', '正在读取外观配置…', 'Loading appearance…')}</div>;
  if (!view) return <div role="alert" className="space-y-3 p-6"><p>{error}</p><button className={BUTTON} onClick={() => void load()}>{label('reload', '重新加载', 'Reload')}</button></div>;

  return <div className="space-y-5" data-testid="auth-appearance-editor">
    <header className="flex flex-wrap items-center justify-between gap-3"><div><h1 className="text-xl font-semibold text-text">{label('title', '品牌与登录外观', 'Brand and authentication appearance')}</h1><p className="mt-1 text-sm text-text-2">{label('deploymentScope', '此配置影响整个部署的认证页面。登录方式由登录渠道管理维护。', 'These settings affect authentication across this deployment. Login channels are managed separately.')}</p></div><div className="flex gap-2"><button className={BUTTON} disabled={busy} onClick={() => dirty ? setConfirm('reload') : void load()}>{label('reload', '重新加载', 'Reload')}</button><button className={BUTTON} disabled={busy || (!dirty && !!view.draft)} onClick={() => void mutate('save')}>{label('save', '保存草稿', 'Save draft')}</button><button className={`${BUTTON.replace('bg-panel', 'bg-accent')} text-white`} disabled={busy || dirty || !view.draft} onClick={() => setConfirm('publish')}>{label('publish', '发布', 'Publish')}</button></div></header>
    {error && <div role="alert" className="rounded-card border border-status-red p-3 text-status-red">{error}</div>}
    {notice && <p role="status" className="text-sm text-status-green">{notice}</p>}
    <p className="text-sm text-text-2">{label('publishedVersion', '线上版本', 'Published version')}：{view.publishedVersion} · {dirty ? label('unsaved', '有未保存修改', 'Unsaved changes') : view.draft ? label('savedState', '草稿已同步', 'Draft synchronized') : label('initialDraft', '草稿尚未保存', 'Draft has not been saved')}</p>
    <div className="grid gap-6 xl:grid-cols-2"><div className="space-y-5">
      {section(label('style', '模板与风格', 'Template and style'), <>
        {field(label('template', '布局模板', 'Layout template'), <select aria-label={label('template', '布局模板', 'Layout template')} className={CONTROL} value={draft.template} onChange={e => change({ ...draft, template: e.target.value as AuthAppearance['template'] })}><option value="split">{label('split', '品牌分栏', 'Brand split')}</option><option value="centered">{label('centered', '简洁居中', 'Centered')}</option><option value="background">{label('background', '场景背景', 'Scene background')}</option></select>)}
        {field(label('defaultLanguage', '默认语言', 'Default language'), <select className={CONTROL} value={draft.defaultLocale} onChange={e => change({ ...draft, defaultLocale: e.target.value as AuthLocale })}><option value="zh-CN">简体中文</option><option value="en-US">English</option></select>)}
        {field(label('accent', '品牌色', 'Brand color'), <input className={CONTROL} type="color" value={theme.accent ?? '#35745b'} onChange={e => change({ ...draft, theme: { ...theme, accent: e.target.value } })} />)}
        {field(label('backgroundColor', '背景色', 'Background color'), <input className={CONTROL} type="color" value={theme.background ?? '#f6f8f3'} onChange={e => change({ ...draft, theme: { ...theme, background: e.target.value } })} />)}
        {field(label('radius', '圆角', 'Corners'), <select className={CONTROL} value={theme.radius ?? 'soft'} onChange={e => change({ ...draft, theme: { ...theme, radius: e.target.value as NonNullable<AuthAppearance['theme']>['radius'] } })}><option value="square">{label('square', '小圆角', 'Compact')}</option><option value="soft">{label('soft', '柔和', 'Soft')}</option><option value="rounded">{label('rounded', '圆润', 'Rounded')}</option></select>)}
        {field(label('themeMode', '认证页主题', 'Authentication theme'), <select className={CONTROL} value={theme.mode ?? 'auto'} onChange={e => change({ ...draft, theme: { ...theme, mode: e.target.value as NonNullable<AuthAppearance['theme']>['mode'] } })}><option value="auto">{label('auto', '跟随用户主题', 'Follow user theme')}</option><option value="light">{label('light', '浅色', 'Light')}</option><option value="dark">{label('dark', '深色', 'Dark')}</option></select>)}
        {field(label('brandPosition', '品牌区位置', 'Brand position'), <select className={CONTROL} value={draft.brandPosition ?? 'left'} onChange={e => change({ ...draft, brandPosition: e.target.value as AuthAppearance['brandPosition'] })}><option value="left">{label('left', '左侧', 'Left')}</option><option value="right">{label('right', '右侧', 'Right')}</option></select>)}
        {field(label('cardPosition', '背景模板卡片位置', 'Background card position'), <select className={CONTROL} value={draft.cardPosition ?? 'center'} onChange={e => change({ ...draft, cardPosition: e.target.value as AuthAppearance['cardPosition'] })}>{(['left', 'center', 'right'] as const).map(v => <option key={v} value={v}>{label(v, v === 'left' ? '左侧' : v === 'right' ? '右侧' : '居中', v)}</option>)}</select>)}
      </>)}
      {section(label('copy', '品牌与登录文案', 'Brand and login copy'), <>{CONTENT.map(key => {
        const value = draft.content?.[key];
        const title = label(key, ...CONTENT_LABELS[key]);
        const values = value?.mode === 'custom' ? value.values : {};
        return <div key={key} className="space-y-2">{field(title, <select aria-label={`${title} ${label('mode', '展示方式', 'display mode')}`} className={CONTROL} value={value?.mode ?? 'default'} onChange={e => {
          const mode = e.target.value as 'custom' | 'default' | 'hidden';
          const next = mode === 'custom' ? { mode, values: { [draft.defaultLocale]: key === 'features' ? [''] : '' } } : { mode };
          change({ ...draft, content: { ...draft.content, [key]: next } as AuthAppearance['content'] });
        }}><option value="default">{label('default', '使用默认', 'Use default')}</option><option value="custom">{label('custom', '自定义', 'Custom')}</option><option value="hidden">{label('hidden', '隐藏', 'Hidden')}</option></select>)}
        {value?.mode === 'custom' && (['zh-CN', 'en-US'] as const).map(lang => <label className="block space-y-1" key={lang}><span className="text-xs text-text-2">{lang === 'zh-CN' ? '简体中文' : 'English'}</span><textarea aria-label={`${title} ${lang}`} className={CONTROL} rows={key === 'features' ? 4 : 2} value={Array.isArray(values[lang]) ? (values[lang] as string[]).join('\n') : (values[lang] as string | undefined) ?? ''} placeholder={key === 'features' ? label('featureHint', '每行一条卖点，建议 1–3 条', 'One feature per line; 1–3 recommended') : label('copyHint', '填写面向用户的简短文案', 'Enter concise copy for users')} onChange={e => {
          const nextValues = { ...values };
          if (!e.target.value && lang !== draft.defaultLocale) delete nextValues[lang];
          else nextValues[lang] = key === 'features' ? e.target.value.split('\n') : e.target.value;
          change({ ...draft, content: { ...draft.content, [key]: { mode: 'custom', values: nextValues } } as AuthAppearance['content'] });
        }} /></label>)}
        </div>;
      })}</>)}
      {section(label('images', '图片与帮助', 'Images and help'), <>
        {field(label('imageMode', '图片用途', 'Image mode'), <select className={CONTROL} value={images.mode} onChange={e => change({ ...draft, images: { ...images, mode: e.target.value as NonNullable<AuthAppearance['images']>['mode'] } })}><option value="none">{label('none', '无图片', 'No image')}</option><option value="illustration">{label('illustration', '品牌插图', 'Illustration')}</option><option value="background">{label('backgroundImage', '场景背景', 'Background')}</option></select>)}
        {(['logoUrl', 'darkLogoUrl', 'heroUrl', 'darkHeroUrl', 'backgroundUrl', 'darkBackgroundUrl'] as const).map(key => field(label(key, ({ logoUrl: 'Logo', darkLogoUrl: '深色 Logo', heroUrl: '插图', darkHeroUrl: '深色插图', backgroundUrl: '背景图片', darkBackgroundUrl: '深色背景图片' })[key], ({ logoUrl: 'Logo', darkLogoUrl: 'Dark logo', heroUrl: 'Illustration', darkHeroUrl: 'Dark illustration', backgroundUrl: 'Background image', darkBackgroundUrl: 'Dark background image' })[key]), <div className="space-y-2"><input aria-label={`${key} URL`} className={CONTROL} value={images[key] ?? ''} placeholder="/brand/image.webp" onChange={e => { const next = { ...images }; if (e.target.value) next[key] = e.target.value; else delete next[key]; change({ ...draft, images: next }); }} /><input type="file" aria-label={`${key} ${label('upload', '上传图片', 'Upload image')}`} accept="image/png,image/jpeg,image/webp" disabled={busy} onChange={e => { void upload(key, e.target.files?.[0]); e.target.value = ''; }} /><p className="text-xs text-text-2">{label('uploadHint', '上传 PNG、JPEG 或 WebP，最大 5 MB。', 'Upload PNG, JPEG, or WebP, up to 5 MB.')}</p></div>, label('anonymousAsset', '使用无需登录即可访问的图片地址。', 'Use an image URL accessible without signing in.')))}
        {(['focusX', 'focusY', 'mobileFocusX', 'mobileFocusY'] as const).map(key => field(label(key, ({ focusX: '桌面水平焦点', focusY: '桌面垂直焦点', mobileFocusX: '手机水平焦点', mobileFocusY: '手机垂直焦点' })[key], ({ focusX: 'Desktop horizontal focus', focusY: 'Desktop vertical focus', mobileFocusX: 'Mobile horizontal focus', mobileFocusY: 'Mobile vertical focus' })[key]), <input className={CONTROL} type="range" min="0" max="100" value={images[key] ?? 50} onChange={e => change({ ...draft, images: { ...images, [key]: Number(e.target.value) } })} />))}
        {field(label('overlay', '背景遮罩', 'Background overlay'), <input className={CONTROL} type="range" min="0" max="0.8" step="0.05" value={images.overlay ?? 0.35} onChange={e => change({ ...draft, images: { ...images, overlay: Number(e.target.value) } })} />)}
        {field(label('helpUrl', '帮助链接', 'Help link'), <input className={CONTROL} value={draft.helpUrl ?? ''} placeholder="/help" onChange={e => { const next = { ...draft }; if (e.target.value) next.helpUrl = e.target.value; else delete next.helpUrl; change(next); }} />)}
      </>)}
    </div><div className="space-y-4 xl:sticky xl:top-6 xl:self-start">
      <div className="flex flex-wrap gap-2">{field(label('device', '预览设备', 'Preview device'), <select className={CONTROL} value={device} onChange={e => setDevice(e.target.value as typeof device)}><option value="desktop">{label('desktop', '桌面', 'Desktop')}</option><option value="mobile">{label('mobile', '手机', 'Mobile')}</option></select>)}{field(label('previewTheme', '预览主题', 'Preview theme'), <select className={CONTROL} value={previewTheme} onChange={e => setPreviewTheme(e.target.value as typeof previewTheme)}><option value="light">{label('light', '浅色', 'Light')}</option><option value="dark">{label('dark', '深色', 'Dark')}</option></select>)}{field(label('previewPage', '预览页面', 'Preview page'), <select className={CONTROL} value={previewPage} onChange={e => setPreviewPage(e.target.value as typeof previewPage)}><option value="login">{label('login', '登录', 'Login')}</option><option value="signup">{label('signup', '注册', 'Registration')}</option><option value="recovery">{label('recovery', '恢复帮助', 'Recovery help')}</option></select>)}</div>
      <p className="text-xs text-text-2">{label('previewNote', '预览用于查看外观，表单不可提交；登录方式以认证策略为准。', 'Preview shows appearance only. Forms cannot submit; authentication policy controls login methods.')}</p>
      <AppearancePreview title={label('preview', '认证页预览', 'Authentication preview')} width={device === 'mobile' ? 390 : 1280}>
        <AuthAppearanceShell branding={branding} appearance={draft} previewTheme={previewTheme}><fieldset disabled>
          <h1>{previewPage === 'login' ? resolveAuthContent(draft.content?.loginTitle, locale, draft.defaultLocale, label('previewLoginTitle', '欢迎回来', 'Welcome back')) : previewPage === 'signup' ? label('signupTitle', '创建账号', 'Create an account') : label('resetUnavailable', '暂不支持自助重置密码', 'Password reset unavailable')}</h1>
          {previewPage === 'recovery' ? <p>{label('resetHelp', '请联系管理员设置或重置密码。', 'Contact your tenant administrator to set or reset your password.')}</p> : <><p>{resolveAuthContent(draft.content?.loginLead, locale, draft.defaultLocale, label('previewLead', '登录以继续使用工作台', 'Sign in to continue to your workspace'))}</p><label>{label('account', '账号或邮箱', 'Account or email')}<input className={CONTROL} /></label><label>{label('password', '密码', 'Password')}<input type="password" className={CONTROL} /></label><button type="submit" className={BUTTON}>{previewPage === 'signup' ? label('signup', '注册', 'Registration') : label('login', '登录', 'Login')}</button></>}
        </fieldset></AuthAppearanceShell>
      </AppearancePreview>
      {section(label('history', '发布历史', 'Release history'), <><p className="text-xs text-text-2">{label('rollbackHint', '回滚会恢复该版本外观，并替换当前草稿。', 'Rollback restores the release and replaces the current draft.')}</p><select className={CONTROL} aria-label={label('rollbackTarget', '回滚目标', 'Rollback target')} value={rollbackVersion} onChange={e => setRollbackVersion(e.target.value)}><option value="">{label('selectRelease', '选择历史版本', 'Select a release')}</option>{view.history.map(release => <option key={release.version} value={release.version}>{release.version} · {new Date(release.createdAt).toLocaleString(locale)}</option>)}</select><button className={BUTTON} disabled={busy || !rollbackVersion} onClick={() => setConfirm('rollback')}>{label('rollback', '回滚', 'Roll back')}</button></>)}
      {confirm && <div role="alertdialog" aria-label={label('confirmRelease', '确认外观发布', 'Confirm appearance release')} className="space-y-3 rounded-card border border-border-strong bg-panel p-4"><p>{confirm === 'reload' ? label('discardConfirm', '重新加载会丢弃未保存修改。', 'Reloading discards unsaved changes.') : confirm === 'publish' ? label('publishConfirm', '发布后，整个部署的认证页面将使用当前草稿。', 'Publishing applies the current draft to authentication across this deployment.') : label('rollbackConfirm', '将恢复所选历史版本并替换当前草稿。', 'Restore the selected release and replace the current draft?')}</p><button className={BUTTON} disabled={busy} onClick={() => { if (confirm === 'reload') { setConfirm(null); void load(); } else void mutate(confirm); }}>{label('confirm', '确认', 'Confirm')}</button><button className={BUTTON} disabled={busy} onClick={() => setConfirm(null)}>{label('cancel', '取消', 'Cancel')}</button></div>}
    </div></div>
  </div>;
}

function AppearancePreview({ children, title, width }: { children: ReactNode; title: string; width: number }) {
  const [body, setBody] = useState<HTMLElement>();
  const container = useRef<HTMLDivElement>(null);
  const [availableWidth, setAvailableWidth] = useState(width);
  useEffect(() => {
    const element = container.current;
    if (!element) return;
    const measure = () => { if (element.clientWidth > 0) setAvailableWidth(element.clientWidth); };
    measure();
    if (typeof ResizeObserver === 'undefined') return;
    const observer = new ResizeObserver(measure);
    observer.observe(element);
    return () => observer.disconnect();
  }, [width]);
  const scale = Math.min(1, availableWidth / width);
  const srcDoc = `<!doctype html><html><head><style>html,body{margin:0;font-family:system-ui}*{box-sizing:border-box}fieldset{border:0;padding:0}label{display:block;margin-block:1rem}input{display:block;width:100%;padding:0.75rem;border:1px solid #ccc}h1{font-size:1.7rem}button{padding:0.75rem;width:100%}${appearanceCss}</style></head><body></body></html>`;
  return <div ref={container} data-testid="auth-appearance-preview" className="relative overflow-hidden rounded-card border border-border bg-panel" style={{ height: 900 * scale }}><iframe title={title} srcDoc={srcDoc} style={{ position: 'absolute', width, height: 900, transform: `scale(${scale})`, transformOrigin: 'top left', border: 0 }} onLoad={e => setBody(e.currentTarget.contentDocument?.body)} />{body && createPortal(children, body)}</div>;
}

export default AuthAppearanceEditor;
