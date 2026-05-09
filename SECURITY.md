# Security Policy

## Supported Versions

| Version          | Supported       | Notes |
| ---------------- | --------------- | ----- |
| 0.1.x-beta       | ✅ best-effort | First public beta. Security fixes ship in the next minor (no separate patch line during beta). |
| 0.x.y (post-beta)| ✅ patch line  | Latest 0.x minor receives patch fixes. |
| 1.0.x and later  | ✅              | Patch + supported-minor policy. Strict semver from 1.0.0. |
| pre-0.1.0        | ❌              | Not supported; use a tagged release. |

During the 0.x beta, "best-effort" means we triage and fix verifiable
security issues, but cannot commit to formal SLA windows or backports
to older betas. Severity-driven targets:

- **Critical** (RCE, auth bypass, mass data exposure): aim to patch in the
  next release within 7 days; advisory issued.
- **High**: aim to patch in the next minor (target 30 days).
- **Medium / Low**: tracked publicly, scheduled by roadmap priority.

## Reporting a Vulnerability

We take the security of AuraBoot seriously. If you discover a security vulnerability, please report it responsibly.

**Email:** [security@auraboot.com](mailto:security@auraboot.com)

**Please include the following in your report:**

- Description of the vulnerability
- Steps to reproduce the issue
- Affected version(s)
- Potential impact assessment
- Any suggested fix (optional)

**Please do NOT:**

- Open a public GitHub issue for security vulnerabilities
- Disclose the vulnerability publicly before it has been addressed
- Exploit the vulnerability beyond what is necessary to demonstrate it

## Response Timeline

- **Acknowledgment:** Within 48 hours of receiving your report
- **Initial assessment:** Within 7 business days
- **Resolution target:** Depends on severity; critical issues will be prioritized for immediate patching

## Disclosure Policy

We follow a coordinated disclosure process:

1. You report the vulnerability privately to us.
2. We investigate and confirm the issue.
3. We develop and test a fix.
4. We release the fix and publish a security advisory.
5. After the fix is released, you may publicly disclose the vulnerability.

We will credit reporters in security advisories unless anonymity is requested.

## Scope

This policy applies to the AuraBoot platform codebase, including:

- Backend (Spring Boot application)
- Frontend (React application)
- BFF proxy layer
- Plugin system
- Authentication and authorization mechanisms

Third-party dependencies are out of scope but we appreciate reports about known vulnerable dependencies.

## Contact

For security-related inquiries: [security@auraboot.com](mailto:security@auraboot.com)

For general questions: [https://www.auraboot.com/](https://www.auraboot.com/)
