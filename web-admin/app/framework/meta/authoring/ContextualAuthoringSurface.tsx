import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router';
import {
  AlertTriangle,
  ChevronRight,
  Eye,
  GitCompare,
  Layers3,
  LockKeyhole,
  MousePointer2,
  PanelLeft,
  PanelRight,
  Plus,
  Settings2,
  ShieldCheck,
  X,
} from 'lucide-react';
import { usePermission } from '~/contexts/AuthContext';
import { getLocalizedText } from '~/routes/_shared/dynamic-route-utils';
import {
  activateAuthoringPreviewGuard,
  AUTHORING_WRITE_BLOCKED_EVENT,
} from '~/shared/services/http-client';
import {
  createAuthoringHandoff,
  loadAuthoringCapabilities,
  openAuthoringSession,
} from './authoringService';
import type {
  AuthoringMode,
  AuthoringNode,
  AuthoringSession,
  CapabilityManifest,
  CapabilityRegistry,
  ContextualAuthoringSurfaceProps,
  PropertyCapability,
} from './types';

type HandoffIntent = 'PAGE_STRUCTURE' | 'NEW_PAGE' | 'MENU_STRUCTURE';

interface ExplainState {
  intent: HandoffIntent;
  title: string;
  reason: string;
  propertyPath?: string;
}

