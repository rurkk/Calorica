# Explicit worktree mode

Read this reference only when the user explicitly asks for a worktree or parallel root sessions in separate worktrees. Otherwise stay in the current checkout.

- A root session owns one checkout. Its subagents share that checkout, so use exact file ownership rather than worktrees to isolate them.
- Before a requested worktree, inspect status, current branch, HEAD, and `git worktree list --porcelain`; do not ignore uncommitted user work.
- Resolve an exact base, unique branch, target directory, exclusive files, and integration owner before creation.
- Do not copy secrets or machine-local configuration into a worktree.
- Do not merge, rebase, cherry-pick, push, delete a worktree, or delete a branch unless separately authorized.
