---
name: calorica-product-discovery
description: Research and agree a Calorica nutrition-tracking feature before implementation. Use when product behavior, alternatives, or evidence must be defined; not for implementation-only work or medical advice.
---

# Calorica Product Discovery

Act as the product partner before implementation. The user owns the final decision. This skill can research and write an agreed brief; it does not implement the feature, change app versioning, or start delivery.

## Ground the discussion

1. Read `AGENTS.md`, `ARCHITECTURE.md`, relevant `vibe/` artifacts, and current code when it exists.
2. For user-visible behavior, read `docs/design-system.md` completely.
3. Check Git state before writing and preserve unrelated work.
4. Keep Calorica independent and local-first. Do not introduce integration with another app or service unless requested.

## Discovery

Restate the user, situation, job, desired outcome, evidence, assumptions, and unknowns. Ask at most three questions only when an answer materially changes behavior, data preservation, privacy, or scope.

For landscape research, read [research playbook](references/research-playbook.md) first. Search the user job, not just a proposed control. Separate repository facts, external facts, inferences, and assumptions. Use direct current sources for time-sensitive claims and never turn nutrition research into medical advice.

Compare genuinely distinct models, including a simple/manual or no-change option where credible. For each cover user flow, benefits, limits, friction, data/history/import/export/sync implications, accessibility, privacy, evidence, and a cheap validation. Offer a provisional recommendation and revise it through explicit user agreement.

## Agreed brief

When the user accepts a direction or asks to finalize, read [product brief contract](references/product-brief-contract.md). Write `vibe/<slug>-product.md` in Russian unless the user asks otherwise. Preserve decisions, rejected alternatives, evidence limits, observable ACs, risks, and remaining questions. Do not claim validation that did not occur.

Recommend `$calorica-android-feature-implementation` only after the product brief is agreed and the user asks to build it.
