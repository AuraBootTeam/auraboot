import assert from 'node:assert/strict';
import { readFileSync, statSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import test from 'node:test';

const here = path.dirname(fileURLToPath(import.meta.url));
const runner = path.join(here, 'oss-backend-unit-ci.sh');
const source = readFileSync(runner, 'utf8');

test('backend CI runner is executable and owns its complete infrastructure lifecycle', () => {
  assert.ok(statSync(runner).mode & 0o100);
  assert.match(source, /docker-compose\.skills-c2\.override\.yml/);
  assert.match(source, /up -d --wait postgres redis/);
  assert.match(source, /down --volumes --remove-orphans/);
  assert.match(source, /trap cleanup EXIT HUP INT TERM/);
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
  ]) {
    assert.match(source, new RegExp(image.replace(/[./:-]/g, '\\$&')));
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
  assert.match(source, /SPRING_DATA_REDIS_HOST='127\.0\.0\.1'/);
  assert.match(source, /SPRING_DATA_REDIS_PORT='26389'/);
});
