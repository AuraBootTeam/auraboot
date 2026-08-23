import assert from 'node:assert/strict';
import { readFileSync, statSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import test from 'node:test';

const here = path.dirname(fileURLToPath(import.meta.url));
const runner = path.join(here, 'oss-backend-unit-ci.sh');
const composeOverride = path.join(here, '..', 'docker-compose.oss-backend-ci.override.yml');
const source = readFileSync(runner, 'utf8');
const escapeRegex = (value) => value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

test('backend CI runner is executable and owns its complete infrastructure lifecycle', () => {
  assert.ok(statSync(runner).mode & 0o100);
  assert.match(source, /docker-compose\.skills-c2\.override\.yml/);
  assert.match(source, /up -d --wait postgres redis/);
  assert.match(source, /down --volumes --remove-orphans/);
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
  assert.match(source, /platform\/gradlew -p platform test\s*$/);
  assert.doesNotMatch(source, /platform\/gradlew[^\n]*\|\| environment_invalid/);
});

test('backend CI runner points fixed-stack tests at the isolated host ports', () => {
  assert.match(source, /TEST_DATABASE_URL='jdbc:postgresql:\/\/127\.0\.0\.1:25442\/aura_boot/);
  assert.match(source, /TEST_DATABASE_USERNAME='auraboot'/);
  assert.match(source, /TEST_DATABASE_PASSWORD='auraboot_dev'/);
  assert.match(source, /SPRING_DATASOURCE_URL='jdbc:postgresql:\/\/127\.0\.0\.1:25442\/aura_boot/);
  assert.match(source, /SPRING_DATASOURCE_USERNAME='auraboot'/);
  assert.match(source, /SPRING_DATASOURCE_PASSWORD='auraboot_dev'/);
  assert.match(source, /SPRING_DATA_REDIS_HOST='127\.0\.0\.1'/);
  assert.match(source, /SPRING_DATA_REDIS_PORT='26389'/);
  assert.match(source, /SPRING_DATA_REDIS_URL='redis:\/\/127\.0\.0\.1:26389'/);
});
