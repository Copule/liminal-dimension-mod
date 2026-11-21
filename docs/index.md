# Project documentation map

Open only the row and section needed for the task. These links point to the
existing source documents rather than duplicating them.

## Technical topics

| Task | Start here |
| --- | --- |
| Build, versions, module layout, or packaging | [Build baseline and layout](architecture.md#build-baseline); [local verification commands](architecture.md#local-verification) |
| JUnit, GameTest, CI reports, or Windows path issues | [M1-01 test commands and acceptance](m1-01-testing.md#运行与判定) |
| Saved state, schema versions, migration, or atomic publication | [M1-02 format and migration](m1-02-persistence.md#格式与迁移); [storage scopes](m1-02-persistence.md#两种作用域与提交) |
| Physical client versus dedicated server, or class loading | [M1-03 boundaries and coverage](m1-03-side-isolation.md#边界与覆盖范围) |
| Data-pack dimension registration or isolation | [M1-04 loading and isolation](m1-04-dimension-poc.md#加载与隔离) |
| Missing dimension, pack removal, or save recovery | [M1-05 reproduction and backups](m1-05-dimension-removal.md#复验与备份); [M1-06 missing-dimension results](m1-06-client-dimension-travel.md#d-移包及恢复实测) |
| Client dimension travel, authenticated join/reconnect, or restart recovery | [M1-06 checks](m1-06-client-dimension-travel.md#复验命令); [acceptance evidence](m1-06-client-dimension-travel.md#本轮验收) |

## Plans and status

- Start with the matching milestone in the [roadmap](roadmap.md), not its full history. Use the relevant GitHub issue/PR for live task, review and merge status; roadmap notes may lag behind.
- Read only the draft PR relevant to your assignment. A draft can contain experimental code or evidence but does not change the approved baseline. Check the exact revision before relying on its results.
- For parallel work or a handoff, read the [workflow](ai-workflow.md#handoff-contract). Put durable technical decisions in `docs/`; keep live task status in GitHub Issues/PRs.

## Requirements and authority

- [Current foundation boundary](../AGENTS.md#read-first) applies to every task. Do not substitute an experiment or a research suggestion for an approved decision.
- [Approved V1.0 source and access rule](roadmap.md#v10-requirement-source): consult only the needed numbered items if the owner has authorized access. The original is kept outside the public repository. If a needed item is unavailable, report the dependency rather than guessing or copying private text into the repo.

## References

Use the sources linked from the relevant technical note before broadening the
search. For engineering work that might benefit from reuse, follow
[the discovery checklist](ai-workflow.md#discovery-first-implementation).
