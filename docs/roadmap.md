# 《未完全死亡》 roadmap

## M0 — engineering bootstrap (implementation complete, merge pending)
- [x] Existing GitHub repository and main baseline verified.
- [x] Isolated `feat/m0-neoforge-bootstrap` branch created.
- [x] Official Minecraft 26.3 ModDevGradle MDK located; Java 25 target confirmed in template.
- [x] Import the official MDK.
- [x] Configure the internal Mod ID `liminal_dimension`.
- [x] Add the minimal entry point and architecture documentation.
- [x] Add the GitHub Actions Gradle build workflow.
- [x] Run the Gradle build and inspect the output JAR.
- [x] Run the Minecraft development client and verify that the mod loads.
- [x] Verify the packaged JAR in a standalone client and a dedicated server. See the [verification record](architecture.md#m0-packaged-jar-acceptance-2026-11-18).
- [x] Open [PR #1](https://github.com/Copule/liminal-dimension-mod/pull/1).
- [x] M0-06 — Set the in-game display name to `未完全死亡` and document the current development boundary in [AGENTS.md](../AGENTS.md).
- [ ] Playtest a single-player world with the packaged JAR.
- [ ] Review and merge PR #1 with owner approval.

## After M0 (requires owner approval)
- Architecture and persistence prototypes.
- Dimension loading and spatial graph proof of concept.
- Death transaction prototype with crash recovery.
- Gameplay milestones based strictly on the approved complete 72-item requirements.

## Outstanding source
The complete, original numbered 72-item V1.0 requirement list has not yet been recovered into this repository. Preserve its original wording and numbering when recovered. Do not reconstruct it from summaries.
