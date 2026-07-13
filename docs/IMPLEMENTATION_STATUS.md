# Civic Economy implementation status

Last updated: 2026-07-14 (Asia/Shanghai)

This file distinguishes unit fixtures, artifact/source inspection, compilation, GameTest, client runtime, and dedicated-server runtime evidence. One form of evidence must not be presented as another.

## Completed

- Integrated the isolated single-player debug-world specification from PR #1 into `agent/v0.1-playable`.
- Created the Java 21 / Minecraft 1.21.1 / NeoForge 21.1.197 Gradle skeleton and wrapper.
- Pinned Lightman's Currency `1.21-2.3.0.5`, FTB Library `2101.1.32`, FTB Teams `2101.1.10`, FTB Chunks `2101.1.20`, and optional Create artifact `1.21.1-6.0.6`.
- Added a pure compatibility module with exact-version and probe-class checks.
- Required LC/FTB absence, version drift, or class drift fails closed.
- Create absence leaves the fiscal core enabled; Create version or class drift disables production scoring without blocking startup.
- Added a NeoForge runtime detector and enforced the compatibility report during Civic mod construction.

## In progress

- Add runtime probes for the specific LC, FTB Teams, and FTB Chunks integration operations required by the first implementation slice.
- Add GameTests for the runtime detector and graded failure behavior.

## Not yet completed

- SQLite WAL persistence, migrations, backups, restore validation, transaction journal, and compensation recovery.
- Stable `NationId`, `NationProvider`, FTB Teams binding, citizenship, roles, capital, lifecycle, and nation registration.
- National Treasury and Organization Fiscal Account LC adapters; budgets, Reservations, Escrow, transfers, refunds, withdrawals, approvals, ledger, audit, and service identities.
- Cumulative Net Issuance, Issuance Hard Cap, National Issuance Quota, Registered Mint, material custody, and destruction/correction flows.
- FTB Chunks territory prepayment, maintenance, validity, continuity, transfer, restoration, and force-load charging.
- National Strength, Registered Facility, Create production accounting, Global Reference Price, and conservative scoring adapters.
- Commands, menus, public reports, administration, recovery tools, and isolated `DEBUG WORLD` implementation.
- Client runtime, GameTest runtime, dedicated-server Create runtime, playable single-player loop, and multiplayer core loop.
- Installation, configuration, player, administrator, debugging, upgrade, backup, and recovery documentation.
- Release JAR verification, clean worktree, push, and draft pull request.

## Verification evidence

### Unit fixtures

- `gradlew.bat test --tests org.civiceconomy.compat.CompatibilityMatrixTest` passed on 2026-07-14.
- Six public-interface behaviors passed: supported required versions, missing required mod, unknown required version, missing required probe class, supported Create, and unknown Create.

### Artifact and metadata inspection

- `gradlew.bat resolveRuntimeDependencies` resolved the exact LC and FTB jars plus Architectury `13.0.8`.
- Direct jar inspection confirmed these classes in the real artifacts:
  - LC: `BankAPI`, `IBankAccount`;
  - FTB Teams: `FTBTeamsAPI`;
  - FTB Chunks: `FTBChunksAPI`, `ClaimedChunkManager`;
  - Create: `ProcessingRecipe`.
- Direct `META-INF/neoforge.mods.toml` inspection confirmed runtime versions `1.21-2.3.0.5`, `2101.1.10`, `2101.1.20`, and Create `6.0.6`.
- These checks prove artifact contents and metadata only; they do not prove gameplay behavior.

### Compilation and build

- `gradlew.bat build` passed on 2026-07-14 and produced a development JAR.
- This is compilation/build evidence only, not installability or gameplay evidence.

### Dedicated-server runtime

- `gradlew.bat runServer` started a real NeoForge dedicated server with LC `1.21-2.3.0.5`, FTB Teams `2101.1.10`, FTB Chunks `2101.1.20`, FTB Library `2101.1.32`, Architectury `13.0.8`, and no Create.
- Civic logged `compatibility check passed; Create production scoring is disabled`.
- Minecraft logged `Done (5.304s)!` before the task-owned process was stopped.
- `gradlew.bat runServer -PincludeCreate=true` loaded the same required stack plus Create `6.0.6`, embedded Flywheel `1.0.4`, and embedded Ponder `1.0.56` in an isolated run directory.
- Civic logged `compatibility check passed; Create production scoring is enabled`.
- Minecraft logged `Done (4.165s)!` before the monitored task-owned process tree was stopped.

### Environment note

- The local Microsoft Java 21 trust store rejected the Gradle/GitHub certificate path. Validation commands used Java's Windows root store via `JAVA_TOOL_OPTIONS=-Djavax.net.ssl.trustStoreType=Windows-ROOT -Djavax.net.ssl.trustStore=NONE`.
- The Gradle 9.2.1 distribution was accepted only after its SHA-256 matched Gradle's published `72f44c9f8ebcb1af43838f45ee5c4aa9c5444898b3468ab3f4af7b6076c5bc3f` checksum.

## Known defects and risks

- Probe class presence does not yet prove method-level API compatibility or event semantics.
- Runtime-generated `run/` data is local evidence and must never be committed.
- Lightman's Currency defaults include issuance and interest mechanisms that must be explicitly disabled or blocked before a playable release can satisfy currency conservation.

## Next step

Deepen the dependency probes to method-level LC account operations, FTB Teams identity queries, FTB Chunks claim/event seams, and Create fixed-machine recipe completion seams.
