# Multi-agent workflow

## Roles
- Local Codex: integration owner, Java implementation, Gradle builds and Minecraft client/server smoke tests.
- Cloud Codex: isolated feature branches, tests and PRs.
- Local Antigravity: independent module work, diagnosis and verification on its own worktree.
- Cloud Antigravity: isolated research, review or implementation only when GitHub access and execution capability are confirmed.
- Local Work: coordinate local artifacts and acceptance evidence where filesystem access is available.
- Cloud Work: planning, GitHub issue/PR coordination, review and acceptance summaries; do not assume access to local files.
- Human owner: requirements, architectural tradeoffs and merge/release approval.

## Handoff contract
Every task must specify: issue, base commit, branch, allowed file scope, acceptance criteria, commands to run, deliverables, and risks. Every handoff reports: commit SHA, PR URL, files changed, executed tests with results, untested items, and blockers.

## Integration
1. Human approves a bounded task.
2. Coordinator creates/assigns an issue; agent creates its own branch from current main.
3. Agent works only in its own branch and isolated local directory or cloud workspace.
4. Agent opens PR with evidence; reviewer checks requirements, scope and CI.
5. Local Codex integrates and runs real Gradle/Minecraft checks where possible.
6. Human authorizes merge; coordinator updates roadmap.

## Collision prevention
- Only the M0 integration owner changes `build.gradle`, `settings.gradle`, `gradle.properties`, Gradle wrapper, or mod entrypoint during bootstrap.
- Do not edit `main` directly or copy uncommitted files between agents as the primary transfer mechanism.
- Coordinate shared APIs through issues and reviewed interface contracts before parallel implementation.
- Keep private narrative and art assets outside public repository until authorized.

## Discovery-first implementation

Before writing new engineering code:

1. Inspect the relevant repository code, installed tools, dependencies, cached examples and prior project work for reusable solutions.
2. When the task could benefit from external work, check relevant official docs, maintained open-source implementations and the plugin/mod ecosystem.
3. Evaluate viable candidates for Minecraft/NeoForge version fit, API stability, license and attribution, maintenance, trust/security, performance, integration cost and long-term ownership.
4. Record a short rationale with sources for `reuse`, `adapt`, `reference` or `build`. The assigned agent chooses within the approved scope; escalate major architectural, legal, security or requirement changes for owner approval.
5. Implement and test after discovery. Keep trivial edits proportionate, never claim a search you did not perform, and do not copy third-party code without verifying that the license permits the intended use.

## Verification and reporting

Distinguish static inspection, local execution, and CI. Cite logs or runs for claimed Gradle, client/server and CI success. Include the changed files, tests actually run, results, and known gaps in the PR. Keep durable architecture decisions in `docs/` and live status in GitHub Issues/PRs. A passing CI run is required but does not replace owner approval to merge.
