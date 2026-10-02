# AuraBoot License FAQ

> **Disclaimer:** This FAQ is an informal guide to help you understand [`LICENSE.txt`](./LICENSE.txt). It is **not a legal document** and has no legal effect. If anything in this FAQ conflicts with `LICENSE.txt`, **the English original of `LICENSE.txt` controls**. For binding interpretations or material business decisions, consult a lawyer or contact us through https://www.auraboot.com/contact.
>
> Last updated: 2026-10-01 (LICENSE v2.0)

---

## 1. Nature of the License

### Q1. Is AuraBoot open source?
**Yes.** As of v2.0 the community distribution is licensed under the **Apache License 2.0** (LICENSE.txt Part 1, reproduced in full), supplemented only by trademark and commercial-contact provisions (Part 2, which restrict nothing granted by Part 1). The v1.x "field-of-use restriction" model is retired.

### Q2. Why aren't you worried about cloud vendors? Where is the moat?
- **The moat is the product, not license restrictions**: enterprise capabilities (advanced designers, industry plugins, governance components) live in a separate proprietary codebase licensed under commercial agreements — open code being resold as a service doesn't diminish proprietary code that was never open.
- **Brand protection** moved to trademark terms (Part 2 S3): reselling under the "AuraBoot" brand requires a trademark license; **running it under your own brand** is entirely free.
- Apache-2.0 has the highest enterprise-legal acceptance rate — the shortest path for self-hosted customers and ISVs alike.

### Q3. Will the license change unexpectedly?
Future versions may be revised, but **published versions remain valid in perpetuity for the code released under them**. New versions only apply to newly released code. We will not pull an "ElasticSearch / Redis" — no retroactive license changes on already-released code. This is a baseline community trust commitment.

---

## 2. Self-Hosting & Internal Use

### Q4. Can I deploy AuraBoot internally for my company employees? Do I need to pay?
**No payment needed.** Apache-2.0 permits any commercial use. Modifications are permitted too — the only obligations are preserving LICENSE/NOTICE copyright statements on distribution (Apache §4) and not packaging the product under the "AuraBoot" brand (Part 2 S3, trademark).

### Q5. Can I modify the source? Do I have to open-source my changes?
**Yes, you can modify. No, you don't have to open-source your changes.** Whether you deploy internally or deliver to customers, this license never requires you to publish your source — fundamentally different from AGPL/GPL.

You may keep modifications private even when deploying for customers. We encourage but do not require contributing general bug fixes / improvements upstream via PR.

### Q6. I'm an ISV delivering a project to a customer's own infrastructure. Does that count as distribution?
**Yes, it's distribution — but it's fully permitted, with no obligation to ship source code.** All you need to do:
- Preserve LICENSE/NOTICE and source copyright/license statements (Apache §4)
- Do not offer the product or service under the "AuraBoot" brand — deliver under your own brand (Part 2 S3)

Your modifications, your business code, your plugins — **all may remain closed source**. This ISV / system integrator scenario is exactly what Apache-2.0 supports.

### Q7. Can I keep a private long-term fork in my own repository?
**Yes.** A private fork is not distribution. As long as you don't publish it externally, you have no obligation to make the source public.

---

## 3. Branding & White-Labeling

### Q8. Can I remove AuraBoot's logo and name?
**Yes — whitelabeling freedom is explicitly protected in v2.0.** The restriction runs in the opposite direction: **trademark terms cover the "AuraBoot" name itself** (Part 2 S3):
- Replacing/removing AuraBoot branding, logo, footer, about page: fully permitted — use your own brand
- Copyright and license statements in code and distributions (LICENSE/NOTICE, source attributions): preserve per Apache §4
- Marketing or operating a product/service **under the "AuraBoot" brand**: requires a trademark license (Part 2 S3)

### Q9. My product is built on AuraBoot, but customers shouldn't see "AuraBoot" anywhere. Is that allowed?
**Yes.** You configure business applications (ERP/CRM/internal systems) on AuraBoot; end users see **your business UI under your brand** — exactly the model v2.0 supports. Descriptive mentions ("Powered by AuraBoot") are fair use: keep or remove, your choice.

---

## 4. SaaS / Hosting Services

### Q10. Is it allowed to run the community edition as a low-code/AI platform SaaS?
**Under your own brand: yes, including multi-tenant.** Apache-2.0 has no field-of-use restrictions; trademark is the only boundary:

| Scenario | Allowed? |
|---|---|
| ERP / PM / vertical-industry SaaS built on AuraBoot | ✅ community edition |
| Form-builder / low-code platform SaaS under your own brand | ✅ allowed |
| Shared multi-tenant instance, tenants configuring apps (your brand) | ✅ allowed |
| Offering hosting/platform **under the "AuraBoot" brand** | ❌ requires trademark license (Part 2 S3) |
| Enterprise capabilities (advanced designers/plugins/governance) | Commercial License (separate proprietary codebase, see Q12) |

