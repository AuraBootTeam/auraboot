import assert from 'node:assert/strict';
import { readFileSync, statSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import test from 'node:test';
import YAML from 'yaml';

const here = path.dirname(fileURLToPath(import.meta.url));
const runner = path.join(here, 'oss-backend-unit-ci.sh');
const composeOverride = path.join(here, '..', 'docker-compose.oss-backend-ci.override.yml');
const gradleBuild = path.join(here, '..', 'platform', 'build.gradle');
const source = readFileSync(runner, 'utf8');
const escapeRegex = (value) => value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

test('every Compose input exists in the submitted source checkout', () => {
  const inputs = [...source.matchAll(/-f "\$PROJECT_ROOT\/([^"]+)"/g)].map((match) => match[1]);
  assert.ok(inputs.length > 0, 'runner must declare its Compose inputs');
  for (const input of inputs) {
    assert.ok(statSync(path.join(here, '..', input)).isFile(), `missing Compose input: ${input}`);
  }
});

test('backend CI runner is executable and owns its complete infrastructure lifecycle', () => {
  assert.ok(statSync(runner).mode & 0o100);
  assert.doesNotMatch(source, /docker-compose\.skills-c2\.override\.yml/);
  assert.match(source, /config --quiet/);
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
  assert.match(override, /volumes:\s*!override/);
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
  assert.match(source, /platform\/gradlew -p platform --continue cleanTest test bootstrapBillingAccountTest\s+#[\s\S]*gradle_status=\$\?/);
  assert.match(source, /exit "\$gradle_status"\s*$/);
  assert.doesNotMatch(source, /platform\/gradlew[^\n]*\|\| environment_invalid/);
});

test('CI Compose override owns ports, containers and an empty PostgreSQL volume', () => {
  // Parse Compose sequence tags as ordinary sequences for structural checks;
  // the Linux runner validates actual merge semantics with Compose itself.
  const config = YAML.parse(readFileSync(composeOverride, 'utf8').replace(/!override[ \t]*/g, ''));
  const { postgres, redis } = config.services;
  assert.deepEqual(postgres.volumes, ['postgres_data:/var/lib/postgresql/data']);
  assert.deepEqual(postgres.environment, {
    POSTGRES_DB: 'aura_boot', POSTGRES_USER: 'auraboot', POSTGRES_PASSWORD: 'auraboot_dev',
  });
  assert.match(postgres.container_name, /AURA_OSS_CI_POSTGRES_CONTAINER:\?/);
  assert.match(postgres.ports[0], /AURA_OSS_CI_POSTGRES_PORT:\?.*:5432/);
  assert.match(redis.container_name, /AURA_OSS_CI_REDIS_CONTAINER:\?/);
  assert.match(redis.ports[0], /AURA_OSS_CI_REDIS_PORT:\?.*:6379/);
  assert.deepEqual(redis.profiles, ['skills-c2-stack']);
});

test('backend CI runner keeps external DashScope checks out unless explicitly requested', () => {
  assert.match(source, /AURA_CI_INCLUDE_DASHSCOPE_LIVE:-0/);
  assert.match(source, /unset DASHSCOPE_API_KEY/);
  assert.match(source, /DashScope live checks disabled/);
});

test('backend CI runner executes isolated bootstrap verification only after the shared suite', () => {
  const buildSource = readFileSync(gradleBuild, 'utf8');
  assert.match(source, /--continue cleanTest test bootstrapBillingAccountTest/);
  assert.match(buildSource, /excludeTags 'destructive-bootstrap'/);
  assert.match(buildSource, /mustRunAfter tasks\.named\('test'\)/);
  assert.match(buildSource, /outputs\.upToDateWhen \{ false \}/);
});

test('bootstrap task has a separately migrated database and refuses implicit shared URLs', () => {
  const buildSource = readFileSync(gradleBuild, 'utf8');
  const fixture = readFileSync(path.join(here, '..', 'platform', 'src', 'test', 'java',
    'com', 'auraboot', 'framework', 'saas', 'bootstrap', 'BootstrapBillingAccountIT.java'), 'utf8');
  assert.match(source, /CREATE DATABASE aura_boot_bootstrap OWNER auraboot/);
  assert.match(source, /run_flyway migrate aura_boot_bootstrap/);
  assert.match(source, /run_flyway validate aura_boot_bootstrap/);
  assert.match(source, /BOOTSTRAP_TEST_DATABASE_URL="jdbc:postgresql:[^\n]+aura_boot_bootstrap/);
  assert.match(buildSource, /BOOTSTRAP_TEST_DATABASE_URL is required/);
  assert.match(fixture, /bootstrap verification requires its own blank migrated database/);
  assert.doesNotMatch(fixture, /TRUNCATE TABLE|DELETE FROM|reset-db\.sh/);
});

test('ArchUnit uses a copy of the committed store without refreezing new violations', () => {
  const buildSource = readFileSync(gradleBuild, 'utf8');
  assert.match(buildSource, /test-fixtures\/archunit_store/);
  assert.match(buildSource, /from file\('src\/test\/resources\/archunit_store'\)/);
  assert.match(buildSource, /archunit\.freeze\.store\.default\.allowStoreCreation', 'false'/);
  assert.doesNotMatch(buildSource, /freeze\.refreeze/);
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
