import { useEffect, useRef, useState, type ReactNode } from 'react';
import { Link, useNavigate } from 'react-router';
import { useI18n } from '~/contexts/I18nContext';
import { usePermissions } from '~/contexts/AuthContext';
import { get, post } from '~/shared/services/http-client';
import { ResultContractView } from '~/plugins/core-aurabot/components-internal/ResultContractView';
import type { ResultContract } from '~/plugins/core-aurabot/types/ResultContract';
import {
  bindSkill,
  editableSchema,
  fieldsFromSchema,
  inputSchema,
  schemaFromFields,
  stringList,
  type InputField,
  type SkillRecord,
  type SkillSchema,
} from './skillDefinition';

const CONTROL =
  'w-full rounded-control border border-border-strong bg-panel px-3 py-2 text-sm text-text focus:outline-none focus:ring-2 focus:ring-accent-weak disabled:opacity-50';
const BUTTON =
  'rounded-control border border-border-strong bg-panel px-3 py-2 text-sm text-text hover:bg-hover focus:outline-none focus:ring-2 focus:ring-accent-weak disabled:opacity-50';
const PRIMARY = `${BUTTON} bg-accent text-white hover:bg-accent-hover`;
interface Tool {
  toolCode: string;
  toolName: string;
  description?: string;
  requiresApproval?: boolean;
  requiresConfirmation?: boolean;
  parameterSchema?: SkillSchema;
}
interface Colleague {
  pid: string;
  name: string;
  skills: unknown;
}
interface EngineResult {
  status: string;
  data?: Record<string, unknown>;
  outputType?: string;
  renderHint?: string;
  textSummary?: string;
  errorMessage?: string;
  durationMs?: number;
  toolCallCount?: number;
}
interface Props {
  runtime?: { recordPid?: string; initialRecord?: Record<string, unknown> };
}
const empty = (): SkillRecord => ({
  skill_code: '',
  skill_name: '',
  skill_description: '',
  skill_level: 'atomic',
  skill_category: 'custom',
  skill_version: '1.0.0',
  skill_status: 'draft',
  execution_mode: 'template',
  max_steps: 5,
  prompt_template: '',
});

