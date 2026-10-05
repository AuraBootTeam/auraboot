#!/usr/bin/env bash

# Validate a Web Admin node_modules view before reusing it in another checkout.
# The caller supplies the node_modules directory, not the Web Admin root.
web_admin_node_modules_usable() {
  local candidate="$1"
  local candidate_real checkout_root checkout_node_modules_real entry entry_real
  local tailwind_entry router_bin router_dev_bin capsule_virtual_store
  capsule_virtual_store=""
  [ -d "$candidate" ] || return 1
  candidate_real="$(cd "$candidate" 2>/dev/null && pwd -P)" || return 1
  checkout_root="$(cd "$(dirname "$candidate")/.." 2>/dev/null && pwd -P)" || return 1
  checkout_node_modules_real=""
  if [ -d "$checkout_root/node_modules" ]; then
    checkout_node_modules_real="$(cd "$checkout_root/node_modules" 2>/dev/null && pwd -P)" || return 1
  fi

  # Published capsule package links resolve into their sibling .pnpm store.
  # Accept that boundary only when the owner receipt proves this exact view;
  # otherwise a healthy capsule is replaced by a mutable sibling checkout.
  if [ -f "$(dirname "$candidate_real")/.aura-dependency-capsule.json" ]; then
    capsule_virtual_store="$(node - "$candidate_real" <<'NODE'
const fs = require('node:fs');
const path = require('node:path');
const view = process.argv[2];
try {
  const root = path.dirname(view);
  const receipt = JSON.parse(fs.readFileSync(path.join(root, '.aura-dependency-capsule.json'), 'utf8'));
  const key = `sha256:${path.basename(root)}`;
  const store = path.join(root, '.pnpm');
  if (receipt.schemaVersion !== 1 || receipt.dependencyKey !== key
      || !/^sha256:[a-f0-9]{64}$/.test(key) || receipt.dependenciesDir !== view
      || receipt.virtualStoreDir !== store || receipt.platform !== process.platform
      || receipt.arch !== process.arch || receipt.nodeAbi !== process.versions.modules
      || fs.realpathSync(store) !== store) process.exit(1);
  process.stdout.write(store);
} catch { process.exit(1); }
NODE
    )" || return 1
  fi

  for entry in \
    react/index.js \
    react-dom/client.js \
    @tailwindcss/vite/dist/index.mjs \
    tailwindcss/index.css \
    @react-router/dev/bin.js
  do
    [ -r "$candidate/$entry" ] || return 1
    entry_real="$(realpath "$candidate/$entry" 2>/dev/null)" || return 1
    case "$entry_real" in
      "$candidate_real"/*) continue ;;
      "$capsule_virtual_store"/*) [ -n "$capsule_virtual_store" ] && continue ;;
    esac
    [ -n "$checkout_node_modules_real" ] || return 1
    case "$entry_real" in
      "$checkout_node_modules_real"/*) ;;
      *) return 1 ;;
    esac
  done

  # pnpm writes a real shim here rather than a symlink. Both the shim and its
  # package target must be present; incomplete copied capsules fail here.
  router_bin="$candidate/.bin/react-router"
  router_dev_bin="$candidate/@react-router/dev/bin.js"
  [ -x "$router_bin" ] && [ -r "$router_dev_bin" ] || return 1

  # Loading lightningcss is the reliable ABI check. Package names alone cannot
  # distinguish Linux musl from glibc. Resolve from Tailwind's real package
  # context, enforce the dependency boundary, then execute a native transform.
  tailwind_entry="$(realpath "$candidate/@tailwindcss/vite/dist/index.mjs" 2>/dev/null)" || return 1
  node - "$candidate_real" "$checkout_node_modules_real" "$tailwind_entry" "$capsule_virtual_store" <<'NODE' >/dev/null 2>&1
const fs = require('node:fs');
const path = require('node:path');
const { createRequire } = require('node:module');

const [candidateRoot, checkoutNodeModules, tailwindEntry, capsuleStore] = process.argv.slice(2);
const within = (root, target) => root && (target === root || target.startsWith(`${root}${path.sep}`));

try {
  const dependencyRequire = createRequire(tailwindEntry);
  const lightningEntry = fs.realpathSync(dependencyRequire.resolve('lightningcss'));
  if (!within(candidateRoot, lightningEntry) && !within(checkoutNodeModules, lightningEntry) && !within(capsuleStore, lightningEntry)) {
    process.exit(1);
  }
  const lightningcss = dependencyRequire('lightningcss');
  const result = lightningcss.transform({
    filename: 'aura-node-modules-abi-probe.css',
    code: Buffer.from('.a{color:red}'),
    minify: true,
  });
  if (!result || !Buffer.isBuffer(result.code) || result.code.length === 0) process.exit(1);
} catch {
  process.exit(1);
}
NODE
}

# .bin shims resolve package targets through $0-relative paths, so a view can pass every
# readability probe and still be unexecutable once it is symlinked from a checkout with a
# different workspace-store layout. Probe the bins the stack supervisor (dev:full) needs.
web_admin_supervisor_bins_executable() {
  "$1/.bin/concurrently" --version >/dev/null 2>&1
}

