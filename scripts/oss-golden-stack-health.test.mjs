import assert from 'node:assert/strict';
import fs from 'node:fs';
import test from 'node:test';

const scriptRoot = import.meta.dirname;
const stackScript = fs.readFileSync(new URL('./oss-golden-stack.sh', import.meta.url), 'utf8');
const healthSource = fs.readFileSync(new URL('../platform/src/main/java/com/auraboot/framework/integration/ReliableIntegrationHealth.java', import.meta.url), 'utf8');

// The env key and the Spring property must stay exact lowercase single-segment mirrors:
// MANAGEMENT_HEALTH_RELIABLEINTEGRATION_ENABLED <-> management.health.reliableintegration.enabled.
const ENV_KEY = 'MANAGEMENT_HEALTH_RELIABLEINTEGRATION_ENABLED=false';
const PROPERTY = 'management.health.reliableintegration.enabled';

test('every golden backend launch decouples aggregate health from the outbox backlog signal', () => {
  const launches = [...stackScript.matchAll(/^    env [^\n]*MANAGEMENT_HEALTH_DB_ENABLED=false[^\n]*\\$/gm)].map(match => match[0]);
  assert.equal(launches.length, 2, `expected exactly the 'up' and retained-resume backend launches, found ${launches.length}`);
  for (const launch of launches) {
    assert.ok(launch.includes(ENV_KEY),
      `backend launch must carry ${ENV_KEY} so a test-driven outbox retry storm cannot hold /actuator/health at 503: ${launch}`);
  }
});

test('the golden env key disables exactly the platform property the indicator is conditional on', () => {
  assert.match(healthSource, /@Component\("reliableIntegration"\)/);
  const conditional = healthSource.match(/@ConditionalOnProperty\(([^)]*)\)/);
  assert.ok(conditional, 'ReliableIntegrationHealth must stay conditional or the env key becomes dead');
  assert.ok(conditional[1].includes(`name = "${PROPERTY}"`), `indicator must gate on ${PROPERTY}`);
  assert.ok(conditional[1].includes('havingValue = "true"'), 'explicit true keeps the indicator registered');
  assert.ok(conditional[1].includes('matchIfMissing = true'), 'production default (property absent) keeps the indicator registered');
});

test('the env key maps to the property with the exact SYSTEM env underscore rule (no relaxed-binding ambiguity)', () => {
  assert.equal(ENV_KEY.toLowerCase().replaceAll('_', '.'), `${PROPERTY}=false`);
});
