import { describe, expect, it } from 'vitest';
import { COMPONENT_RUNTIME_MANIFEST } from '../ComponentRuntimeManifest';

describe('DecisionOps runtime component manifest', () => {
  it('registers all DecisionOps typed custom blocks used by DSL pages', () => {
    expect(COMPONENT_RUNTIME_MANIFEST.decisiontableworkbench).toMatchObject({
      modulePath: '../../../../ui/smart/decisionops/DecisionTableWorkbenchBlock.tsx',
      exportName: 'DecisionTableWorkbenchBlock',
    });
    expect(COMPONENT_RUNTIME_MANIFEST.decisiondefinitioncatalog.aliases).toContain(
      'DecisionDefinitionCatalogBlock',
    );
    expect(COMPONENT_RUNTIME_MANIFEST.decisionrolloutmonitor.aliases).toContain(
      'DecisionRolloutMonitorBlock',
    );
    expect(COMPONENT_RUNTIME_MANIFEST.eventpolicyactions.aliases).toContain(
      'EventPolicyActionsBlock',
    );
    expect(COMPONENT_RUNTIME_MANIFEST.executionlogtrace.aliases).toContain(
      'ExecutionLogTraceBlock',
    );
    expect(COMPONENT_RUNTIME_MANIFEST.decisionmodelfieldcatalog.aliases).toContain(
      'DecisionModelFieldCatalogBlock',
    );
  });
});
