import assert from 'node:assert/strict';

import {
  buildPlaywrightArgs,
  DEFAULT_PHASE_ORDER,
  resolvePhases,
} from './run-showcase-seed-sequence.mjs';

assert.deepEqual(
  resolvePhases([]).map((phase) => phase.name),
  DEFAULT_PHASE_ORDER,
);

assert.deepEqual(
  resolvePhases(['workflow', 'commercial']).map((phase) => phase.spec),
  [
    'tests/api/setup/seed-showcase-workflow.spec.ts',
    'tests/api/setup/seed-showcase-commercial.spec.ts',
  ],
);

assert.throws(() => resolvePhases(['missing']), /Unknown showcase seed phase "missing"/);

assert.deepEqual(
  buildPlaywrightArgs(resolvePhases(['data'])[0], {
    config: 'custom.seed.config.ts',
    reporter: 'dot',
    outputPrefix: 'test-results/seed/check',
  }),
  [
    'playwright',
    'test',
    'tests/api/setup/seed-showcase-data.spec.ts',
    '--config=custom.seed.config.ts',
    '--reporter=dot',
    '--output=test-results/seed/check-data',
  ],
);

console.log('run-showcase-seed-sequence tests passed');
