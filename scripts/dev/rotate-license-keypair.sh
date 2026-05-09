#!/usr/bin/env bash
# Rotate the AuraBoot commercial-license signing keypair.
#
# Run this AFTER purge-private-pem-from-history.sh has scrubbed the leaked
# private.pem from git history. The leaked key must be treated as compromised
# even after purge — anyone who cloned before the rewrite still has it.
#
# What this script does:
#   1. Generates a new RSA-4096 keypair into a chosen output directory
#      OUTSIDE the repo (default: $HOME/.auraboot/license-keys/<date>/)
#   2. Copies ONLY the public key into the repo at
#        platform/src/main/resources/license/public.pem
#   3. Reminds you to set the new active-kid in application.yml
#   4. Reminds you to re-sign already-issued customer licenses with the new key
#
# What this script does NOT do:
#   - Push the private key to KMS (depends on your KMS choice — manual)
#   - Re-issue customer licenses (your license-issuance system does that)
#   - Commit / push code changes (so you can review the diff first)
#   - Touch the JWKS endpoint (if you have one)

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
DATE_STAMP="$(date +%Y%m%d)"
OUT_DIR="${LICENSE_KEY_OUT_DIR:-$HOME/.auraboot/license-keys/$DATE_STAMP}"
NEW_KID="${NEW_KID:-v$(date +%Y%m%d)}"

cd "$REPO_ROOT"

echo "==> AuraBoot License Keypair Rotation"
echo ""
echo "    Output directory: $OUT_DIR"
echo "    New kid:          $NEW_KID"
echo "    Repo root:        $REPO_ROOT"
echo ""

# --- Pre-flight ---
if ! command -v openssl >/dev/null 2>&1; then
  echo "ERROR: openssl not installed."
  exit 1
fi

if [ -f "platform/src/main/resources/license/private.pem" ]; then
  echo "ERROR: platform/src/main/resources/license/private.pem still exists in working tree."
  echo "       Refusing to overwrite. Investigate manually."
  exit 1
fi

if [ -d "$OUT_DIR" ] && [ "$(ls -A "$OUT_DIR" 2>/dev/null)" ]; then
  echo "ERROR: $OUT_DIR is non-empty. Refusing to overwrite existing keys."
  echo "       Choose a different LICENSE_KEY_OUT_DIR or remove the directory first."
  exit 1
fi

mkdir -p "$OUT_DIR"
chmod 700 "$OUT_DIR"

# --- Generate ---
echo "==> Generating RSA-4096 keypair"
openssl genrsa -out "$OUT_DIR/private-$NEW_KID.pem" 4096 2>/dev/null
openssl rsa -in "$OUT_DIR/private-$NEW_KID.pem" \
            -pubout \
            -out "$OUT_DIR/public-$NEW_KID.pem" 2>/dev/null
chmod 600 "$OUT_DIR/private-$NEW_KID.pem"
chmod 644 "$OUT_DIR/public-$NEW_KID.pem"

echo "    private: $OUT_DIR/private-$NEW_KID.pem"
echo "    public:  $OUT_DIR/public-$NEW_KID.pem"
echo ""

# --- Stage public key into repo ---
TARGET_PUBLIC="platform/src/main/resources/license/public.pem"
echo "==> Copying public key into repo: $TARGET_PUBLIC"
cp "$OUT_DIR/public-$NEW_KID.pem" "$TARGET_PUBLIC"

echo ""
echo "==> Diff vs HEAD:"
git diff --stat "$TARGET_PUBLIC" || true

# --- Verify the keys match ---
echo ""
echo "==> Verifying keypair self-consistency"
TEST_INPUT=$(mktemp)
TEST_SIG=$(mktemp)
echo "auraboot-license-rotation-test" > "$TEST_INPUT"
openssl dgst -sha256 -sign "$OUT_DIR/private-$NEW_KID.pem" -out "$TEST_SIG" "$TEST_INPUT"
if openssl dgst -sha256 -verify "$OUT_DIR/public-$NEW_KID.pem" -signature "$TEST_SIG" "$TEST_INPUT" 2>&1 | grep -q "Verified OK"; then
  echo "    ✅ keypair verified"
else
  echo "    ❌ keypair self-test FAILED — investigate before using"
  exit 1
fi
rm -f "$TEST_INPUT" "$TEST_SIG"

# --- Next steps ---
cat <<EOF

==================================================================
NEXT STEPS — DO NOT SKIP
==================================================================

1. STORE THE PRIVATE KEY in your KMS / vault:
     - Recommended: AWS KMS / GCP KMS / HashiCorp Vault / 1Password
     - If using KMS, import the private key and reference it via key alias
     - If using a secrets manager, store the PEM contents under a clear name
       like "auraboot/license/private-$NEW_KID"
     - DELETE $OUT_DIR after the key is safely stored:
         rm -rf $OUT_DIR

2. UPDATE application.yml to set the new active-kid:
     auraboot:
       license:
         offline-license:
           active-kid: $NEW_KID         # <-- new
           public-key-path: classpath:license/public.pem

3. ENSURE THE VERIFIER KNOWS HOW TO HANDLE BOTH OLD + NEW KIDS:
     - During the transition, customer licenses signed with the OLD kid (v1)
       must still verify. Either:
       (a) keep the OLD public key in the repo as public-v1.pem and have the
           verifier load both keys keyed by kid header, OR
       (b) issue an end-of-life date for v1-signed licenses and re-sign all
           customers before that date (cleaner but harder to coordinate).
     - The recommended path is (a). Code change in
       platform/src/main/java/com/auraboot/license/* needed: make
       public-key-path map kid -> path so multiple keys can be loaded.

4. RE-SIGN ALREADY-ISSUED CUSTOMER LICENSES with the new key:
     - If your license issuance system has a reissue endpoint, run it for
       every active customer.
     - Communicate the rotation: customers with offline licenses need to
       receive new license files.

5. COMMIT + PUSH the public key change:
     git add platform/src/main/resources/license/public.pem
     git -c user.signingkey= commit -m "chore(license): rotate signing keypair to $NEW_KID"
     git push origin main

6. UPDATE OPS RUNBOOK noting:
     - Old keypair compromised on YYYY-MM-DD (date of git purge)
     - Rotated to kid=$NEW_KID on $DATE_STAMP
     - Next planned rotation: $DATE_STAMP + 12 months

DO NOT proceed to step 5 until step 1 (KMS storage) is verified and you have
copied the private key out of $OUT_DIR.

EOF