export function ContextualAuthoringSurface({
  schema,
  recordPid,
  children,
}: ContextualAuthoringSurfaceProps) {
  const navigate = useNavigate();
  const canReadDesigner = usePermission('meta.designer.read');
  const canManageDesigner = usePermission('meta.designer.update');
  const canConfigure = canReadDesigner && canManageDesigner;
  const [session, setSession] = useState<AuthoringSession | null>(null);
  const [capabilities, setCapabilities] = useState<CapabilityRegistry | null>(null);
  const [opening, setOpening] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [mode, setMode] = useState<AuthoringMode>('select');
  const [altPressed, setAltPressed] = useState(false);
  const [selectedId, setSelectedId] = useState(schema.id);
  const [outlineOpen, setOutlineOpen] = useState(false);
  const [inspectorOpen, setInspectorOpen] = useState(false);
  const [explain, setExplain] = useState<ExplainState | null>(null);
  const [handoffPending, setHandoffPending] = useState(false);
  const [writeBlocked, setWriteBlocked] = useState(false);
  const runtimeRootRef = useRef<HTMLDivElement | null>(null);
  const entryScrollRef = useRef({ x: 0, y: 0 });

  const rootNode = useMemo(() => buildAuthoringTree(schema), [schema]);
  const nodeIndex = useMemo(() => indexTree(rootNode), [rootNode]);
  const selectedNode = nodeIndex.byId.get(selectedId) ?? rootNode;
  const effectiveMode: AuthoringMode = altPressed
    ? mode === 'select'
      ? 'interact'
      : 'select'
    : mode;
  const manifestByType = useMemo(
    () => new Map(capabilities?.manifests.map((manifest) => [manifest.blockType, manifest]) ?? []),
    [capabilities],
  );

  const enter = useCallback(async () => {
    if (!canConfigure || opening) return;
    setOpening(true);
    setError(null);
    entryScrollRef.current = { x: window.scrollX, y: window.scrollY };
    try {
      const interactionContext = captureInteractionContext(recordPid);
      const [opened, registry] = await Promise.all([
        openAuthoringSession(schema.id, interactionContext),
        loadAuthoringCapabilities(),
      ]);
      setSession(opened);
      setCapabilities(registry);
      setSelectedId(schema.id);
    } catch (enterError) {
      setError(enterError instanceof Error ? enterError.message : '无法进入配置模式');
    } finally {
      setOpening(false);
    }
  }, [canConfigure, opening, recordPid, schema.id]);

  const exit = useCallback(() => {
    setSession(null);
    setCapabilities(null);
    setExplain(null);
    setError(null);
    setWriteBlocked(false);
    setOutlineOpen(false);
    setInspectorOpen(false);
    requestAnimationFrame(() =>
      window.scrollTo(entryScrollRef.current.x, entryScrollRef.current.y),
    );
  }, []);

  useEffect(() => {
    if (!session) return;
    return activateAuthoringPreviewGuard(session.sessionPid);
  }, [session]);

  useEffect(() => {
    if (!session) return;
    const handleBlocked = () => {
      setWriteBlocked(true);
      window.setTimeout(() => setWriteBlocked(false), 4000);
    };
    window.addEventListener(AUTHORING_WRITE_BLOCKED_EVENT, handleBlocked);
    return () => window.removeEventListener(AUTHORING_WRITE_BLOCKED_EVENT, handleBlocked);
  }, [session]);

  useEffect(() => {
    if (!session) return;
    const keyDown = (event: KeyboardEvent) => {
      if (event.key === 'Alt') setAltPressed(true);
      if (event.altKey && event.key === 'ArrowUp') {
        event.preventDefault();
        const parentId = nodeIndex.byId.get(selectedId)?.parentId;
        if (parentId) setSelectedId(parentId);
      }
      if (event.key === 'Escape') {
        if (explain) setExplain(null);
        else if (inspectorOpen) setInspectorOpen(false);
        else if (outlineOpen) setOutlineOpen(false);
        else if (selectedId !== schema.id) setSelectedId(schema.id);
        else exit();
      }
    };
    const keyUp = (event: KeyboardEvent) => {
      if (event.key === 'Alt') setAltPressed(false);
    };
    window.addEventListener('keydown', keyDown);
    window.addEventListener('keyup', keyUp);
    return () => {
      window.removeEventListener('keydown', keyDown);
      window.removeEventListener('keyup', keyUp);
    };
  }, [exit, explain, inspectorOpen, nodeIndex.byId, outlineOpen, schema.id, selectedId, session]);

  useEffect(() => {
    const root = runtimeRootRef.current;
    if (!root || !session) return;
    const selectable = root.querySelectorAll<HTMLElement>(
      '[data-aura-block-id], [data-block-id], [data-authoring-node-id]',
    );
    selectable.forEach((element) => {
      element.classList.add('outline', 'outline-1', 'outline-blue-300', 'outline-offset-2');
      if (effectiveMode === 'select') element.classList.add('cursor-crosshair');
      const sourceId =
        element.dataset.authoringNodeId ||
        element.dataset.auraBlockId ||
        element.dataset.blockId ||
        element.dataset.auraElementId;
      if (sourceId === selectedNode.sourceId) {
        element.classList.remove('outline-1', 'outline-blue-300');
        element.classList.add('outline-2', 'outline-blue-600');
      }
    });
    return () => {
      selectable.forEach((element) =>
        element.classList.remove(
          'outline',
          'outline-1',
          'outline-2',
          'outline-blue-300',
          'outline-blue-600',
          'outline-offset-2',
          'cursor-crosshair',
        ),
      );
    };
  }, [effectiveMode, selectedNode.sourceId, session]);

  const selectNode = useCallback(
    (nodeId: string) => {
      setSelectedId(nodeId);
      setInspectorOpen(true);
      const sourceId = nodeIndex.byId.get(nodeId)?.sourceId;
      if (sourceId) {
        runtimeRootRef.current
          ?.querySelector<HTMLElement>(
            `[data-authoring-node-id="${cssEscape(sourceId)}"], [data-aura-block-id="${cssEscape(sourceId)}"], [data-block-id="${cssEscape(sourceId)}"]`,
          )
          ?.scrollIntoView({ block: 'center', behavior: 'smooth' });
      }
    },
    [nodeIndex.byId],
  );

  const handleRuntimeClick = (event: React.MouseEvent<HTMLDivElement>) => {
    if (effectiveMode === 'select') {
      event.preventDefault();
      event.stopPropagation();
      const element = (event.target as HTMLElement).closest<HTMLElement>(
        '[data-authoring-node-id], [data-aura-block-id], [data-block-id]',
      );
      const sourceId =
        element?.dataset.authoringNodeId || element?.dataset.auraBlockId || element?.dataset.blockId;
      setSelectedId(sourceId ? nodeIndex.bySourceId.get(sourceId)?.id ?? schema.id : schema.id);
      setInspectorOpen(true);
      return;
    }

    if (!isSafePreviewInteraction(event.target as HTMLElement)) {
      event.preventDefault();
      event.stopPropagation();
      setWriteBlocked(true);
      window.setTimeout(() => setWriteBlocked(false), 4000);
    }
  };

  const createHandoff = async () => {
    if (!session || !explain || handoffPending) return;
    setHandoffPending(true);
    setError(null);
    try {
      const handoff = await createAuthoringHandoff(
        session.sessionPid,
        session.revision,
        explain.intent,
        selectedNode.kind === 'page' ? undefined : selectedNode.sourceId,
        explain.propertyPath,
      );
      navigate(`${handoff.targetRoute}?contextId=${encodeURIComponent(handoff.contextId)}`);
    } catch (handoffError) {
      setError(handoffError instanceof Error ? handoffError.message : '无法移交到应用设计中心');
      setExplain(null);
    } finally {
      setHandoffPending(false);
    }
  };

  if (!session) {
    return (
      <div className="relative" data-testid="contextual-authoring-runtime">
        {children}
        {canConfigure ? (
          <button
            type="button"
            onClick={enter}
            disabled={opening}
            className="border-border-strong bg-panel text-text hover:bg-hover fixed bottom-6 right-6 z-30 inline-flex min-h-11 items-center gap-2 rounded-full border px-4 py-2 text-sm font-semibold shadow-lg disabled:cursor-wait disabled:opacity-70"
            data-testid="contextual-authoring-enter"
          >
            <Settings2 className="h-4 w-4" />
            {opening ? '正在进入配置模式…' : '配置此页'}
          </button>
        ) : null}
        {error ? (
          <div
            role="alert"
            className="border-status-red bg-status-red-bg fixed bottom-20 right-6 z-30 max-w-sm rounded-lg border px-4 py-3 text-sm text-red-800 shadow-lg"
          >
            {error}
          </div>
        ) : null}
      </div>
    );
  }

  const breadcrumbs = ancestorChain(selectedNode, nodeIndex.byId);
  const selectedManifest = manifestByType.get(selectedNode.blockType);

  return (
    <section
      className="border-border bg-subtle relative flex min-h-[calc(100vh-4rem)] flex-col overflow-hidden border"
      data-testid="contextual-authoring-surface"
      data-mode={effectiveMode}
    >
      <AuthoringToolbar
        mode={mode}
        temporaryMode={altPressed}
        onModeChange={setMode}
        onOutline={() => setOutlineOpen(true)}
        onInspector={() => setInspectorOpen(true)}
        onNewPage={() =>
          setExplain({
            intent: 'NEW_PAGE',
            title: '在应用设计中心创建页面',
            reason: '新页面会改变页面树、路由和发布资源，不属于当前页面的局部展示调整。',
          })
        }
        onExit={exit}
      />

      {writeBlocked ? (
        <div
          role="status"
          className="border-status-amber bg-status-amber-bg text-status-amber mx-3 mt-3 flex items-center gap-2 rounded-md border px-3 py-2 text-sm"
          data-testid="authoring-write-blocked"
        >
          <LockKeyhole className="h-4 w-4" />
          已拦截真实业务写入；交互预览只保留本地状态。
        </div>
      ) : null}
      {error ? (
        <div role="alert" className="border-status-red bg-status-red-bg mx-3 mt-3 rounded-md border px-3 py-2 text-sm text-red-800">
          {error}
        </div>
      ) : null}

      <div className="relative flex min-h-0 flex-1 overflow-hidden">
        {(outlineOpen || inspectorOpen) && (
          <button
            type="button"
            aria-label="关闭侧栏"
            onClick={() => {
              setOutlineOpen(false);
              setInspectorOpen(false);
            }}
            className="absolute inset-0 z-30 bg-slate-950/20 lg:hidden"
          />
        )}
        <OutlinePanel
          root={rootNode}
          selectedId={selectedNode.id}
          open={outlineOpen}
          onClose={() => setOutlineOpen(false)}
          onSelect={selectNode}
        />

        <main className="min-w-0 flex-1 overflow-auto bg-slate-100 p-2 sm:p-4">
          <div className="border-border bg-panel mb-3 flex min-h-9 flex-wrap items-center gap-1 rounded-md border px-3 py-1.5 text-xs text-slate-500">
            {breadcrumbs.map((node, index) => (
              <React.Fragment key={node.id}>
                {index > 0 ? <ChevronRight className="h-3.5 w-3.5" /> : null}
                <button
                  type="button"
                  className={node.id === selectedNode.id ? 'font-semibold text-blue-700' : 'hover:text-slate-900'}
                  onClick={() => selectNode(node.id)}
                >
                  {node.label}
                </button>
              </React.Fragment>
            ))}
          </div>
          <div
            ref={runtimeRootRef}
            className="border-border bg-panel rounded-lg border shadow-sm"
            onClickCapture={handleRuntimeClick}
            onSubmitCapture={(event) => {
              event.preventDefault();
              event.stopPropagation();
              setWriteBlocked(true);
            }}
            data-testid="contextual-authoring-canvas"
          >
            {children}
          </div>
        </main>

        <InspectorPanel
          node={selectedNode}
          manifest={selectedManifest}
          session={session}
          open={inspectorOpen}
          onClose={() => setInspectorOpen(false)}
          onHandoff={(property) =>
            setExplain({
              intent: 'PAGE_STRUCTURE',
              title: '进入应用设计中心',
              reason: explainHandoffReason(selectedNode, property),
              propertyPath: property?.propertyPath,
            })
          }
        />
      </div>

      <ChangeDock session={session} />
      {explain ? (
        <ExplainDialog
          state={explain}
          node={selectedNode}
          pending={handoffPending}
          onCancel={() => setExplain(null)}
          onContinue={createHandoff}
        />
      ) : null}
    </section>
  );
}

