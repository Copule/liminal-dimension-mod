# 《未完全死亡》 agent guide

## Read first

- This GitHub repository (`Copule/liminal-dimension-mod`) is the source of truth for code. The approved, original 72-item V1.0 list governs gameplay; preserve its wording and numbering. Never reconstruct missing entries or treat research as approved requirements.
- Foundation only: underlying technology and necessary tests. Do not define or implement worldbuilding, story, civilizations, dimension art direction, models, bosses, or formal gameplay. If a task needs one of those decisions, stop that part and name the decision the owner needs to make.
- Keep the in-game name `未完全死亡` and Mod ID `liminal_dimension`; the owner decides any official English title.
- One task, one isolated branch/worktree, one PR. No direct commits to `main`, no force-push, no merge without owner approval. Assign an integrator for shared build changes; inspect conflicts and preserve others' work.
- Keep credentials, caches, build output, and private material out of commits. Never disclose private story/art without authorization.
- For engineering, check existing solutions before implementation. Report actual tests, evidence, gaps, and impacted files in the PR. A static review is not a build, client run, or CI pass; CI alone does not authorize merge.

## Read only for your task

Start with the smallest matching section below. Follow extra links only if the task depends on them; do not load all of `docs/` or open PRs indiscriminately.

| If you are working on… | Read |
| --- | --- |
| Engineering discovery, parallel work, or a handoff | [Workflow and discovery](docs/ai-workflow.md) |
| Build, tests, persistence, sides, dimensions, or saves | [Technical topic map](docs/index.md#technical-topics) |
| Milestone status or what is approved to start | [Plans and status](docs/index.md#plans-and-status) |
| A task that depends on gameplay requirements | [Requirements and authority](docs/index.md#requirements-and-authority) |
