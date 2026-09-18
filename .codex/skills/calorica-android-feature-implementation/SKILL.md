---
name: calorica-android-feature-implementation
description: Implement a non-trivial Calorica Android feature through focused research, planning, delivery, testing, and review. Use for changes spanning multiple files or layers; not for explanation-only work or tiny isolated edits.
---

# Calorica Android Feature Implementation

Deliver a complete Android feature while the main agent owns user communication, scope decisions, final integration, and Git actions. Use the project agents:

- `calorica_android_feature_researcher`
- `calorica_android_feature_planner`
- `calorica_android_feature_implementer`
- `calorica_android_feature_tester`
- `calorica_android_feature_reviewer`

## Establish context

1. Read `AGENTS.md` and `ARCHITECTURE.md`.
2. For UI-facing work, read `docs/design-system.md` completely.
3. Check branch, status, relevant `vibe/` artifacts, and analogous code/tests. Preserve unrelated changes.
4. Use a stable kebab-case slug and retain `AC-###` and `T-###` identifiers throughout.
5. Read [Android quality gates](references/android-quality-gates.md) and include only applicable gates.

Treat Calorica as local-first and independent. Preserve stable IDs, nutrient snapshots on historical food entries, editable history, sensitive-data minimization, calculation explainability, and neutral non-medical UX. Do not introduce a connection to another app or service unless the user explicitly requests it.

## Workflow

### Research and plan

Use one read-only researcher unless an approved product brief plus obvious code evidence makes it unnecessary. Its Feature Brief must cover scope, non-goals, testable ACs, affected paths, invariants, risks, verification, and any product-changing question.

Then use the planner to write `vibe/<slug>-plan.md` and `vibe/<slug>-plan-track.md`. The plan must freeze contracts, AC-to-task-to-verification traceability, exact ownership, execution waves, and relevant migration/privacy/accessibility concerns before implementation.

### Implement

Use one implementer by default. Use parallel writers only with frozen contracts and disjoint file ownership; never split a Room migration, a shared navigation point, or a dependency catalogue between writers. Each implementer owns the smallest meaningful regression tests and a targeted check. Do not run Gradle while parallel writers edit one checkout.

### Verify and close

After the diff is stable, run the tester and reviewer in parallel. The tester owns the only Gradle process; the reviewer remains read-only. Route confirmed P0/P1 findings back to the original implementer in one batch, then recheck only affected behavior.

Once stable, run the final project gates sequentially once (when the Android project exists):

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

Do not commit, merge, push, create worktrees, or change branches unless the user explicitly requested that action. If the user explicitly requests worktree mode, read [worktree mode](references/worktree-mode.md) before any Git mutation.

## Handoff

Lead with delivered ACs and exclusions, then report grouped file changes, exact validation results, review verdict, residual risks, and plan/tracker paths.