function AuthoringToolbar({
  mode,
  temporaryMode,
  onModeChange,
  onOutline,
  onInspector,
  onNewPage,
  onExit,
}: {
  mode: AuthoringMode;
  temporaryMode: boolean;
  onModeChange: (mode: AuthoringMode) => void;
  onOutline: () => void;
  onInspector: () => void;
  onNewPage: () => void;
  onExit: () => void;
}) {
  return (
    <header className="border-border bg-panel sticky top-0 z-20 flex min-h-14 flex-wrap items-center gap-2 border-b px-3 py-2">
      <div className="mr-auto flex min-w-0 items-center gap-2">
        <ShieldCheck className="h-5 w-5 shrink-0 text-blue-600" />
        <div className="min-w-0">
          <div className="truncate text-sm font-semibold text-slate-900">配置模式</div>
          <div className="truncate text-xs text-amber-700">当前为只读配置桥；不会写入业务数据</div>
        </div>
      </div>
      <button type="button" className="border-border inline-flex min-h-9 items-center gap-1.5 rounded-md border px-2.5 text-sm text-slate-700 hover:bg-slate-50 lg:hidden" onClick={onOutline}>
        <PanelLeft className="h-4 w-4" />大纲
      </button>
      <div className="border-border flex rounded-md border bg-slate-50 p-0.5" role="group" aria-label="配置模式">
        <ModeButton active={mode === 'select'} onClick={() => onModeChange('select')}>
          <MousePointer2 className="h-4 w-4" />选择
        </ModeButton>
        <ModeButton active={mode === 'interact'} onClick={() => onModeChange('interact')}>
          <Eye className="h-4 w-4" />交互预览
        </ModeButton>
      </div>
      {temporaryMode ? <span className="text-xs text-blue-700">Alt 临时切换</span> : null}
      <select
        aria-label="角色结构预览"
        className="border-border bg-panel rounded-md border px-2 py-1.5 text-sm text-slate-700"
        defaultValue="current"
      >
        <option value="current">当前角色结构</option>
      </select>
      <button type="button" className="border-border inline-flex min-h-9 items-center gap-1.5 rounded-md border px-2.5 text-sm text-slate-700 hover:bg-slate-50" onClick={onNewPage}>
        <Plus className="h-4 w-4" />新页面 / 菜单
      </button>
      <button type="button" className="border-border inline-flex min-h-9 items-center gap-1.5 rounded-md border px-2.5 text-sm text-slate-700 hover:bg-slate-50 lg:hidden" onClick={onInspector}>
        <PanelRight className="h-4 w-4" />属性
      </button>
      <button type="button" className="border-border inline-flex min-h-9 items-center gap-1.5 rounded-md border px-2.5 text-sm text-slate-700 hover:bg-slate-50" onClick={onExit}>
        <X className="h-4 w-4" />退出
      </button>
    </header>
  );
}

