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