**Bottom line:** the v2.0 monetization boundary = the AuraBoot trademark + the proprietary enterprise codebase — not field-of-use restrictions on open code.

### Q11. I'm building a vertical-industry SaaS (e.g., restaurant SaaS, education SaaS) on AuraBoot. Is this allowed, even multi-tenant?
**Yes, even multi-tenant is allowed.** Because what you sell is "restaurant / education business applications," not a "general low-code platform." Tenants use your pre-configured business features, not low-code authoring.

If your product also exposes "tenants can use low-code to modify their own business workflows," the boundary becomes ambiguous — please contact us through https://www.auraboot.com/contact to confirm.

### Q12. Does a commercial license unlock multi-tenant low-code SaaS?
Two different things are often conflated here:
- **The community edition** (this repository, Apache-2.0 + trademark supplements): turning it into a multi-tenant SaaS **under your own brand** is permitted by Part 1 outright — no paid license needed. The only restriction is that you may not trade under the "AuraBoot" brand (Part 2 S3, trademark).
- **The commercial/enterprise edition** (a separate proprietary codebase): the advanced designers, industry plugins, and governance components are not in this repository and are available only under a Commercial License and separate SaaS / OEM agreements.

In short: selling a rebranded SaaS built on the community edition is Apache-2.0 freedom; selling AuraBoot commercial capabilities or using the AuraBoot brand is what requires an agreement with us. Contact: https://www.auraboot.com/contact

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
A commercial license unlocks **enterprise capabilities and services** — not permission to use the community edition (Apache-2.0 already grants that):
- ✅ Enterprise features: advanced designers, industry plugins, governance components
- ✅ Official support / SLA / priority bug fixes / custom development
- ✅ Offering products or services under the "AuraBoot" brand (trademark license)
- ✅ Third-party commercial warranties for your legal team

Self-hosting, project delivery, own-brand SaaS (including multi-tenant low-code platforms) — **none of these require a commercial license**.

### Q17. How do I obtain a commercial license?
Use the website contact form at https://www.auraboot.com/contact.

### Q18. Is the commercial license perpetual or subscription-based?
Specific terms are governed by the commercial agreement signed between parties. Generally we offer two options: perpetual buy-out and annual subscription (the latter includes upgrades and support).

---

## 7. Compliance & Enforcement

### Q19. What happens if I violate the license?
Under [Apache-2.0 §3](https://www.apache.org/licenses/LICENSE-2.0) (Part 2 S6), if you institute patent litigation alleging that the Work or an incorporated Contribution infringes a patent, the **patent licenses** granted to you for that Work terminate when the litigation is filed. This does not mean that all copyright licenses automatically terminate. Part 2 adds no general "30-day cure then destroy all copies" provision and does not waive the redistribution obligations in Part 1. Trademark matters are covered by Part 2 S3; specific disputes require legal review.

### Q20. Which jurisdiction governs?
Apache-2.0 does not designate an exclusive governing law, court, arbitration institution, or dispute-resolution forum. If the parties sign a Commercial License or another written agreement, that agreement may specify the governing law and dispute-resolution mechanism.

### Q21. Can commercial customers agree on a different forum?
Yes. For cross-border commercial customers, a commercial contract may specify the governing law and dispute-resolution forum (e.g., Singapore / Hong Kong / the customer's jurisdiction). The commercial contract controls for the covered parties and scope.

---

## 8. Comparison with Other Projects

### Q22. How does AuraBoot's license compare to Appsmith / ToolJet / Budibase?

| Project | License | Multi-tenant SaaS Restriction | Branding Removable? |
|---|---|---|---|
| **AuraBoot v2.0** | Apache-2.0 (trademark supplement) | None (own brand) | ✅ yes (must drop AuraBoot marks) |
| Appsmith | Apache-2.0 | None | Fully removable |
| ToolJet | AGPLv3 | Must open-source all modifications | Fully removable |
| Budibase | GPL v3 + Commercial | Must open-source all modifications (GPL) | Commercial version only |

**Summary:** as of v2.0 the AuraBoot community edition sits in the same domain as pure Apache projects (zero enterprise-legal friction, no forced open-sourcing, no SaaS restrictions) — the differentiation moved to the product layer: enterprise capabilities in a proprietary codebase, plus AuraBoot trademark protection.

---

## 9. Still Have Questions?

- **Legal questions:** https://www.auraboot.com/contact
- **Contribution questions:** see CONTRIBUTING.md
- **Security disclosure:** see SECURITY.md
- **Commercial / OEM partnerships:** https://www.auraboot.com/contact

This FAQ is updated based on community feedback. Open a GitHub Issue if you have a question — frequently asked ones get added here.
