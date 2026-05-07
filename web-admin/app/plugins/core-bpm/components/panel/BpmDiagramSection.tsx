/**
 * BpmDiagramSection - Task 12 of the OSS BPM closure spec.
 *
 * Renders the diagram slot of a {@link BpmPanelBlock}. Given a resolved
 * {@link BpmInstanceForRecord}, it fetches the process definition nodes/edges
 * via {@link getProcessDefinitionByKey} and draws them with @xyflow/react,
 * overlaying current/completed highlights driven by
 * `instance.currentNodes` / `instance.completedNodes`.
 *
 * Path A was evaluated: {@link ProcessStatusViewer} in `core-designer` also
 * renders runtime BPMN with highlights, but it (a) fetches its own status
 * based on `processInstanceId` / `businessKey` (would cause a second round
 * trip since the panel already has the instance in hand), (b) mutates the
 * shared `useBPMNStore` singleton to drive node colours, and (c) owns its own
 * header/status bar. Embedding it would either duplicate data flow or
 * require internal refactors that the Task 12 prompt explicitly forbids.
 *
 * Path B (fallback permitted by the plan, <=150 LOC) is taken: render a
 * minimal xyflow canvas with generic nodes plus CSS-class highlights, using
 * the already-fetched {@link BpmInstanceForRecord} so there is no second
 * status request. No dependency on `useBPMNStore` and no new npm packages.
 *
 * The `processDefinitionId` field on `BpmInstanceForRecord` carries the
 * SmartEngine process *key* (see `BpmFormService` line 216:
 * "processKey IS the processDefinitionId in SmartEngine"), so
 * `getProcessDefinitionByKey(instance.processDefinitionId)` resolves the
 * matching definition without additional mapping.
 *
 * @since BPM closure spec 1 (Task 12)
 */
import { useEffect, useRef, useState, type RefObject } from 'react';
import {
  ReactFlow,
  Controls,
  Background,
  useNodesInitialized,
  useReactFlow,
  type NodeTypes,
} from '@xyflow/react';
import '@xyflow/react/dist/style.css';

import { getProcessDefinitionByKey } from '~/plugins/core-designer/components/bpmn-designer/services/bpmnService';
import type {
  BPMNProcessDefinition,
  BPMNNode,
  BPMNEdge,
} from '~/plugins/core-designer/components/bpmn-designer/types';
import type { BpmInstanceForRecord } from '~/plugins/core-bpm/services/bpmWorkbenchService';
import { ResultHelper } from '~/utils/type';
import { BpmDiagramNode } from '~/plugins/core-bpm/components/panel/BpmDiagramNode';

/**
 * Translator signature compatible with `useI18n`'s `t`. Prop-injected for the
 * same reason as {@link BpmStatusSection}: keeps the component trivially
 * renderable in unit tests without an I18nProvider.
 */
type Translator = (
  key: string,
  params?: Record<string, unknown>,
  fallback?: string,
) => string;

export interface BpmDiagramSectionProps {
  instance: BpmInstanceForRecord | null;
  t: Translator;
}

/**
 * Local `nodeTypes` map: every BPMN node kind renders through a single
 * {@link BpmDiagramNode} component which reads a pre-computed `highlight`
 * value off `node.data`. This avoids touching `useBPMNStore` while still
 * giving current/completed/idle distinct styling.
 */
const nodeTypes: NodeTypes = {
  startEvent: BpmDiagramNode,
  endEvent: BpmDiagramNode,
  userTask: BpmDiagramNode,
  serviceTask: BpmDiagramNode,
  receiveTask: BpmDiagramNode,
  exclusiveGateway: BpmDiagramNode,
  parallelGateway: BpmDiagramNode,
  inclusiveGateway: BpmDiagramNode,
  callActivity: BpmDiagramNode,
};