function ModeButton({ active, onClick, children }: { active: boolean; onClick: () => void; children: React.ReactNode }) {
  return (
    <button
      type="button"
      aria-pressed={active}
      onClick={onClick}
      className={`inline-flex min-h-8 items-center gap-1.5 rounded px-2.5 text-sm ${
        active ? 'bg-white font-medium text-blue-700 shadow-sm' : 'text-slate-500 hover:text-slate-900'
      }`}
    >
      {children}
    </button>
  );
}

function OutlinePanel({
  root,
  selectedId,
  open,
  onClose,
  onSelect,
}: {
  root: AuthoringNode;
  selectedId: string;
  open: boolean;
  onClose: () => void;
  onSelect: (id: string) => void;
}) {
  return (
    <aside
      className={`border-border bg-panel z-40 w-[280px] shrink-0 overflow-auto border-r lg:relative lg:block ${
        open ? 'absolute inset-y-0 left-0 block shadow-2xl' : 'hidden'
      }`}
      aria-label="页面大纲"
      data-testid="authoring-outline"
    >
      <PanelHeader title="页面大纲" onClose={onClose} />
      <div className="p-2">
        <OutlineNode node={root} selectedId={selectedId} onSelect={onSelect} />
      </div>
    </aside>
  );
}

function OutlineNode({ node, selectedId, onSelect }: { node: AuthoringNode; selectedId: string; onSelect: (id: string) => void }) {
  return (
    <div>
      <button
        type="button"
        onClick={() => onSelect(node.id)}
        className={`flex min-h-9 w-full items-center gap-2 rounded-md px-2 text-left text-sm ${
          node.id === selectedId ? 'bg-blue-50 font-medium text-blue-700' : 'text-slate-700 hover:bg-slate-50'
        }`}
        style={{ paddingLeft: `${Math.min(node.depth * 14 + 8, 64)}px` }}
        data-testid={`authoring-outline-${node.id}`}
      >
        {node.kind === 'page' ? <Layers3 className="h-4 w-4" /> : <span className="h-1.5 w-1.5 rounded-full bg-slate-300" />}
        <span className="truncate">{node.label}</span>
        <span className="ml-auto text-[10px] uppercase text-slate-400">{node.kind}</span>
      </button>
      {node.children.map((child) => (
        <OutlineNode key={child.id} node={child} selectedId={selectedId} onSelect={onSelect} />
      ))}
    </div>
  );
}

