import assert from 'node:assert/strict';
import { existsSync, readFileSync, statSync, mkdtempSync, rmSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import test from 'node:test';

const here = path.dirname(fileURLToPath(import.meta.url));
const runner = path.join(here, 'oss-backend-unit-ci.sh');
const composeOverride = path.join(here, '..', 'docker-compose.oss-backend-ci.override.yml');
const gradleBuild = path.join(here, '..', 'platform', 'build.gradle');
const source = readFileSync(runner, 'utf8');
const escapeRegex = (value) => value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

test('every repository Compose input exists in a clean checkout', () => {
  const inputs = [...source.matchAll(/-f "\$PROJECT_ROOT\/([^"]+)"/g)].map(match => match[1]);
  assert.ok(inputs.length > 0, 'runner must declare its Compose inputs');
  for (const input of inputs) {
    const file = path.join(here, '..', input);
    assert.ok(existsSync(file), `missing clean-checkout Compose input: ${input}`);
    assert.ok(statSync(file).isFile(), `Compose input must be a regular file: ${input}`);
  }
});

test('backend CI runner is executable and owns its complete infrastructure lifecycle', () => {
  assert.ok(statSync(runner).mode & 0o100);
  assert.doesNotMatch(source, /docker-compose\.skills-c2\.override\.yml/);
  assert.match(source, /up -d --wait postgres redis kafka/);
  assert.match(source, /runtime retained and stopped; network released: compose_project=/);
  assert.match(source, /COMPOSE_PROJECT="aura-ci-oss-backend-\$RUNTIME_TOKEN"/);
  assert.match(source, /free_port 25000 25999/);
  assert.match(source, /free_port 26000 26999/);
  assert.match(source, /free_port 27000 27999/);
  assert.doesNotMatch(source, /down --volumes --remove-orphans/);
  assert.match(source, /docker compose "\$\{COMPOSE_ARGS\[@\]\}" stop/);
  assert.match(source, /docker network disconnect -f "\$\{COMPOSE_PROJECT\}_default"/);
  assert.match(source, /docker network rm "\$\{COMPOSE_PROJECT\}_default"/);
  assert.match(source, /trap cleanup EXIT HUP INT TERM/);
  assert.match(source, /PostgreSQL init process complete; ready for start up\./);
  assert.match(source, /pg_isready -U auraboot -d aura_boot/);
});

test('backend CI runner migrates a blank database from the Flyway source of truth', () => {
  const override = readFileSync(composeOverride, 'utf8');

  assert.match(source, /docker-compose\.oss-backend-ci\.override\.yml/);
  assert.match(override, /skills_c2_postgres_data:\/var\/lib\/postgresql\/data/);
  assert.doesNotMatch(override, /container_name:|!override/);
  assert.doesNotMatch(override, /schema-current\.sql/);
  assert.match(source, /flyway\/flyway:12\.8\.1/);
  assert.match(source, /-locations=filesystem:\/flyway\/sql/);
  assert.match(source, /-table=ab_flyway_schema_history/);
  assert.match(source, /-baselineOnMigrate=false/);
  assert.match(source, /-validateMigrationNaming=true/);
  assert.match(source, /-cleanDisabled=true/);
  assert.match(source, /migrate/);
  assert.match(source, /validate/);
});

test('backend CI runner proves required platform seed rows before Gradle tests', () => {
  for (const table of [
    'ab_object_alias',
    'ab_agent_capability',
    'ab_login_application',
    'ab_login_channel',
    'ab_login_channel_auth_method',
    'ab_billing_resource_catalog',
  ]) {
    assert.match(source, new RegExp(escapeRegex(table)));
  }
  assert.match(source, /platform seed verification failed/);
});

test('backend CI runner provisions the lockfile-pinned Playwright Chromium golden dependency', () => {
  assert.match(source, /web-admin\/node_modules\/\.bin\/playwright/);
  assert.match(source, /VERSION_ID:-.*26\.04/);
  assert.match(source, /PLAYWRIGHT_HOST_PLATFORM_OVERRIDE="ubuntu24\.04-x64"/);
  assert.match(source, /PLAYWRIGHT_HOST_PLATFORM_OVERRIDE="ubuntu24\.04-arm64"/);
  assert.match(source, /"\$PLAYWRIGHT_CLI" install chromium/);
  assert.match(source, /playwright-install\.log/);
  assert.match(source, /cannot install lockfile-pinned Playwright Chromium/);
});

test('backend CI runner pre-pulls every fixed and Testcontainers image', () => {
  for (const image of [
    'pgvector/pgvector:pg16',
    'redis:7-alpine',
    'postgres:16',
    'mysql:8.0.39',
    'mysql:8.0',
    'confluentinc/cp-kafka:7.5.0',
    'tdengine/tdengine:3.3.4.3',
    'testcontainers/ryuk:0.12.0',
    'flyway/flyway:12.8.1',
  ]) {
    assert.match(source, new RegExp(escapeRegex(image)));
  }
  assert.match(source, /timeout 10m docker pull "\$image" \|\| environment_invalid/);
});

test('backend CI runner preserves Gradle product-test exit status', () => {
  assert.match(source, /^platform\/gradlew -p platform --continue cleanTest test bootstrapBillingAccountTest\s*$/m);
  assert.match(source, /gradle_status=\$\?/);
  assert.match(source, /exit "\$gradle_status"\s*$/);
  assert.doesNotMatch(source, /platform\/gradlew[^\n]*\|\| environment_invalid/);
});

test('backend CI runner keeps external DashScope checks out unless explicitly requested', () => {
  assert.match(source, /AURA_CI_INCLUDE_DASHSCOPE_LIVE:-0/);
  assert.match(source, /unset DASHSCOPE_API_KEY/);
  assert.match(source, /DashScope live checks disabled/);
});

test('backend CI runner executes destructive bootstrap verification only after the shared suite', () => {
  const buildSource = readFileSync(gradleBuild, 'utf8');
  assert.match(source, /--continue cleanTest test bootstrapBillingAccountTest/);
  assert.match(buildSource, /excludeTags 'destructive-bootstrap'/);
  assert.match(buildSource, /mustRunAfter tasks\.named\('test'\)/);
  assert.match(buildSource, /outputs\.upToDateWhen \{ false \}/);
});

test('backend CI runner points fixed-stack tests at runtime-owned host ports', () => {
  assert.match(source, /TEST_DATABASE_URL="jdbc:postgresql:\/\/127\.0\.0\.1:\$\{AURA_OSS_CI_POSTGRES_PORT\}\/aura_boot/);
  assert.match(source, /TEST_DATABASE_USERNAME='auraboot'/);
  assert.match(source, /TEST_DATABASE_PASSWORD='auraboot_dev'/);
  assert.match(source, /SPRING_DATASOURCE_URL="jdbc:postgresql:\/\/127\.0\.0\.1:\$\{AURA_OSS_CI_POSTGRES_PORT\}\/aura_boot/);
  assert.match(source, /SPRING_DATASOURCE_USERNAME='auraboot'/);
  assert.match(source, /SPRING_DATASOURCE_PASSWORD='auraboot_dev'/);
  assert.match(source, /SPRING_DATA_REDIS_HOST='127\.0\.0\.1'/);
  assert.match(source, /SPRING_DATA_REDIS_PORT="\$AURA_OSS_CI_REDIS_PORT"/);
  assert.match(source, /SPRING_DATA_REDIS_URL="redis:\/\/127\.0\.0\.1:\$AURA_OSS_CI_REDIS_PORT"/);
  assert.match(source, /SPRING_KAFKA_BOOTSTRAP_SERVERS="127\.0\.0\.1:\$AURA_OSS_CI_KAFKA_PORT"/);
  assert.match(source, /AURA_CI_REQUIRE_KAFKA='1'/);
  assert.match(source, /AURA_CI_KAFKA_BOOTSTRAP_SERVERS="127\.0\.0\.1:\$AURA_OSS_CI_KAFKA_PORT"/);
});

test('backend CI override provisions a required native Kafka broker', () => {
  const override = readFileSync(composeOverride, 'utf8');
  assert.match(override, /kafka:/);
  assert.match(override, /confluentinc\/cp-kafka:7\.5\.0/);
  assert.match(override, /AURA_OSS_CI_KAFKA_PORT/);
  assert.match(override, /EXTERNAL:\/\/0\.0\.0\.0:19092/);
  assert.match(override, /INTERNAL:\/\/kafka:9092/);
  assert.match(override, /KAFKA_INTER_BROKER_LISTENER_NAME: INTERNAL/);
  assert.match(override, /kafka-topics --bootstrap-server localhost:9092 --list/);
});

function allocateWithDocker(t, mode) {
  const artifacts = mkdtempSync(path.join(tmpdir(), 'oss-ci-network-contract-'));
  t.after(() => rmSync(artifacts, { recursive: true, force: true }));
  const allocator = source.match(/create_isolated_network\(\) \{[\s\S]*?\n\}/)?.[0];
  assert.ok(allocator, 'runner must allocate an explicit subnet before Compose');
  return spawnSync('bash', ['-c', `
    COMPOSE_PROJECT=own-contract
    AURA_OSS_CI_NETWORK=own-contract_default
    NETWORK_CREATED=false
    calls=0
    environment_invalid() { printf '%s\\n' "$*" >&2; printf 'calls=%s\\n' "$calls"; exit 2; }
    docker() {
      calls=$((calls + 1))
      printf '%s\\n' "$*" >> "$ARTIFACTS/commands.txt"
      if [[ "$MODE" == unexpected ]]; then printf 'permission denied\\n' >&2; return 1; fi
      if [[ "$MODE" == exhausted || "$calls" == 1 ]]; then
        printf 'Error response from daemon: Pool overlaps with other one on this address space\\n' >&2
        return 1
      fi
      printf 'created-network-id\\n'
    }
    ${allocator}
    create_isolated_network
    printf 'created=%s calls=%s\\n' "$NETWORK_CREATED" "$calls"
    cat "$ARTIFACTS/commands.txt"
  `], { encoding: 'utf8', env: { ...process.env, ARTIFACTS: artifacts, MODE: mode } });
}

test('CI network allocation advances only after overlap and Compose uses the owned external network', t => {
  const result = allocateWithDocker(t, 'overlap');
  assert.equal(result.status, 0, result.stderr);
  assert.match(result.stdout, /created=true calls=2/);
  assert.match(result.stdout, /network create --subnet 10\.247\.1\.0\/24 --label aura\.ci\.compose-project=own-contract own-contract_default/);
  const compose = readFileSync(composeOverride, 'utf8');
  assert.match(compose, /networks:\s+default:\s+external: true\s+name: "\$\{AURA_OSS_CI_NETWORK:\?/);
  assert.ok(source.indexOf('\ncreate_isolated_network\n') < source.indexOf('up -d --wait postgres redis kafka'));
  assert.match(source, /if \[\[ "\$NETWORK_CREATED" == true \]\]; then/);
});

test('an exhausted explicit network pool fails environment-invalid without claiming creation', t => {
  const result = allocateWithDocker(t, 'exhausted');
  assert.equal(result.status, 2);
  assert.match(result.stdout, /calls=256/);
  assert.match(result.stderr, /no free isolated CI network/);
  assert.doesNotMatch(result.stdout, /created=true/);
});

test('unexpected Docker errors fail immediately instead of being retried as subnet collisions', t => {
  const result = allocateWithDocker(t, 'unexpected');
  assert.equal(result.status, 2);
  assert.match(result.stdout, /calls=1/);
  assert.match(result.stderr, /isolated network creation failed/);
});
