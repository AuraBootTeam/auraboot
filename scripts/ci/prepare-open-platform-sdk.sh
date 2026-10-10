#!/bin/sh
# Run in the frozen Node image. Source is read-only; all outputs are task-owned.
set -eu
[ "$#" = 2 ] || { printf 'usage: prepare-open-platform-sdk.sh SOURCE OUTPUT\n' >&2; exit 2; }
SOURCE="$1"
OUTPUT="$2"
[ ! -e "$OUTPUT" ] || { printf 'SDK output already exists\n' >&2; exit 2; }
mkdir -p "$OUTPUT/build/packages" "$OUTPUT/archive" "$OUTPUT/consumer" "$OUTPUT/toolchain"
cp "$SOURCE/scripts/fixtures/open-platform-consumer/toolchain/"*.json "$OUTPUT/toolchain/"
export npm_config_cache="$OUTPUT/npm-cache"
export npm_config_fetch_retries=0
export npm_config_fetch_timeout=120000
npm ci --prefix "$OUTPUT/toolchain" --ignore-scripts --no-audit --no-fund
cp -R "$SOURCE/packages/open-platform-sdk" "$OUTPUT/build/packages/"
cp "$SOURCE/packages/tsconfig.base.json" "$OUTPUT/build/packages/"
# A tracked or residual dist must never serve as fresh-build evidence.
rm -rf "$OUTPUT/build/packages/open-platform-sdk/dist"
node "$OUTPUT/toolchain/node_modules/typescript/bin/tsc" \
  -p "$OUTPUT/build/packages/open-platform-sdk/tsconfig.build.json"
(
  cd "$OUTPUT/build/packages/open-platform-sdk"
  npm pack --ignore-scripts --pack-destination "$OUTPUT/archive" --json > "$OUTPUT/pack.json"
)
ARCHIVE="$(node -e 'const fs=require("fs"); const rows=JSON.parse(fs.readFileSync(process.argv[1])); if(rows.length!==1)process.exit(1); process.stdout.write(rows[0].filename)' "$OUTPUT/pack.json")"
printf '{"private":true,"type":"module"}\n' > "$OUTPUT/consumer/package.json"
cp "$SOURCE/scripts/fixtures/open-platform-consumer/real-backend.mjs" \
  "$SOURCE/scripts/fixtures/open-platform-consumer/real-backend.profile.json" "$OUTPUT/consumer/"
npm install --prefix "$OUTPUT/consumer" --offline --ignore-scripts --no-audit --no-fund "$OUTPUT/archive/$ARCHIVE"
node --version > "$OUTPUT/node-version.txt"
node "$OUTPUT/toolchain/node_modules/typescript/bin/tsc" --version > "$OUTPUT/compiler-version.txt"
