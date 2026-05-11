# AuraBoot License FAQ

> **Disclaimer:** This FAQ is an informal guide to help you understand [`LICENSE.txt`](./LICENSE.txt). It is **not a legal document** and has no legal effect. If anything in this FAQ conflicts with `LICENSE.txt`, **the English original of `LICENSE.txt` controls**. For binding interpretations or material business decisions, consult a lawyer or contact us through https://www.auraboot.com/contact.
>
> Last updated: 2026-05-10 (LICENSE v1.3)

---

## 1. Nature of the License

### Q1. Is AuraBoot open source?
**Technically, it is "source-available with commercial restrictions."** AuraBoot's license is built on Apache License 2.0 with supplementary terms — primarily restrictions on reselling AuraBoot itself as a low-code / no-code / AI platform SaaS (see §5.4).

It is **not OSI-approved**, because OSI's definition disallows field-of-use restrictions. Comparable projects in this category: Sentry, PostHog, Cal.com, n8n, CockroachDB, HashiCorp.

### Q2. Why not pure Apache-2.0 / MIT / AGPL?
- **Pure Apache/MIT** doesn't prevent cloud vendors from packaging AuraBoot as a managed service without contributing back.
- **AGPL** is widely banned by enterprise legal teams, blocking legitimate self-hosted customers.
- **This license** aims to be friendly for individuals, internal use, and ISVs — while charging only those who repackage AuraBoot itself as a low-code platform SaaS.

### Q3. Will the license change unexpectedly?
Future versions may be revised, but **published versions remain valid in perpetuity for the code released under them**. New versions only apply to newly released code. We will not pull an "ElasticSearch / Redis" — no retroactive license changes on already-released code. This is a baseline community trust commitment.

---

## 2. Self-Hosting & Internal Use

### Q4. Can I deploy AuraBoot internally for my company employees? Do I need to pay?
**No payment needed.** Commercial use is permitted (§5.1). You may modify the source code as long as you preserve copyright notices (§5.3) and brand information (§5.2).

### Q5. Can I modify the source? Do I have to open-source my changes?
**Yes, you can modify. No, you don't have to open-source your changes.** Whether you deploy internally or deliver to customers, this license never requires you to publish your source — fundamentally different from AGPL/GPL.

You may keep modifications private even when deploying for customers. We encourage but do not require contributing general bug fixes / improvements upstream via PR.

### Q6. I'm an ISV delivering a project to a customer's own infrastructure. Does that count as distribution?
**Yes, it's distribution — but it's fully permitted, with no obligation to ship source code.** All you need to do:
- Preserve `LICENSE.txt` and copyright notices (§5.1, §5.3)
- Preserve AuraBoot brand identity, including the upper-left main logo, footer, about page, and other visible brand information (§5.2)
- Don't resell it as a low-code / AI platform SaaS service (§5.4)

Your modifications, your business code, your plugins — **all may remain closed source**. This ISV / system integrator scenario is explicitly supported and friendly under this license.

### Q7. Can I keep a private long-term fork in my own repository?
**Yes.** A private fork is not distribution. As long as you don't publish it externally, you have no obligation to make the source public.

---

## 3. Branding & White-Labeling

### Q8. Can I remove AuraBoot's logo and name?
- **The upper-left main logo:** must remain visible and may not be replaced (§5.2).
- **Footer "Powered by AuraBoot," about page, copyright statements:** must remain visible.
- **Copyright headers, license headers, attribution comments in the source:** must remain (§5.3).
- **Any white-labeling or removal of branding:** requires a commercial license.

### Q9. My product is built on AuraBoot, but customers shouldn't see "AuraBoot" anywhere. Is that allowed?
If you only use AuraBoot to configure business applications (ERP / CRM / internal systems) and end users mainly interact with **your business UI**, this is fine. But administrative / configuration / about pages that expose the platform layer must keep AuraBoot branding — unless you obtain a commercial license.

---

## 4. SaaS / Platform Services (Critical Boundary)

### Q10. What constitutes a "low-code / AI platform SaaS" prohibited by §5.4?
**The test:** Is the value you're selling a "business application" or "the low-code capability itself"?

| Scenario | Allowed? |
|---|---|
| You build an ERP SaaS on AuraBoot; customers use ERP features | ✅ Allowed (community version is fine) |
| You build a project management SaaS; customers use PM features | ✅ Allowed |
| You build an "online form / online low-code platform" SaaS where customers configure their own apps | ❌ Prohibited; commercial license required |
| You offer "managed AuraBoot hosting"; customers log in and see AuraBoot's configuration UI | ❌ Prohibited |
| You wrap AuraBoot's API as an "app builder API" and resell it | ❌ Prohibited |
| You deliver a single-tenant low-code platform privately to one customer (customer-owned) | ✅ Allowed (counts as ISV delivery) |
| Multiple customers share one AuraBoot instance, each configuring their own apps | ❌ Multi-tenant SaaS — prohibited |

**Core principle:** "primary value proposition is platform development capability vs. specific business applications" is the boundary line (§7.4).

