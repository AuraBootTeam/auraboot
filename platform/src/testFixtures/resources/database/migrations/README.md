# database/migrations — TEST FIXTURES ONLY

The scripts in this directory are applied by hand (or by specific integration
tests) to set up dedicated test schemas. They are **not** part of the
production schema evolution path and never ship in the distribution jar
(that is why they live under `src/testFixtures`, relocated here 2026-11-14
from `src/main/resources`).

## Where production schema changes go

Flyway, under `src/main/resources/db/migration/core/` (`V<timestamp>__*.sql`),
executed by `scripts/db/flyway-migrate.sh`. If you need a production schema
change, add a Flyway migration there — never a dated script here.
