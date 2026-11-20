# 《未完全死亡》 roadmap

## M0 — engineering bootstrap (complete and merged)
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
- [x] M0-07 — Verify the packaged JAR in a standalone client's single-player Demo world. See the [acceptance record](architecture.md#m0-07-single-player-world-acceptance-2026-11-18).
- [x] Merge PR #1 into `main` as [`6c66753`](https://github.com/Copule/liminal-dimension-mod/commit/6c66753169d28401090f73b717bef7770c9b00a9). The [post-merge Build](https://github.com/Copule/liminal-dimension-mod/actions/runs/44323609196) passed.

See [remaining verification](architecture.md#m0-remaining-verification)
for the scenarios outside M0's completed acceptance.

## M1 — architecture and infrastructure

- [x] M1-01 — JUnit, native GameTest, report verification and Ubuntu CI. Merged in [PR #3](https://github.com/Copule/liminal-dimension-mod/pull/3). See [test infrastructure](m1-01-testing.md).
- [x] M1-02 — Versioned snapshot persistence and safe publication. Merged in [PR #4](https://github.com/Copule/liminal-dimension-mod/pull/4). See [persistence](m1-02-persistence.md) and the [post-merge Build](https://github.com/Copule/liminal-dimension-mod/actions/runs/44537997459).
- [x] M1-03 — Physical client and dedicated-server isolation, packaged JAR checks and runtime acceptance. Merged in [PR #5](https://github.com/Copule/liminal-dimension-mod/pull/5). See [side isolation](m1-03-side-isolation.md) and the [post-merge Build](https://github.com/Copule/liminal-dimension-mod/actions/runs/44661441426).
- [x] M1-04 — Isolated data pack dimension loading proof of concept. Merged in [PR #6](https://github.com/Copule/liminal-dimension-mod/pull/6) as `954a244`. [main Build #28](https://github.com/Copule/liminal-dimension-mod/actions/runs/44676419025) passed. See [dimension proof of concept](m1-04-dimension-poc.md).
- [x] M1-05 — Dimension data pack removal and save recovery verification. Merged in [PR #7](https://github.com/Copule/liminal-dimension-mod/pull/7) as `c70e0d3`. [main Build #35](https://github.com/Copule/liminal-dimension-mod/actions/runs/44705613903) passed. See [dimension removal and recovery](m1-05-dimension-removal.md).
- [ ] M1-06 — Production client dimension travel, authenticated reconnect, and missing-dimension save behavior verified with one test account. [PR #8](https://github.com/Copule/liminal-dimension-mod/pull/8) awaits Gemini 4 Neon High independent review and owner-approved merge. See [client dimension travel and acceptance scope](m1-06-client-dimension-travel.md).

## Later work (requires owner approval)

- Spatial graph proof of concept.
- Death transaction prototype with crash recovery.
- Gameplay milestones based strictly on the approved complete 72-item requirements.

## V1.0 requirement source

The complete, original numbered 72-item V1.0 requirement list has been
recovered and is held in restricted project materials outside this
repository. Use the approved source with owner-authorized access whenever
work depends on those requirements. Preserve its original wording and
numbering.