### Q11. I'm building a vertical-industry SaaS (e.g., restaurant SaaS, education SaaS) on AuraBoot. Is this allowed, even multi-tenant?
**Yes, even multi-tenant is allowed.** Because what you sell is "restaurant / education business applications," not a "general low-code platform." Tenants use your pre-configured business features, not low-code authoring.

If your product also exposes "tenants can use low-code to modify their own business workflows," the boundary becomes ambiguous — please contact us through https://www.auraboot.com/contact to confirm.

### Q12. Does a commercial license unlock multi-tenant low-code SaaS?
**No, not by itself.** Per §5.4 (and reinforced by §7.3), platform-as-a-service distribution requires **both** a Commercial License AND an explicit written grant of SaaS / OEM distribution rights for the specific deployment. A standard Commercial License (e.g., for white-labeling or removing branding) does not by itself authorize you to host AuraBoot itself as a multi-tenant low-code platform service. If that's your business model, a separate SaaS / OEM rider or addendum to your Commercial License is required. Contact us through https://www.auraboot.com/contact to negotiate the rider.

---

## 5. Development & Contribution

### Q13. What do I need to sign to submit a PR?
A **CLA (Contributor License Agreement)**. The CLA grants The AuraBoot Project the rights needed to legally distribute your contribution under both the community and commercial licenses.

The CLA does not require you to surrender copyright — it grants the project a long-term, irrevocable license to use your contribution. A CLA bot will guide you on your first PR.

### Q14. Will my PR be used in the commercial version?
**Yes.** That's one of the purposes of the CLA. Your contribution will appear in both community and commercial editions. If you don't accept this, please don't submit PRs.

### Q15. Can I build and sell plugins for AuraBoot?
**Yes.** Plugins are independent works and may use any license you choose, including closed-source commercial. AuraBoot's license does not propagate to plugin code.

If your plugin **directly copies AuraBoot core code**, that copied portion remains under this license. We recommend integrating via our extension points / SPI / DSL rather than forking core code.

---

## 6. Commercial License

### Q16. When do I need a commercial license?
- ✅ You want to remove all branding (white-label) — Commercial License sufficient
- ✅ Your legal team won't accept non-OSI-approved licenses (rare but real) — Commercial License sufficient
- ✅ You need official technical support / SLA / priority bug fixes — Commercial License sufficient
- ✅ You require custom development / private features / proprietary plugins — Commercial License sufficient
- ⚠️ You want to operate a multi-tenant low-code / AI platform SaaS resale business — **Commercial License + written SaaS / OEM rider required** (see Q12)

Self-hosting only, ISV project delivery only, vertical business SaaS only — **none of these require a commercial license.**

### Q17. How do I obtain a commercial license?
Use the website contact form at https://www.auraboot.com/contact.

### Q18. Is the commercial license perpetual or subscription-based?
Specific terms are governed by the commercial agreement signed between parties. Generally we offer two options: perpetual buy-out and annual subscription (the latter includes upgrades and support).

---

## 7. Compliance & Enforcement

### Q19. What happens if I violate the license?
§6.1's 30-day cure period means that if you breach the Community License, you have 30 days after becoming aware of the breach to fix the issue. If you cure the breach within that period, the Community License will not terminate because of that breach; if you do not cure it within 30 days, **you must cease all use and destroy all copies**. If the violation causes commercial damage (e.g., unauthorized SaaS resale), the Licensor reserves the right to pursue legal remedies.

### Q20. Which jurisdiction governs?
The Community License does not designate an exclusive governing law, court, arbitration institution, or dispute-resolution forum (§8.1). If the parties sign a Commercial License or another written agreement, that agreement may specify the governing law and dispute-resolution mechanism (§8.2).

### Q21. Can commercial customers agree on a different forum?
Yes. For cross-border commercial customers, a commercial contract may specify the governing law and dispute-resolution forum (e.g., Singapore / Hong Kong / the customer's jurisdiction). The commercial contract controls over the Community License's general terms for the covered parties and scope.

---

## 8. Comparison with Other Projects

### Q22. How does AuraBoot's license compare to Appsmith / ToolJet / Budibase?

| Project | License | Multi-tenant SaaS Restriction | Branding Removable? |
|---|---|---|---|
| **AuraBoot** | Apache-2.0 + supplementary terms | Prohibits low-code / AI platform SaaS resale | No removal or replacement |
| Appsmith | Apache-2.0 | None | Fully removable |
| ToolJet | AGPLv3 | Must open-source all modifications | Fully removable |
| Budibase | GPL v3 + Commercial | Must open-source all modifications (GPL) | Commercial version only |

**Summary:** For self-hosting, ISV project delivery, and vertical SaaS scenarios, AuraBoot is more friendly than AGPL/GPL projects (your business code stays closed). Versus pure Apache projects, AuraBoot adds one extra guardrail against cloud-vendor freeloading.

---

## 9. Still Have Questions?

- **Legal questions:** https://www.auraboot.com/contact
- **Contribution questions:** see CONTRIBUTING.md
- **Security disclosure:** see SECURITY.md
- **Commercial / OEM partnerships:** https://www.auraboot.com/contact

This FAQ is updated based on community feedback. Open a GitHub Issue if you have a question — frequently asked ones get added here.
