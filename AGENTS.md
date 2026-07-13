# Civic Economy repository instructions

These instructions apply to the entire repository.

## Read before changing code

Read these files completely, in order:

1. `README.md`
2. `Civic Economy 经济核心 Mod 项目规格 v1.1.md`
3. `CONTEXT.md`
4. every file under `docs/adr/`

Do not implement from the README summary alone. The v1.1 specification is the product and acceptance source of truth.

## Current scope

The repository is at the specification baseline. The first implementation slice is limited to:

- a Minecraft 1.21.1 NeoForge/Java 21 project skeleton;
- exact dependency probes for Lightman’s Currency, FTB Teams, FTB Chunks, and optional Create;
- runtime compatibility whitelist and fail-closed behavior;
- an integrated-server debug-world harness that is disabled by default and visibly isolated from production data;
- minimal automated tests or GameTests for the probes;
- written evidence of commands, versions, and observed integration boundaries.

Do not implement treasury, escrow, minting, territory charging, national-strength calculation, or UI in the first slice.

## Non-negotiable invariants

- Lightman’s Currency is the only payable currency. Never add a second virtual balance.
- The model is one shared currency, independent national treasuries, and one global issuance controller.
- Civic owns a stable `NationId`; an FTB Team UUID is only a strict one-to-one external binding.
- Formal fiscal flows use banks. Physical cash is free to circulate but is not auditable economic activity.
- External mods cannot call a normal API that directly increases balances.
- Cross-mod writes use durable state machines, idempotency, and compensation; do not describe them as a shared atomic transaction.
- Unknown LC or FTB compatibility fails closed. Unknown Create compatibility disables production scoring without disabling the fiscal core.
- Create accounting is conservative: missing valid production is safer than crediting unverifiable production.
- The release JAR may expose single-player debug tools only through a server-authoritative, world-scoped debug marker. Debug writes must be unavailable in normal worlds and dedicated servers by default.
- MVP excludes automatic tax rates, loans, interest, bonds, and exchange rates.

## Domain documentation

- Use the canonical vocabulary in `CONTEXT.md` in code, UI, tests, and docs.
- Update `CONTEXT.md` when a domain term is resolved or intentionally changed; keep implementation details out of it.
- Add an ADR only for a hard-to-reverse, surprising decision that resulted from a real trade-off.
- If code and the specification disagree, stop and surface the conflict instead of silently changing semantics.

## Development and verification

- Prefer test-first work for each independently verifiable integration boundary.
- Record whether a check used real dependency jars/runtime, fixtures, mocks, or source inspection.
- Never report real integration success based only on mocks or compilation.
- Add exact build, test, and run commands to the README once the Gradle skeleton exists.
- Keep server-only authority and client/server separation explicit.
- Avoid blocking the Minecraft server thread with SQLite, network, or full-world scans.
- Treat dependency version changes as compatibility work requiring probe reruns.
- Test debug commands in an integrated single-player server and verify that the same write commands are rejected in a normal world and an unapproved dedicated server.

## Git scope

- Preserve unrelated user changes.
- Stage only files belonging to the active task.
- Use `agent/<task>` branches after the initial repository baseline.
- Do not force-push unless the user explicitly requests it.

## Suggested skills

- `codebase-design` for module boundaries and deep interfaces;
- `domain-modeling` when terminology or domain ownership changes;
- `tdd` for integration probes and implementation slices;
- `diagnosing-bugs` for runtime or compatibility failures;
- `github:yeet` for intentional commit, push, and draft PR delivery.
