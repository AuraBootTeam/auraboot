---
type: system-reference
status: active
created: 2026-10-03
updated: 2026-10-03
---

# Model mutation policy

Model policies apply inside the server write boundary, including requests from administrators. They complement command permissions and capability/record scopes; they do not grant authority to a command.

| Declaration | Contract |
| --- | --- |
| `immutable: true` | Reject later updates, state transitions and deletion, including authorized command deletion. The existing exact same-transaction insert-completion exception remains unchanged. |
| `commandOnlyCreate: true` | Reject generic creation without the trusted command permit scope. |
| `extension.commandOnlyDelete: true` | Reject generic single and batch deletion without that scope; permit lifecycle updates subject to field ownership and other policies. |

Deletion protection is declared in the existing model extension map, which plugin import, metadata normalization and Application Release definitions preserve:

```json
{
  "code": "oi_disclosure_package",
  "commandOnlyCreate": true,
  "extension": {
    "commandOnlyDelete": true
  }
}
```

The deletion value must be a JSON boolean. An absent value or `false` retains existing mutable-model deletion behavior. An invalid value fails closed when deletion is attempted. `immutable` takes precedence even when a command permit is present. A request payload cannot supply a permit; the command pipeline creates it after authorization.

`ModelMutationGuard.assertDeleteAllowed` runs before record reads and deletion SQL in `DynamicDataServiceImpl.delete` and `batchDelete`. Command field-map deletion and cascade deletion use the same guard. Soft and hard deletion share the single-record boundary. This policy does not replace entity-specific freeze/revoke rules, content integrity checks, audit retention, or independent download authorization.

The disclosure package and audience grant use command-only deletion while retaining review, freeze and revocation updates. They currently declare no deletion command. The access log remains immutable, so a command permit cannot erase its history.

Hermetic contracts reside in `ModelMutationGuardTest`, `DynamicDataServiceAtomicIncrementGuardTest` and `ModelDefinitionDTOTest`. A passing unit contract is not evidence of deployed plugin metadata or HTTP refusal; the AMOS fresh-runtime probe must verify both delete routes and unchanged retained records against the new Core and Plugins commits.
