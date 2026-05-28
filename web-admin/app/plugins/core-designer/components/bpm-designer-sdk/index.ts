/**
 * bpm-designer-sdk — B2b first-batch ports of bpmn-designer nodes onto the
 * flow-designer-sdk G1/G2/G7/G8 surfaces.
 *
 * This package coexists with bpmn-designer/ during the multi-batch T4
 * migration (double-write). It will become the single source of truth once
 * batches B2c (store) + B2d (page cutover) land.
 */

export {
  StartEventNode,
  EndEventNode,
  ParallelGatewayNode,
  ServiceTaskNode,
} from './nodes/BpmSdkNodes';

export {
  StartEventEditor,
  EndEventEditor,
  ParallelGatewayEditor,
  ServiceTaskEditor,
} from './editors/BpmSdkEditors';

export {
  BPM_SDK_BATCH1_NODE_TYPES,
  type BpmSdkBatch1NodeType,
  buildBpmSdkBatch1NodeDefinitions,
  registerBpmSdkBatch1,
} from './registerBpmSdkNodes';