function InspectorPanel({
  node,
  manifest,
  session,
  open,
  onClose,
  onHandoff,
}: {
  node: AuthoringNode;
  manifest?: CapabilityManifest;
  session: AuthoringSession;
  open: boolean;
  onClose: () => void;
  onHandoff: (property?: PropertyCapability) => void;
}) {
  const properties = Object.values(manifest?.properties ?? {}).sort((left, right) =>
    left.propertyPath.localeCompare(right.propertyPath),
  );
  return (
    <aside
      className={`border-border bg-panel z-40 w-[360px] max-w-[calc(100vw-2rem)] shrink-0 overflow-auto border-l lg:relative lg:block ${
        open ? 'absolute inset-y-0 right-0 block shadow-2xl' : 'hidden'
      }`}
      aria-label="属性检查器"
      data-testid="authoring-inspector"
    >
      <PanelHeader title="属性检查器" onClose={onClose} />
      <div className="space-y-4 p-4">
        <div>
          <div className="text-xs font-semibold uppercase tracking-wide text-slate-400">当前对象</div>
          <div className="mt-1 text-base font-semibold text-slate-900">{node.label}</div>
          <div className="mt-1 break-all font-mono text-xs text-slate-500">{node.sourceId}</div>
        </div>
        <div className="grid grid-cols-2 gap-2 text-xs">
          <StatusCell label="风险" value={session.riskLevel} />
          <StatusCell label="发布" value={publishLabel(session.publishPolicy)} />
          <StatusCell label="校验" value={session.validationState} />
          <StatusCell label="修订" value={`r${session.revision}`} />
        </div>
        <div className="border-status-amber bg-status-amber-bg rounded-md border p-3 text-xs text-amber-900">
          M1 为只读配置桥。下列能力来自服务端可信清单；写态将在安全编辑切片启用。
        </div>
        <div>
          <div className="mb-2 text-xs font-semibold uppercase tracking-wide text-slate-400">可配置属性</div>
          {properties.length ? (
            <div className="space-y-2">
              {properties.map((property) => (
                <button
                  type="button"
                  key={property.propertyPath}
                  onClick={() =>
                    property.route === 'HANDOFF_STUDIO' ? onHandoff(property) : undefined
                  }
                  className={`border-border w-full rounded-md border p-2 text-left ${
                    property.route === 'HANDOFF_STUDIO' ? 'hover:border-blue-300 hover:bg-blue-50' : ''
                  }`}
                >
                  <div className="flex items-center gap-2">
                    <code className="min-w-0 flex-1 truncate text-xs text-slate-700">{property.propertyPath}</code>
                    <RiskBadge risk={property.risk} />
                  </div>
                  <div className="mt-1 flex items-center justify-between text-[11px] text-slate-500">
                    <span>{routeLabel(property.route)}</span>
                    {property.route === 'HANDOFF_STUDIO' ? <span className="text-blue-700">高级设置 ↗</span> : null}
                  </div>
                </button>
              ))}
            </div>
          ) : (
            <div className="rounded-md border border-dashed border-slate-300 p-3 text-xs text-slate-500">
              此对象未声明现场配置能力，默认进入应用设计中心。
            </div>
          )}
        </div>
        <button
          type="button"
          onClick={() => onHandoff()}
          className="inline-flex min-h-10 w-full items-center justify-center gap-2 rounded-md bg-blue-600 px-3 text-sm font-semibold text-white hover:bg-blue-700"
        >
          <Settings2 className="h-4 w-4" />高级设置
        </button>
      </div>
    </aside>
  );
}

