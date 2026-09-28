#!/usr/bin/env bash

# Validate a Web Admin node_modules view before reusing it in another checkout.
# The caller supplies the node_modules directory, not the Web Admin root.
web_admin_node_modules_usable() {
  local candidate="$1"
  local candidate_real checkout_root checkout_node_modules_real entry entry_real
  local tailwind_entry router_bin router_dev_bin
  [ -d "$candidate" ] || return 1
  candidate_real="$(cd "$candidate" 2>/dev/null && pwd -P)" || return 1
  checkout_root="$(cd "$(dirname "$candidate")/.." 2>/dev/null && pwd -P)" || return 1
  checkout_node_modules_real=""
  if [ -d "$checkout_root/node_modules" ]; then
    checkout_node_modules_real="$(cd "$checkout_root/node_modules" 2>/dev/null && pwd -P)" || return 1
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
  node - "$candidate_real" "$checkout_node_modules_real" "$tailwind_entry" <<'NODE' >/dev/null 2>&1
const fs = require('node:fs');
const path = require('node:path');
const { createRequire } = require('node:module');

const [candidateRoot, checkoutNodeModules, tailwindEntry] = process.argv.slice(2);
const within = (root, target) => root && (target === root || target.startsWith(`${root}${path.sep}`));

try {
  const dependencyRequire = createRequire(tailwindEntry);
  const lightningEntry = fs.realpathSync(dependencyRequire.resolve('lightningcss'));
  if (!within(candidateRoot, lightningEntry) && !within(checkoutNodeModules, lightningEntry)) {
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