/** Typed authoring and trial block hosted by the existing agent_skill DSL form. */
export function SkillDefinitionEditor({ runtime }: Props) {
  const { t, locale } = useI18n();
  const navigate = useNavigate();
  const { hasPermission } = usePermissions();
  const label = (key: string, cn: string, en: string) =>
    t(`ai.skills.editor.${key}`, undefined, locale.startsWith('zh') ? cn : en);
  const [draft, setDraft] = useState<SkillRecord>(empty);
  const [saved, setSaved] = useState<SkillRecord>();
  const [schema, setSchema] = useState<SkillSchema>({ type: 'object', properties: {} });
  const [fields, setFields] = useState<InputField[]>([]);
  const [selectedTools, setSelectedTools] = useState<string[]>([]);
  const [tools, setTools] = useState<Tool[]>([]);
  const [modelHint, setModelHint] = useState('');
  const [colleagues, setColleagues] = useState<Colleague[]>([]);
  const [agentPid, setAgentPid] = useState('');
  const [loading, setLoading] = useState(true);
  const [loadFailed, setLoadFailed] = useState(false);
  const [busy, setBusy] = useState(false);
  const [dirty, setDirty] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [toolError, setToolError] = useState('');
  const [values, setValues] = useState<Record<string, unknown>>({});
  const [message, setMessage] = useState('');
  const [consent, setConsent] = useState(false);
  const [result, setResult] = useState<EngineResult>();
  const generation = useRef(0);
  const authoring = useRef<HTMLFieldSetElement>(null);
  const trial = useRef<HTMLFieldSetElement>(null);
  const validate = (element: HTMLFieldSetElement | null) =>
    element != null &&
    Array.from(
      element.querySelectorAll<HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement>(
        'input,select,textarea',
      ),
    ).every((control) => control.disabled || control.reportValidity());
  const canManage = hasPermission('acp_agent_skill');
  const canRun = hasPermission('acp.runtime.manage');
  const canBind = hasPermission('acp_agent_definition');
  const supported =
    editableSchema(schema) &&
    Object.keys(inputSchema(draft.step_input_mappings ?? {})).length === 0 &&
    ['template', 'sequential', 'orchestration'].includes(draft.execution_mode ?? 'template');
  const readOnly = loadFailed || !canManage || draft.is_builtin === true || !supported;
  const fail = (cause: unknown) =>
    setError(
      cause instanceof Error
        ? cause.message
        : label(
            'failed',
            '操作失败，请重新加载后再试。',
            'Operation failed. Reload and try again.',
          ),
    );
  const change = (patch: Partial<SkillRecord>) => {
    setDraft((previous) => ({ ...previous, ...patch }));
    setDirty(true);
    setNotice('');
    setResult(undefined);
    setConsent(false);
  };
  const request = async <T,>(promise: ReturnType<typeof get<T>>): Promise<T> => {
    const response = await promise;
    if (String(response.code) !== '0')
      throw new Error(response.message || label('failed', '操作失败', 'Operation failed'));
    return response.data as T;
  };
  const loadTools = async () => {
    setToolError('');
    try {
      const data = await request(
        get<{ tools: Tool[] }>('/api/agent/tools/registry', {
          modelHint: modelHint.trim() || undefined,
        }),
      );
      setTools(data.tools);
    } catch {
      setToolError(
        label(
          'toolsFailed',
          '工具目录加载失败，请重新加载；已保存的绑定会保留。',
          'Tool catalog failed to load. Reload; saved bindings are retained.',
        ),
      );
    }
  };
  useEffect(() => {
    const id = ++generation.current;
    setLoading(true);
    setLoadFailed(false);
    setError('');
    void (async () => {
      try {
        const pid = runtime?.recordPid;
        const record = pid
          ? await request(get<SkillRecord>(`/api/dynamic/agent-skill/${encodeURIComponent(pid)}`))
          : empty();
        const parsed = inputSchema(record.skill_input_schema);
        if (id !== generation.current) return;
        setDraft(record);
        setSaved(pid ? record : undefined);
        setSchema(parsed);
        setFields(fieldsFromSchema(parsed));
        setSelectedTools(stringList(record.skill_tools));
        setDirty(false);
      } catch (cause) {
        if (id === generation.current) {
          setLoadFailed(true);
          fail(cause);
        }
      } finally {
        if (id === generation.current) setLoading(false);
      }
    })();
    void loadTools();
    return () => {
      generation.current++;
    };
  }, [runtime?.recordPid]);
  useEffect(() => {
    const warn = (event: BeforeUnloadEvent) => {
      if (dirty) {
        event.preventDefault();
        event.returnValue = '';
      }
    };
    window.addEventListener('beforeunload', warn);
    return () => window.removeEventListener('beforeunload', warn);
  }, [dirty]);
  const command = async <T,>(code: string, payload: Record<string, unknown>, pid?: string) =>
    request(
      post<T>(`/api/meta/commands/execute/${code}`, {
        payload,
        targetRecordPid: pid,
        operationType: pid ? 'UPDATE' : 'CREATE',
      }),
    );
  const save = async () => {
    setError('');
    setNotice('');
    setBusy(true);
    try {
      if (
        !selectedTools.length ||
        (draft.execution_mode === 'template' && selectedTools.length !== 1)
      )
        throw new Error(
          label(
            'toolsRequired',
            '单工具方式需选择一个工具；多工具请选择顺序执行或模型编排。',
            'Single-tool mode requires one tool. Choose sequential or orchestration for multiple tools.',
          ),
        );
      let nextSchema: SkillSchema;
      try {
        nextSchema = schemaFromFields(fields, schema);
      } catch {
        throw new Error(
          label(
            'invalidFields',
            '参数名称须唯一，并使用字母、数字或下划线，不能以数字开头。',
            'Parameter names must be unique identifiers and cannot start with a digit.',
          ),
        );
      }
      const payload = {
        skill_code: draft.skill_code.trim(),
        skill_name: draft.skill_name.trim(),
        skill_description: draft.skill_description,
        skill_level: draft.skill_level,
        skill_category: draft.skill_category,
        skill_version: draft.skill_version,
        skill_status: draft.skill_status,
        prompt_template: draft.prompt_template,
        execution_mode: draft.execution_mode,
        max_steps: Number(draft.max_steps),
        skill_tools: JSON.stringify(selectedTools),
        skill_input_schema: JSON.stringify(nextSchema),
      };
      const response = await command<Record<string, unknown>>(
        saved ? 'acp:update_agent_skill' : 'acp:create_agent_skill',
        payload,
        saved?.pid,
      );
      const pid =
        saved?.pid ?? String((response.data as Record<string, unknown> | undefined)?.recordPid ?? '');
      if (!pid)
        throw new Error(
          label(
            'missingReceipt',
            '保存响应缺少记录凭据，请返回列表核实后重新打开。',
            'Save response has no record receipt. Return to the list and reopen.',
          ),
        );
      const stored = await request(
        get<SkillRecord>(`/api/dynamic/agent-skill/${encodeURIComponent(pid)}`),
      );
      setSaved(stored);
      setDraft(stored);
      setSchema(inputSchema(stored.skill_input_schema));
      setDirty(false);
      setConsent(false);
      setResult(undefined);
      setNotice(
        label(
          'saved',
          '技能已保存，可以绑定AI同事或试运行。',
          'Skill saved. Bind a colleague or run a trial.',
        ),
      );
      if (!runtime?.recordPid) navigate(`/p/agent_skill/edit/${pid}`, { replace: true });
    } catch (cause) {
      fail(cause);
    } finally {
      setBusy(false);
    }
  };
  const loadColleagues = async () => {
    setBusy(true);
    setError('');
    try {
      const data = await request(
        get<{ records: Colleague[] }>('/api/dynamic/agent-definition/list', {
          pageNum: 1,
          pageSize: 100,
        }),
      );
      setColleagues(data.records);
    } catch (cause) {
      fail(cause);
    } finally {
      setBusy(false);
    }
  };
  const bind = async () => {
    if (!saved || !agentPid) return;
    setBusy(true);
    setError('');
    try {
      const current = await request(
        get<Colleague>(`/api/dynamic/agent-definition/${encodeURIComponent(agentPid)}`),
      );
      const next = bindSkill(current.skills, saved.skill_code);
      await command('acp:update_agent_definition', { skills: JSON.stringify(next) }, current.pid);
      const stored = await request(
        get<Colleague>(`/api/dynamic/agent-definition/${encodeURIComponent(agentPid)}`),
      );
      if (!stringList(stored.skills).includes(saved.skill_code))
        throw new Error(
          label(
            'bindFailed',
            '绑定未生效，请重新打开同事详情核实。',
            'Binding did not persist. Reopen the colleague to verify.',
          ),
        );
      setNotice(
        label(
          'bound',
          '技能已绑定，原有技能已保留。',
          'Skill bound; existing bindings were retained.',
        ),
      );
    } catch (cause) {
      fail(cause);
    } finally {
      setBusy(false);
    }
  };
  const run = async () => {
    if (!saved || dirty || !consent) return;
    setBusy(true);
    setError('');
    setResult(undefined);
    setConsent(false);
    try {
      const output = await request(
        post<EngineResult>(`/api/agent/skills/${encodeURIComponent(saved.skill_code)}/execute`, {
          input: values,
          userMessage: message,
        }),
      );
      setResult(output);
    } catch (cause) {
      fail(cause);
    } finally {
      setBusy(false);
    }
  };
  const field = (title: string, control: ReactNode, help?: string) => (
    <label className="block space-y-1 text-sm">
      <span className="text-text font-medium">{title}</span>
      {control}
      {help && <span className="text-text-2 block text-xs">{help}</span>}
    </label>
  );
  const section = (title: string, children: ReactNode) => (
    <section className="rounded-card border-border bg-panel space-y-4 border p-5">
      <h2 className="text-text text-base font-semibold">{title}</h2>
      {children}
    </section>
  );
  const modeNames = {
    template: label('single', '单工具执行', 'Single tool'),
    sequential: label('sequential', '按所选顺序执行', 'Sequential'),
    orchestration: label('orchestration', '由模型编排工具', 'Model orchestration'),
  };
  if (loading) return <p role="status">{label('loading', '正在加载技能…', 'Loading skill…')}</p>;
  const approvalRequired =
    result?.data?.approvalRequired === true || result?.data?.confirmationRequired === true;
  return (
    <div
      className="space-y-5"
      data-testid="skill-definition-editor"
      onKeyDown={(event) => {
        if (event.key === 'Enter' && event.target instanceof HTMLInputElement)
          event.preventDefault();
      }}
    >
      <Link className="text-accent text-sm underline" to="/p/agent_skill">
        {label('back', '返回技能管理', 'Back to skills')}
      </Link>
      {error && (
        <div role="alert" className="rounded-control border-danger text-danger border p-3">
          {error}
        </div>
      )}
      {notice && (
        <p role="status" className="rounded-control bg-subtle text-text p-3">
          {notice}
        </p>
      )}
      {readOnly && (
        <p role="status" className="rounded-control bg-subtle p-3">
          {label(
            'readOnly',
            '此定义为内置技能、当前账号只读，或包含高级结构。本页保留原定义，不提供简化覆盖。',
            'This definition is built-in, read-only for your account, or uses an advanced structure. Its original definition is preserved.',
          )}
        </p>
      )}
      <div>
        <fieldset ref={authoring} disabled={busy || readOnly} className="space-y-5">
          {section(
            label('identity', '名称与适用范围', 'Identity and scope'),
            <div className="grid gap-4 md:grid-cols-2">
              {field(
                label('name', '技能名称', 'Skill name'),
                <input
                  required
                  maxLength={200}
                  className={CONTROL}
                  value={draft.skill_name}
                  placeholder={label(
                    'nameExample',
                    '例如：BOM发布前检查',
                    'e.g. BOM pre-release review',
                  )}
                  onChange={(e) => change({ skill_name: e.target.value })}
                />,
              )}
              {field(
                label('code', '技能标识', 'Skill identifier'),
                <input
                  required
                  disabled={!!saved}
                  pattern="[a-zA-Z][a-zA-Z0-9_.:-]*"
                  maxLength={100}
                  className={CONTROL}
                  value={draft.skill_code}
                  placeholder="bom_pre_release_review"
                  onChange={(e) => change({ skill_code: e.target.value })}
                />,
                label(
                  'codeHelp',
                  '创建后用于稳定绑定，不能修改。',
                  'Stable binding identifier; cannot change after creation.',
                ),
              )}
              {field(
                label('version', '版本', 'Version'),
                <input
                  required
                  maxLength={20}
                  className={CONTROL}
                  value={draft.skill_version ?? ''}
                  placeholder="1.0.0"
                  onChange={(e) => change({ skill_version: e.target.value })}
                />,
              )}
              {field(
                label('status', '使用状态', 'Availability'),
                <select
                  className={CONTROL}
                  value={draft.skill_status ?? 'draft'}
                  onChange={(e) => change({ skill_status: e.target.value })}
                >
                  <option value="draft">{label('draft', '草稿', 'Draft')}</option>
                  <option value="active">{label('active', '启用', 'Active')}</option>
                  <option value="deprecated">{label('disabled', '停用', 'Disabled')}</option>
                </select>,
              )}
              <div className="md:col-span-2">
                {field(
                  label('description', '适用任务与边界', 'Task and boundaries'),
                  <textarea
                    className={CONTROL}
                    value={draft.skill_description ?? ''}
                    placeholder={label(
                      'descriptionExample',
                      '说明何时使用、应输出什么，以及必须交给业务命令校验的事项。',
                      'Describe when to use it, its outputs and required business validations.',
                    )}
                    onChange={(e) => change({ skill_description: e.target.value })}
                  />,
                )}
              </div>
            </div>,
          )}
          {section(
            label('procedure', '执行规程与工具', 'Procedure and tools'),
            <>
              {field(
                label('mode', '执行方式', 'Execution mode'),
                <select
                  className={CONTROL}
                  value={draft.execution_mode ?? 'template'}
                  onChange={(e) => change({ execution_mode: e.target.value })}
                >
                  {Object.entries(modeNames).map(([value, name]) => (
                    <option key={value} value={value}>
                      {name}
                    </option>
                  ))}
                </select>,
              )}
              {field(
                label('prompt', '操作规程', 'Procedure'),
                <textarea
                  rows={5}
                  className={CONTROL}
                  value={draft.prompt_template ?? ''}
                  placeholder={label(
                    'promptExample',
                    '例如：先查当前BOM，缺少版本时询问；发布操作必须使用专用命令并等待审批。',
                    'Read the current BOM; clarify missing versions. Publish only through the dedicated command and approval.',
                  )}
                  onChange={(e) => change({ prompt_template: e.target.value })}
                />,
                label(
                  'promptHelp',
                  '规程在模型编排时使用；单工具与顺序方式按配置直接执行。规程不授予权限，也不代替业务校验。',
                  'The procedure guides model orchestration; single/sequential modes run configured tools directly. It grants no permissions.',
                ),
              )}
              {draft.execution_mode === 'orchestration' &&
                field(
                  label('maxSteps', '最大工具轮数', 'Maximum tool rounds'),
                  <input
                    required
                    type="number"
                    min={1}
                    max={20}
                    className={CONTROL}
                    value={draft.max_steps ?? 5}
                    onChange={(e) => change({ max_steps: Number(e.target.value) })}
                  />,
                )}
              <div className="flex items-end gap-3">
                {field(
                  label('model', '业务模型范围', 'Business model scope'),
                  <input
                    className={CONTROL}
                    value={modelHint}
                    placeholder="tpm_customer"
                    onChange={(e) => setModelHint(e.target.value)}
                  />,
                )}
                <button className={BUTTON} type="button" onClick={() => void loadTools()}>
                  {label('loadTools', '加载工具', 'Load tools')}
                </button>
              </div>
              {toolError && (
                <p role="alert" className="text-danger">
                  {toolError}
                </p>
              )}
              <p className="text-text-2 text-xs">
                {label(
                  'orderHelp',
                  '按勾选顺序执行；取消再勾选可调整顺序。加载模型范围可发现列表与详情查询工具。',
                  'Selection order determines sequential execution. Reselect to reorder. Load a model scope to discover list/detail tools.',
                )}
              </p>
              <div className="rounded-control border-border max-h-64 space-y-2 overflow-y-auto border p-3">
                {tools.length === 0 && (
                  <p>
                    {label(
                      'noTools',
                      '当前范围没有可用工具。请检查权限或调整模型范围。',
                      'No tools in this scope. Check permissions or change model scope.',
                    )}
                  </p>
                )}
                {tools.map((tool) => (
                  <label
                    key={tool.toolCode}
                    className="rounded-control hover:bg-hover flex items-start gap-3 p-2"
                  >
                    <input
                      type="checkbox"
                      data-testid={`skill-tool-${tool.toolCode}`}
                      checked={selectedTools.includes(tool.toolCode)}
                      onChange={(e) => {
                        setSelectedTools((previous) =>
                          e.target.checked
                            ? [...previous, tool.toolCode]
                            : previous.filter((code) => code !== tool.toolCode),
                        );
                        change({});
                      }}
                    />
                    <span>
                      <span className="font-medium">
                        {tool.toolName || tool.description || tool.toolCode}
                      </span>
                      {(tool.requiresApproval || tool.requiresConfirmation) && (
                        <span className="text-text-2 ml-2 text-xs">
                          {label('governed', '需确认或审批', 'Confirmation or approval')}
                        </span>
                      )}
                      <span className="text-text-2 block text-xs">{tool.description}</span>
                    </span>
                  </label>
                ))}
              </div>
              <p className="text-text-2 text-xs">
                {label('selected', '已选择工具数', 'Selected tools')}: {selectedTools.length}
              </p>
            </>,
          )}
          {section(
            label('inputs', '技能输入', 'Skill inputs'),
            <>
              <p className="text-text-2 text-xs">
                {label(
                  'inputsHelp',
                  '定义调用者填写的参数。工具仍会独立校验参数；顺序方式将同一份输入交给各工具。',
                  'Define caller inputs. Tools validate independently; sequential mode passes the same inputs to each tool.',
                )}
              </p>
              {fields.map((item, index) => (
                <div
                  key={index}
                  className="rounded-control border-border grid items-end gap-3 border p-3 md:grid-cols-4"
                >
                  {field(
                    label('parameter', '参数名称', 'Parameter name'),
                    <input
                      required
                      className={CONTROL}
                      value={item.name}
                      placeholder="modelCode"
                      onChange={(e) => {
                        setFields((previous) =>
                          previous.map((value, i) =>
                            i === index ? { ...value, name: e.target.value } : value,
                          ),
                        );
                        change({});
                      }}
                    />,
                  )}
                  {field(
                    label('parameterLabel', '显示名称', 'Display label'),
                    <input
                      required
                      className={CONTROL}
                      value={item.title}
                      placeholder={label(
                        'parameterExample',
                        '例如：业务模型',
                        'e.g. Business model',
                      )}
                      onChange={(e) => {
                        setFields((previous) =>
                          previous.map((value, i) =>
                            i === index ? { ...value, title: e.target.value } : value,
                          ),
                        );
                        change({});
                      }}
                    />,
                  )}
                  {field(
                    label('type', '参数类型', 'Parameter type'),
                    <select
                      className={CONTROL}
                      value={item.type}
                      onChange={(e) => {
                        setFields((previous) =>
                          previous.map((value, i) =>
                            i === index
                              ? { ...value, type: e.target.value as InputField['type'] }
                              : value,
                          ),
                        );
                        change({});
                      }}
                    >
                      {(['string', 'number', 'integer', 'boolean'] as const).map((type) => (
                        <option key={type} value={type}>
                          {label(
                            type,
                            { string: '文本', number: '数值', integer: '整数', boolean: '是／否' }[
                              type
                            ],
                            {
                              string: 'Text',
                              number: 'Number',
                              integer: 'Integer',
                              boolean: 'Yes / No',
                            }[type],
                          )}
                        </option>
                      ))}
                    </select>,
                  )}
                  <div className="flex items-center gap-3">
                    <label>
                      <input
                        type="checkbox"
                        checked={item.required}
                        onChange={(e) => {
                          setFields((previous) =>
                            previous.map((value, i) =>
                              i === index ? { ...value, required: e.target.checked } : value,
                            ),
                          );
                          change({});
                        }}
                      />{' '}
                      {label('required', '必填', 'Required')}
                    </label>
                    <button
                      type="button"
                      className={BUTTON}
                      onClick={() => {
                        setFields((previous) => previous.filter((_, i) => i !== index));
                        change({});
                      }}
                    >
                      {label('remove', '移除', 'Remove')}
                    </button>
                  </div>
                </div>
              ))}
              <button
                type="button"
                className={BUTTON}
                onClick={() => {
                  setFields((previous) => [
                    ...previous,
                    { name: '', title: '', type: 'string', required: false },
                  ]);
                  change({});
                }}
              >
                {label('addInput', '添加参数', 'Add input')}
              </button>
            </>,
          )}
          <button
            type="button"
            className={PRIMARY}
            onClick={() => {
              if (validate(authoring.current)) void save();
            }}
            disabled={!dirty && !!saved}
          >
            {busy
              ? label('saving', '处理中…', 'Working…')
              : label('save', '保存技能', 'Save skill')}
          </button>
        </fieldset>
      </div>
      {section(
        label('colleagues', '绑定AI同事', 'Bind an AI colleague'),
        <>
          <p className="text-text-2 text-sm">
            {label(
              'bindingHelp',
              '保存后可添加到同事的技能列表，不覆盖其他已有技能。',
              'After saving, add this skill to a colleague without replacing other bindings.',
            )}
          </p>
          <button
            type="button"
            className={BUTTON}
            disabled={!saved || dirty || busy || !canBind}
            onClick={() => void loadColleagues()}
          >
            {label('loadColleagues', '选择AI同事', 'Choose a colleague')}
          </button>
          {colleagues.length > 0 && (
            <div className="flex items-end gap-3">
              {field(
                label('colleague', 'AI同事', 'AI colleague'),
                <select
                  className={CONTROL}
                  value={agentPid}
                  onChange={(e) => setAgentPid(e.target.value)}
                >
                  <option value="">{label('choose', '请选择', 'Choose')}</option>
                  {colleagues.map((agent) => (
                    <option key={agent.pid} value={agent.pid}>
                      {agent.name}
                    </option>
                  ))}
                </select>,
              )}
              <button
                type="button"
                className={BUTTON}
                disabled={!agentPid || dirty || busy}
                onClick={() => void bind()}
              >
                {label('bind', '绑定技能', 'Bind skill')}
              </button>
            </div>
          )}
          {!canBind && (
            <p>
              {label(
                'bindPermission',
                '绑定需具备AI同事管理权限。',
                'Colleague management permission is required.',
              )}
            </p>
          )}
        </>,
      )}
      {section(
        label('trial', '真实试运行', 'Real trial run'),
        <>
          <p className="text-text-2 text-sm">
            {label(
              'trialHelp',
              '使用已保存且启用的版本。可能产生模型费用和真实业务操作；原有权限、确认与审批继续生效。本次结果在离开页面后清空。',
              'Uses the saved active definition. Model costs and real effects are possible. Permissions, confirmation and approval remain enforced. Results clear when leaving this page.',
            )}
          </p>
          <div>
            <fieldset
              ref={trial}
              disabled={
                busy || !saved || dirty || saved.skill_status !== 'active' || !canRun || !supported
              }
              className="space-y-4"
            >
              {fields.map((item) =>
                field(
                  item.title || item.name,
                  item.type === 'boolean' ? (
                    <select
                      className={CONTROL}
                      required={item.required}
                      value={values[item.name] === undefined ? '' : String(values[item.name])}
                      onChange={(e) =>
                        setValues((previous) => ({
                          ...previous,
                          [item.name]:
                            e.target.value === '' ? undefined : e.target.value === 'true',
                        }))
                      }
                    >
                      <option value="">{label('unset', '未填写', 'Unset')}</option>
                      <option value="true">{label('yes', '是', 'Yes')}</option>
                      <option value="false">{label('no', '否', 'No')}</option>
                    </select>
                  ) : (
                    <input
                      className={CONTROL}
                      required={item.required}
                      type={item.type === 'string' ? 'text' : 'number'}
                      step={item.type === 'integer' ? '1' : 'any'}
                      value={String(values[item.name] ?? '')}
                      onChange={(e) =>
                        setValues((previous) => ({
                          ...previous,
                          [item.name]:
                            e.target.value === ''
                              ? undefined
                              : item.type === 'string'
                                ? e.target.value
                                : Number(e.target.value),
                        }))
                      }
                    />
                  ),
                ),
              )}
              {field(
                label('task', '本次任务说明', 'Task for this run'),
                <textarea
                  className={CONTROL}
                  value={message}
                  placeholder={label(
                    'taskExample',
                    '说明本次要查询或处理的对象。',
                    'Describe the records or task for this run.',
                  )}
                  onChange={(e) => setMessage(e.target.value)}
                />,
              )}
              <label className="flex gap-2 text-sm">
                <input
                  type="checkbox"
                  checked={consent}
                  onChange={(e) => setConsent(e.target.checked)}
                />
                {label(
                  'consent',
                  '我已核对输入，允许本次真实执行。',
                  'I reviewed the input and allow this real execution.',
                )}
              </label>
              <button
                type="button"
                className={PRIMARY}
                onClick={() => {
                  if (validate(trial.current)) void run();
                }}
                disabled={!consent}
              >
                {busy
                  ? label('running', '正在执行，请勿重复提交…', 'Running; do not resubmit…')
                  : label('run', '执行一次', 'Run once')}
              </button>
            </fieldset>
          </div>
          {!canRun && (
            <p>
              {label(
                'runPermission',
                '试运行需具备Agent运行时管理权限。',
                'Agent runtime management permission is required.',
              )}
            </p>
          )}
          {result && (
            <div data-testid="skill-trial-result" aria-live="polite">
              {approvalRequired ? (
                <p role="status">
                  {label(
                    'pending',
                    '已暂停，等待确认或审批。本页不会自动重试；请前往审批入口核查。',
                    'Paused for confirmation or approval. This page will not retry. Review it in approvals.',
                  )}
                </p>
              ) : (
                <ResultContractView
                  contract={
                    {
                      outputType: result.outputType ?? 'text',
                      renderHint: result.renderHint,
                      data: result.data,
                      textSummary: result.textSummary ?? result.errorMessage,
                      status:
                        result.status === 'SUCCESS'
                          ? 'success'
                          : result.status === 'PARTIAL_SUCCESS'
                            ? 'partial_success'
                            : 'failed',
                      actionability: 'read_only',
                      durationMs: result.durationMs,
                    } as ResultContract
                  }
                />
              )}
            </div>
          )}
        </>,
      )}
    </div>
  );
}