function ChangeDock({ session }: { session: AuthoringSession }) {
  return (
    <footer className="border-border bg-panel sticky bottom-0 z-20 flex min-h-14 flex-wrap items-center gap-3 border-t px-3 py-2 text-sm">
      <div className="mr-auto flex flex-wrap items-center gap-3">
        <strong className="text-slate-900">0 项未保存</strong>
        <span className="text-slate-600">{Math.max(0, session.revision - 1)} 项草稿变更</span>
        <span className="text-slate-600">0 个校验错误</span>
      </div>
      <DockButton icon={<GitCompare className="h-4 w-4" />} label="差异" />
      <DockButton label="保存" />
      <DockButton icon={<Eye className="h-4 w-4" />} label="预览" />
      <DockButton label="提交评审" />
      <span className="w-full text-right text-[11px] text-slate-500 sm:w-auto">只读桥接阶段</span>
    </footer>
  );
}

function DockButton({ icon, label }: { icon?: React.ReactNode; label: string }) {
  return (
    <button
      type="button"
      disabled
      title="M1 只读配置桥暂不开放写入"
      className="border-border inline-flex min-h-9 items-center gap-1.5 rounded-md border px-3 text-sm text-slate-400 disabled:cursor-not-allowed"
    >
      {icon}{label}
    </button>
  );
}

function ExplainDialog({
  state,
  node,
  pending,
  onCancel,
  onContinue,
}: {
  state: ExplainState;
  node: AuthoringNode;
  pending: boolean;
  onCancel: () => void;
  onContinue: () => void;
}) {
  return (
    <div className="fixed inset-0 z-[70] grid place-items-center bg-slate-950/40 p-4" role="dialog" aria-modal="true" aria-labelledby="handoff-title">
      <div className="bg-panel w-full max-w-lg rounded-xl border border-slate-200 shadow-2xl">
        <div className="flex items-start gap-3 border-b border-slate-200 p-5">
          <div className="rounded-full bg-amber-100 p-2 text-amber-700"><AlertTriangle className="h-5 w-5" /></div>
          <div>
            <h2 id="handoff-title" className="font-semibold text-slate-900">{state.title}</h2>
            <p className="mt-1 text-sm text-slate-600">{state.reason}</p>
          </div>
        </div>
        <div className="space-y-3 p-5 text-sm">
          <div className="rounded-md bg-slate-50 p-3">
            <div><span className="text-slate-500">目标对象：</span>{node.label}</div>
            <div className="mt-1"><span className="text-slate-500">携带内容：</span>当前 ChangeSet、选择对象、返回位置</div>
            <div className="mt-1"><span className="text-slate-500">安全方式：</span>10 分钟、本人/本租户/本环境绑定、一次性 contextId</div>
          </div>
          <p className="text-xs text-slate-500">URL 不包含 pagePid、recordPid 或业务筛选；应用设计中心会重新检查权限。</p>
        </div>
        <div className="flex justify-end gap-2 border-t border-slate-200 p-4">
          <button type="button" onClick={onCancel} className="border-border min-h-10 rounded-md border px-4 text-sm text-slate-700 hover:bg-slate-50">取消</button>
          <button type="button" onClick={onContinue} disabled={pending} className="min-h-10 rounded-md bg-blue-600 px-4 text-sm font-semibold text-white hover:bg-blue-700 disabled:opacity-60">
            {pending ? '正在建立安全上下文…' : '继续到应用设计中心'}
          </button>
        </div>
      </div>
    </div>
  );
}

