#!/usr/bin/env bash
# Purge platform/src/main/resources/license/private.pem from the entire git
# history of this OSS repository.
#
# ⚠️  THIS IS A DESTRUCTIVE, HISTORY-REWRITING OPERATION.
#     - Every existing clone becomes incompatible
#     - Every collaborator must reclone after you force-push
#     - The original commit SHAs change
#     - You CANNOT undo this without restoring from a backup
#
# Run this BEFORE the repository is made public. After going public, the leaked
# blob is on the internet permanently and rotation (not purge) is the only
# remediation.
#
# Prerequisites:
#   - git-filter-repo installed:  brew install git-filter-repo
#                                 # or: pip install git-filter-repo
#   - Backup mirror clone (auto-created by this script in /tmp)
#   - All branches pushed to remote (so the backup is complete)
#   - Coordination with anyone else working on this repo
#
# After running:
#   1. Inspect git log to confirm the file is gone
#   2. Run the keypair rotation step (see KEYPAIR ROTATION section below)
#   3. Force-push: git push --force-with-lease origin --all && git push --force-with-lease origin --tags
#   4. All collaborators must: rm -rf <clone> && git clone fresh
#   5. Contact GitHub Support to purge cached views: https://support.github.com/

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TARGET_PATH="platform/src/main/resources/license/private.pem"
BACKUP_DIR="/tmp/auraboot-prepurge-backup-$(date +%Y%m%d-%H%M%S).git"

cd "$REPO_ROOT"

echo "==> Pre-flight checks"

if ! command -v git-filter-repo >/dev/null 2>&1; then
  echo "ERROR: git-filter-repo not installed."
  echo "       Install with: brew install git-filter-repo"
  exit 1
fi

if [ -f "$TARGET_PATH" ]; then
  echo "WARNING: $TARGET_PATH still exists in working tree."
  echo "         Remove it first, commit, then run this script."
  exit 1
fi

# Verify the file is actually in history
if ! git log --all --oneline -- "$TARGET_PATH" | grep -q .; then
  echo "INFO: $TARGET_PATH not found in any history. Nothing to purge."
  exit 0
fi

echo "==> Git history references to purge:"
git log --all --oneline -- "$TARGET_PATH"

echo ""
echo "==> Creating safety backup at: $BACKUP_DIR"
git clone --mirror "$REPO_ROOT" "$BACKUP_DIR"
echo "    Backup complete. To restore: git clone $BACKUP_DIR <new-location>"

echo ""
read -p "==> Type 'PURGE' to confirm history rewrite (anything else aborts): " CONFIRM
if [ "$CONFIRM" != "PURGE" ]; then
  echo "Aborted. Backup retained at $BACKUP_DIR for manual inspection."
  exit 1
fi

echo ""
echo "==> Running git-filter-repo --invert-paths --path $TARGET_PATH"
git filter-repo --invert-paths --path "$TARGET_PATH" --force

echo ""
echo "==> Verifying purge"
if git rev-list --all --objects | grep -q "$TARGET_PATH"; then
  echo "ERROR: $TARGET_PATH still appears in pack objects. Investigate manually."
  exit 1
fi

git log --all --oneline -- "$TARGET_PATH" || true

echo ""
echo "✅ Purge complete in local repo."
echo ""
echo "==================================================================="
echo "NEXT STEPS — DO NOT SKIP"
echo "==================================================================="
echo ""
echo "1. KEYPAIR ROTATION (required even after purge):"
echo "     Assume the key is compromised because it was committed."
echo "     a. Generate new keypair:"
echo "        openssl genrsa -out new-private.pem 4096"
echo "        openssl rsa  -in new-private.pem -pubout -out platform/src/main/resources/license/public.pem"
echo "     b. Store new-private.pem in your KMS / secret manager (NEVER commit)"
echo "     c. Re-sign all currently-issued commercial licenses with the new key"
echo "     d. Add a kid header so old + new licenses can coexist during transition"
echo ""
echo "2. RE-RUN GITLEAKS to confirm zero leaks:"
echo "     gitleaks detect --source . --no-banner --redact --config .gitleaks.toml"
echo ""
echo "3. FORCE-PUSH (after verifying new local history is correct):"
echo "     git remote remove origin              # filter-repo strips remotes by design"
echo "     git remote add origin <repo-url>"
echo "     git push --force-with-lease --mirror"
echo ""
echo "4. NOTIFY collaborators to reclone:"
echo "     Old SHAs are gone; rebases will conflict. Everyone runs:"
echo "       rm -rf <local-clone> && git clone <repo-url>"
echo ""
echo "5. GITHUB CACHE PURGE (after force-push):"
echo "     File a request to GitHub Support to invalidate cached blob views:"
echo "     https://support.github.com/contact/private-information"
echo ""
echo "Backup retained at: $BACKUP_DIR"
echo "Delete it ONLY after confirming the rewritten repo works end-to-end."
