import type { WizardState } from './VirtualModelWizard';

export async function submitVirtualModel(state: WizardState): Promise<string> {
  if (!state.meta?.code || !state.meta?.displayName) throw new Error('元信息不完整');
  if (!state.sourceType) throw new Error('未选择数据源类型');
  if (!state.primaryKey) throw new Error('未指定主键');

  const caps = state.capabilities ?? {};
  const payload = {
    code: state.meta.code,
    displayName: state.meta.displayName,
    description: state.meta.description,
    sourceType: state.sourceType,
    sourceRef: state.sourceRef,
    primaryKey: state.primaryKey,
    fields: (state.detectedFields ?? []).map((f) => ({
      code: f.code,
      dataType: f.dataType,
      sortable: !!f.sortable,
      filterable: !!f.filterable,
    })),
    capabilities: {
      list: caps.list ?? true,
      detail: caps.detail ?? true,
      sort: caps.sort ?? true,
      filter: caps.filter ?? true,
      paginate: caps.paginate ?? true,
      export: caps.export ?? true,
      create: false,
      update: false,
      delete: false,
      bulkDelete: false,
    },
    ...(state.sourceType === 'endpoint' && state.endpointAdapter
      ? { extension: { endpointAdapter: state.endpointAdapter } }
      : {}),
  };

  const resp = await fetch('/api/meta/models', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  });
  if (!resp.ok) {
    const errorBody = await resp.json().catch(() => ({ message: `HTTP ${resp.status}` }));
    throw new Error(errorBody.message ?? `HTTP ${resp.status}`);
  }
  const body = await resp.json();
  const pid = body?.data?.pid;
  if (!pid) throw new Error('后端未返回 pid');
  return pid;
}