function PanelHeader({ title, onClose }: { title: string; onClose: () => void }) {
  return (
    <div className="border-border flex min-h-12 items-center justify-between border-b px-4">
      <h2 className="text-sm font-semibold text-slate-900">{title}</h2>
      <button type="button" onClick={onClose} className="rounded p-1 text-slate-500 hover:bg-slate-100 lg:hidden" aria-label={`关闭${title}`}><X className="h-4 w-4" /></button>
    </div>
  );
}

function StatusCell({ label, value }: { label: string; value: string }) {
  return <div className="rounded-md bg-slate-50 p-2"><div className="text-slate-400">{label}</div><div className="mt-0.5 font-semibold text-slate-700">{value}</div></div>;
}

function RiskBadge({ risk }: { risk: string }) {
  const tone = risk === 'L0' ? 'bg-emerald-100 text-emerald-700' : risk === 'L1' ? 'bg-blue-100 text-blue-700' : risk === 'L2' ? 'bg-amber-100 text-amber-700' : 'bg-red-100 text-red-700';
  return <span className={`rounded px-1.5 py-0.5 text-[10px] font-semibold ${tone}`}>{risk}</span>;
}

function captureInteractionContext(recordPid?: string) {
  const url = `${window.location.pathname}${window.location.search}${window.location.hash}`;
  const search = new URLSearchParams(window.location.search);
  return {
    route: url,
    ...(recordPid ? { recordPid } : {}),
    ...(search.get('tab') ? { tabId: search.get('tab')! } : {}),
    scroll: { x: window.scrollX, y: window.scrollY },
  };
}

function buildAuthoringTree(schema: ContextualAuthoringSurfaceProps['schema']): AuthoringNode {
  const page: AuthoringNode = {
    id: schema.id,
    sourceId: schema.id,
    kind: 'page',
    blockType: 'page',
    label: localizedLabel(schema.title, schema.pageKey || '页面'),
    parentId: null,
    depth: 0,
    source: schema as unknown as Record<string, unknown>,
    children: [],
  };
  page.children = (schema.blocks || []).map((block, index) =>
    buildBlockNode(block as Record<string, unknown>, page.id, 1, `block-${index}`),
  );
  return page;
}

function buildBlockNode(
  block: Record<string, unknown>,
  parentId: string,
  depth: number,
  fallback: string,
): AuthoringNode {
  const sourceId = String(block.id || `${parentId}/${fallback}`);
  const blockType = String(block.blockType || 'block');
  const node: AuthoringNode = {
    id: sourceId,
    sourceId,
    kind: 'block',
    blockType,
    label: localizedLabel(block.title, blockTypeLabel(blockType)),
    parentId,
    depth,
    source: block,
    children: [],
  };
  const nestedBlocks = Array.isArray(block.blocks) ? block.blocks : [];
  nestedBlocks.forEach((child, index) => {
    if (child && typeof child === 'object') {
      node.children.push(
        buildBlockNode(child as Record<string, unknown>, node.id, depth + 1, `block-${index}`),
      );
    }
  });
  addLeafNodes(node, 'field', listValues(block.fields));
  const table = block.table && typeof block.table === 'object' ? (block.table as Record<string, unknown>) : null;
  addLeafNodes(node, 'field', listValues(block.columns).length ? listValues(block.columns) : listValues(table?.columns));
  addLeafNodes(node, 'action', listValues(block.buttons));
  addLeafNodes(node, 'action', listValues(block.rowActions));
  addLeafNodes(node, 'action', listValues(table?.rowActions));
  listValues(block.tabs).forEach((tab, tabIndex) => {
    listValues(tab.blocks).forEach((child, childIndex) => {
      node.children.push(buildBlockNode(child, node.id, depth + 1, `tab-${tabIndex}-${childIndex}`));
    });
  });
  return node;
}

