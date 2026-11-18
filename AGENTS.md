# Agent collaboration rules — Liminal Dimension

## Authority
- GitHub repository Copule/liminal-dimension-mod is the single source of truth for code.
- The approved 72-item V1.0 requirement list is authoritative for gameplay; do not infer missing items from summaries or research proposals.
- Research recommendations are not automatically approved requirements.
- Never disclose or publish private story/art contributions without authorization.

## M0 scope
- Bootstrap a minimal Minecraft Java 26.3 / NeoForge ModDevGradle project targeting Java 25.
- Mod ID: `liminal_dimension`; display name: `Liminal Dimension`.
- No gameplay systems, dimensions, death hooks, assets, bosses, or story implementation in M0.
- Prefer the official NeoForgeMDKs/MDK-26.3-ModDevGradle template; preserve the Gradle wrapper and record any version changes.

## Branch and PR discipline
- Never commit directly to `main` or force-push.
- Each agent works on an isolated task branch; do not share a mutable working tree.
- One task = one branch = one PR; changes to shared build files need an assigned integrator.
- Rebase/merge current `main` only after inspecting conflicts; never discard another agent's changes.
- PRs require a concise summary, tests actually run, known gaps, and impacted files.
- CI success is necessary but not sufficient; final merge requires user approval.

## Validation and reporting
- Never claim Gradle build, Minecraft client launch, or CI success without logs.
- Distinguish static inspection from execution.
- Use GitHub Issues/PRs for task status; put durable architecture decisions in `docs/`.
- No secrets, credentials, local caches, build outputs, or private material in commits.

## Discovery-first implementation policy
Before starting each new engineering task, the assigned execution model must:
1. Inspect local repository code, installed tools, existing dependencies, cached examples and previous project work for reusable solutions.
2. Search relevant official documentation, maintained open-source implementations and plugin/mod ecosystems when the task could benefit from external work.
3. Record candidate solutions and evaluate Minecraft/NeoForge version compatibility, API stability, license and attribution, maintenance, trust/security, performance, integration cost and long-term ownership.
4. Choose reuse, adapt, reference or build in-house, with a short rationale and cited sources/links. The assigned execution model makes the implementation choice within the approved task scope; escalate major architectural, legal, security or requirement changes for human approval.
5. Only then implement and test. Do not claim a search was performed unless it actually was. For trivial edits, keep the discovery record proportionately brief.
6. Do not copy code from a third party without verifying that its license permits the intended use. Do not publish private story/art assets.
