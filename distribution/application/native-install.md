# Install an unpublished Core foundation candidate

This candidate is a Linux amd64 verification artifact, not a published registry
release. Keep its original archive, SHA-256, `application.lock` and
`release-receipt.json` together. Read `LICENSE.txt` and `NOTICE` for the bundled
terms; a website proposal does not replace them.

The foundation includes metadata, page configuration, organization, platform
administration and ownership configuration. It is not the Enterprise Showcase.
Configuration imports follow the verified lock's composition order. Older
candidates retain their own original configuration inventory.

## 1. Prepare prerequisites and persistent configuration

Use Node.js 20+, the capsule's pinned pnpm (currently 9.15.9), Java 21, Python 3,
Flyway, `curl`, `lsof`, and PostgreSQL client tools. The verification database is
PostgreSQL 16 with pgvector. For backup and restore, use client tools matching the
source server major version; do not remove rejected SQL from a backup to make it
restore. The candidate does not install or reset shared infrastructure.

Extract to a new directory and verify the archive hash against the supplied
SHA-256 before using it. From the extracted root, create a private configuration
file **once**, outside the artifact directory:

```bash
umask 077
python3 - <<'PY'
import base64, os, pathlib, secrets, shlex
file = pathlib.Path('../core-runtime.env')
values = {
    'PGHOST': '127.0.0.1', 'PGPORT': '5432', 'PGUSER': 'auraboot',
    'PGDATABASE': 'auraboot_core_1', 'PGPASSWORD': '',
    'ADMIN_EMAIL': 'admin@example.com', 'ADMIN_PASSWORD': 'Admin-' + secrets.token_hex(20),
    'JWT_SECRET': secrets.token_urlsafe(48), 'SESSION_SECRET': secrets.token_urlsafe(48),
    'OPEN_PLATFORM_PROTOCOL_SIGNING_KEY': secrets.token_hex(32),
    'FIELD_ENCRYPTION_KEY': base64.b64encode(secrets.token_bytes(32)).decode(),
}
with file.open('x') as output:  # Existing configuration is never overwritten.
    os.chmod(file, 0o600)
    output.writelines(key + '=' + shlex.quote(value) + '\n' for key, value in values.items())
print('Private configuration created; edit database settings before continuing.')
PY
```

Edit that private file to set your database credentials and administrator email.
Keep the generated encryption/session/signing keys and administrator password
private. Reuse this file on restart and upgrade; do not regenerate the keys.

```bash
set -a
. ../core-runtime.env
set +a
export AURA_APP_ARTIFACT_ROOT="$(pwd -P)"
export AURA_STATE_ROOT="$(cd .. && pwd -P)/core-state"
export AURA_BACKEND_PORT=6601 AURA_WEB_PORT=5601
```

The state path must stay outside the extracted artifact and remain stable across
upgrades. Local file storage defaults to `$AURA_STATE_ROOT/files`; set
`AURA_FILE_STORAGE_PATH` explicitly only when using another persistent location.

## 2. Initialize an empty database and sign in

```bash
bash bin/auraboot-core-env.sh preflight
bash bin/auraboot-core-env.sh create-db
bash bin/auraboot-core-env.sh migrate
bash bin/auraboot-core-env.sh start
bash bin/auraboot-core-env.sh init-core
bash bin/auraboot-core-env.sh publish
bash bin/auraboot-core-env.sh verify
```

Open `http://localhost:5601`, sign in using the administrator from the private
configuration file and select the business space. You should see Models and Page
Configuration. Create a physical model, create/bind fields, publish it and use the
standard CRUD generator. Save a record, reopen it, refine a generated form in the
Unified Designer, then check the actual runtime page. No visual configuration or
business React is required.

`init-core` is for first installation, not upgrade or restore. `publish` and
`verify` obtain a real business-space session. If an administrator has multiple
business spaces, set `AURA_ADMIN_TENANT_ID` explicitly.

## 3. Stop or upgrade without replacing business data

```bash
bash bin/auraboot-core-env.sh stop
```

Stop proves process ownership, start time, process group, directory and ports.
Unregistered legacy PID files are refused; inspect ownership rather than deleting
an active process's record. Stop does not remove the database, file storage or
historical evidence.

Before upgrading, back up the database, persistent files and private configuration
with writes stopped. Extract and verify the new candidate separately. Reuse the
same database, state path and keys; point `AURA_APP_ARTIFACT_ROOT` to the new root,
then run its `migrate`, `start`, `publish` and `verify` actions. Do not run
`init-core`, drop the database or apply a new empty schema snapshot over it.

If candidate verification fails, keep the current directory and running business
intact. Do not fall back to an unverified directory or relabel older artifacts as
the new version. A failed data migration requires the matching backup/recovery
procedure, not just switching a version label.

## 4. Back up and recover

With business writes stopped, create a custom-format PostgreSQL backup using the
source server's major-version `pg_dump`. Copy the persistent file directory and
retain the matching private encryption configuration in the protected backup.
Database-only recovery does not recover local attachment bytes.

Restore into a separate, empty database using compatible `pg_restore` with
`--exit-on-error`. Restore the file directory to the new state's `files` path,
reuse the encryption keys, and set the restored database coordinates. Run
`migrate`, `start`, `publish` and `verify`, then independently check records,
attachment download hashes, role permissions, rejected writes and command audit
history. Retain the previous database and evidence until recovery is accepted.
