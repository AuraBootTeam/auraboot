import { fetchResult } from '~/shared/services/http-client/HttpClient';

// ---- Types ----

export interface IntentAnalysisRequest {
  content: string;
  format: 'text' | 'markdown';
}

export interface FieldDef {
  code: string;
  name: string;
  type: string;
  required: boolean;
  description: string;
  enumValues?: string;
  referenceModel?: string;
}

export interface EntityDef {
  code: string;
  name: string;
  description: string;
  fields: FieldDef[];
}

export interface RelationshipDef {
  fromEntity: string;
  toEntity: string;
  type: string;
  foreignKey: string;
  description: string;
}

export interface TransitionDef {
  from: string;
  to: string;
  action: string;
  description: string;
}

export interface StateMachineDef {
  entityCode: string;
  fieldCode: string;
  states: string[];
  transitions: TransitionDef[];
}

export interface BusinessRuleDef {
  entityCode: string;
  ruleType: string;
  expression: string;
  description: string;
}

export interface IntentAnalysisResult {
  entities: EntityDef[];
  relationships: RelationshipDef[];
  stateMachines: StateMachineDef[];
  rules: BusinessRuleDef[];
  summary: string;
}

export interface PluginGenerateRequest {
  analysis: IntentAnalysisResult;
  pluginCode: string;
  pluginName: string;
}

export interface PluginGenerateResult {
  pluginCode: string;
  pluginName: string;
  configs: Record<string, unknown>;
  summary: string;
  modelCount: number;
  fieldCount: number;
  commandCount: number;
  pageCount: number;
}

export interface PluginDeployRequest {
  pluginCode: string;
  pluginName: string;
  configs: Record<string, unknown>;
}

export interface PluginDeployResult {
  success: boolean;
  pluginCode: string;
  message: string;
  modelsCreated: number;
  fieldsCreated: number;
  commandsCreated: number;
  pagesCreated: number;
  menusCreated: number;
}

// ---- API Calls ----

export async function analyzeIntent(
  request: IntentAnalysisRequest,
  token?: string,
): Promise<IntentAnalysisResult | null> {
  const result = await fetchResult<IntentAnalysisResult>('/api/agent/intent/analyze', {
    method: 'post',
    params: request as unknown as Record<string, unknown>,
    token,
  });
  return result.code === '0' ? result.data : null;
}

export async function generatePlugin(
  request: PluginGenerateRequest,
  token?: string,
): Promise<PluginGenerateResult | null> {
  const result = await fetchResult<PluginGenerateResult>('/api/agent/intent/generate', {
    method: 'post',
    params: request as unknown as Record<string, unknown>,
    token,
  });
  return result.code === '0' ? result.data : null;
}

export async function deployPlugin(
  request: PluginDeployRequest,
  token?: string,
): Promise<PluginDeployResult | null> {
  const result = await fetchResult<PluginDeployResult>('/api/agent/intent/deploy', {
    method: 'post',
    params: request as unknown as Record<string, unknown>,
    token,
  });
  return result.code === '0' ? result.data : null;
}