function addLeafNodes(parent: AuthoringNode, kind: 'field' | 'action', values: Record<string, unknown>[]) {
  values.forEach((value, index) => {
    const identity = String(value.id || value.field || value.code || `${kind}-${index}`);
    parent.children.push({
      id: `${parent.id}/${kind}:${identity}`,
      sourceId: String(value.id || identity),
      kind,
      blockType: kind === 'field' ? 'field' : 'action',
      label: localizedLabel(value.label, String(value.field || value.code || identity)),
      parentId: parent.id,
      depth: parent.depth + 1,
      source: value,
      children: [],
    });
  });
}

function listValues(value: unknown): Record<string, unknown>[] {
  return Array.isArray(value)
    ? value.filter((item): item is Record<string, unknown> => Boolean(item && typeof item === 'object'))
    : [];
}

function indexTree(root: AuthoringNode) {
  const byId = new Map<string, AuthoringNode>();
  const bySourceId = new Map<string, AuthoringNode>();
  const visit = (node: AuthoringNode) => {
    byId.set(node.id, node);
    if (!bySourceId.has(node.sourceId)) bySourceId.set(node.sourceId, node);
    node.children.forEach(visit);
  };
  visit(root);
  return { byId, bySourceId };
}

function ancestorChain(node: AuthoringNode, index: Map<string, AuthoringNode>): AuthoringNode[] {
  const chain = [node];
  let parentId = node.parentId;
  while (parentId) {
    const parent = index.get(parentId);
    if (!parent) break;
    chain.unshift(parent);
    parentId = parent.parentId;
  }
  return chain;
}

function localizedLabel(value: unknown, fallback: string): string {
  if (typeof value === 'string' && value.trim()) return value;
  if (value && typeof value === 'object') {
    try {
      return getLocalizedText(value as Record<string, string>, 'zh-CN', (key) => key) || fallback;
    } catch {
      return fallback;
    }
  }
  return fallback;
}

function blockTypeLabel(blockType: string): string {
  const labels: Record<string, string> = {
    table: '表格',
    list: '列表',
    filters: '筛选区',
    toolbar: '操作栏',
    form: '表单',
    'form-section': '表单分组',
    'detail-section': '详情分组',
    chart: '图表',
    tabs: '标签页',
    description: '说明',
    'rich-text': '富文本',
  };
  return labels[blockType] || blockType;
}

function isSafePreviewInteraction(target: HTMLElement): boolean {
  const interactive = target.closest<HTMLElement>('button, a, input, select, textarea, [role="tab"], [role="button"]');
  if (!interactive) return true;
  if (interactive.matches('a, input, select, textarea, [role="tab"]')) return true;
  const testId = interactive.dataset.testid || '';
  return (
    interactive.getAttribute('aria-expanded') !== null ||
    interactive.getAttribute('aria-controls') !== null ||
    /(tab|toggle|expand|collapse|pagination|page-size|filter-search|filter-reset)/i.test(testId)
  );
}

function explainHandoffReason(node: AuthoringNode, property?: PropertyCapability): string {
  if (property?.route === 'HANDOFF_STUDIO') {
    return `属性 ${property.propertyPath} 涉及 ${property.effectTags.join('、') || '业务语义'}，需要依赖分析和专业发布治理。`;
  }
  if (node.kind === 'page') return '页面级结构、路由和资源关系需要在应用设计中心统一治理。';
  return '此对象未声明可安全就地写入的属性，系统不会猜测影响范围。';
}

function routeLabel(route: string): string {
  return ({ INLINE: '可就地配置', GUIDED_INLINE: '引导式配置', HANDOFF_STUDIO: '应用设计中心', DENY: '禁止' } as Record<string, string>)[route] || route;
}

function publishLabel(policy: string): string {
  return ({ DIRECT_ALLOWED: '可直发', DEFAULT_REVIEW: '默认评审', REQUIRED_REVIEW: '必须评审', STUDIO_APPROVAL: '专项审批', DENIED: '禁止' } as Record<string, string>)[policy] || policy;
}

function cssEscape(value: string): string {
  if (typeof CSS !== 'undefined' && typeof CSS.escape === 'function') return CSS.escape(value);
  return value.replace(/["\\]/g, '\\$&');
}

export const contextualAuthoringTestUtils = { buildAuthoringTree, indexTree, isSafePreviewInteraction };
