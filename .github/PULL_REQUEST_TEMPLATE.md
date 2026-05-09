<!-- Thanks for contributing to AuraBoot! 🚀 -->
<!-- Before submitting, make sure your PR has been signed via the CLA bot (auto-prompted on first PR). -->

## What this PR does

<!-- One paragraph: what changes, what doesn't, who's affected. -->

## Why

<!-- Optional but appreciated: link to the issue / discussion / motivation. -->
<!-- Closes #N -->

## Type

- [ ] 🐛 Bug fix
- [ ] ✨ New feature
- [ ] ♻️ Refactor / cleanup
- [ ] 🧪 Tests only
- [ ] 📝 Documentation only
- [ ] 🔒 Security fix
- [ ] 💥 Breaking change

## Testing

<!-- How did you verify this works? "Existing tests pass" is fine for trivial PRs.
     For features, describe what manual / automated tests you ran. -->

- [ ] Existing tests still pass: `./gradlew test` and/or `pnpm test`
- [ ] New tests added (if applicable)
- [ ] Tested manually against a clean stack: `docker compose up -d` then verified the change

## Breaking changes (if any)

<!-- Skip this section for non-breaking PRs.
     Otherwise: describe the break + migration path. -->

**Affected**: <!-- which configs / APIs / users -->

**Migration**:
```diff
- old behavior
+ new behavior
```

## Checklist

- [ ] CLA signed (the bot prompts on first PR; reply with the sign-off line)
- [ ] Code follows the existing style (no unrelated formatting churn)
- [ ] Documentation updated (`docs/`, `CHANGELOG.md`, USAGE-FAQ if a behavior changed)
- [ ] No secrets or PII in the diff (gitleaks CI will block)
- [ ] No imports / references from `auraboot-enterprise/` (boundary CI will block)

## Screenshots (UI changes only)

<!-- Drop before/after screenshots here. -->