function DiagramViewportSync({
  nodeCount,
  containerRef,
}: {
  nodeCount: number;
  containerRef: RefObject<HTMLDivElement | null>;
}) {
  const nodesInitialized = useNodesInitialized();
  const { fitView } = useReactFlow();

  useEffect(() => {
    if (!nodesInitialized || nodeCount === 0) return;
    const frame = window.requestAnimationFrame(() => {
      void fitView({ padding: 0.24, duration: 0 });
    });
    return () => window.cancelAnimationFrame(frame);
  }, [fitView, nodeCount, nodesInitialized]);

  useEffect(() => {
    if (!nodesInitialized || nodeCount === 0 || !containerRef.current) return;

    const observer = new ResizeObserver((entries) => {
      const entry = entries[0];
      if (!entry) return;
      const { width, height } = entry.contentRect;
      if (width <= 0 || height <= 0) return;

      window.requestAnimationFrame(() => {
        void fitView({ padding: 0.24, duration: 0 });
      });
    });

    observer.observe(containerRef.current);
    return () => observer.disconnect();
  }, [fitView, nodeCount, nodesInitialized]);

  return null;
}

export function BpmDiagramSection({ instance, t }: BpmDiagramSectionProps) {
  const [definition, setDefinition] = useState<BPMNProcessDefinition | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const containerRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (instance === null) {
      setDefinition(null);
      setError(null);
      setLoading(false);
      return;
    }

    let cancelled = false;
    setLoading(true);
    setError(null);

    // `processDefinitionId` on the status DTO is the SmartEngine process key.
    getProcessDefinitionByKey(instance.processDefinitionId)
      .then((result) => {
        if (cancelled) return;
        if (!ResultHelper.isSuccess(result) || !result.data) {
          setError(result.desc || 'process definition lookup failed');
          setLoading(false);
          return;
        }
        setDefinition(result.data);
        setLoading(false);
      })
      .catch((err: unknown) => {
        if (cancelled) return;
        setError(err instanceof Error ? err.message : String(err));
        setLoading(false);
      });

    return () => {
      cancelled = true;
    };
  }, [instance]);

  if (instance === null) {
    return null;
  }

  if (loading) {
    return (
      <div
        data-testid="bpm-diagram-loading"
        className="rounded border border-gray-200 bg-gray-50 p-6 text-sm text-gray-500"
      >
        {t('bpm.diagram.loading', undefined, '加载流程图...')}
      </div>
    );
  }

  if (error) {
    return (
      <div
        data-testid="bpm-diagram-error"
        className="rounded border border-red-200 bg-red-50 p-4 text-sm text-red-700"
      >
        {t('bpm.diagram.error', undefined, '流程图加载失败')}: {error}
      </div>
    );
  }

  if (!definition) {
    return null;
  }

  // Build a fast lookup from nodeId → highlight class so nodes can style
  // themselves without walking the status arrays.
  const currentIds = new Set(instance.currentNodes.map((n) => n.nodeId));
  const completedIds = new Set(instance.completedNodes.map((n) => n.nodeId));

  const annotatedNodes: BPMNNode[] = definition.nodes.map((node) => {
    const highlight: 'current' | 'completed' | 'idle' = currentIds.has(node.id)
      ? 'current'
      : completedIds.has(node.id)
        ? 'completed'
        : 'idle';
    return {
      ...node,
      data: { ...node.data, highlight },
    } as BPMNNode;
  });

  const styledEdges: BPMNEdge[] = definition.edges.map((edge) => ({
    ...edge,
    style: { stroke: '#94a3b8', strokeWidth: 2 },
  }));

  return (
    <div
      ref={containerRef}
      data-testid="bpm-diagram-container"
      className="h-80 w-full overflow-hidden rounded border border-gray-200 bg-white"
    >
      <ReactFlow<BPMNNode, BPMNEdge>
        nodes={annotatedNodes}
        edges={styledEdges}
        nodeTypes={nodeTypes}
        nodesDraggable={false}
        nodesConnectable={false}
        elementsSelectable={false}
        panOnDrag={true}
        zoomOnScroll={true}
        minZoom={0.2}
        maxZoom={1.5}
        fitView
        fitViewOptions={{ padding: 0.24 }}
      >
        <DiagramViewportSync
          nodeCount={annotatedNodes.length}
          containerRef={containerRef}
        />
        <Controls showInteractive={false} />
        <Background />
      </ReactFlow>
    </div>
  );
}
