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
- Added a real SQLite persistence module with WAL, foreign keys, busy timeout, schema versioning, and persisted world/dependency identity.
- SQLite open fails closed for a foreign world UUID or an unknown schema version.
- Bundled exact `sqlite-jdbc` `3.50.3.0` in the NeoForge JAR using jar-in-jar metadata.
- Added checked, nonnegative LC-minor-unit `MoneyAmount` values.
- Added SQLite schema v2 durable Reservations with service-scoped `requestId` idempotency, payload-conflict rejection, available-balance calculation, and per-account concurrency control.
- Added SQLite schema v3 payment transactions with durable `PREPARED`, `EXTERNAL_APPLIED`, `CIVIC_COMMITTED`, `COMPENSATING`, and `COMPENSATED` states.
- Added restart recovery for crashes after an external payment and for the ambiguous window between external application and Civic recording the result; retries retain the same transaction UUID.

## In progress

- Add runtime probes for the specific LC, FTB Teams, and FTB Chunks integration operations required by the first implementation slice.
- Add GameTests for the runtime detector and graded failure behavior.

## Not yet completed

- SQLite migrations beyond schema v3, online backups, restore validation, compensation execution, and recovery audit records.
- Stable `NationId`, `NationProvider`, FTB Teams binding, citizenship, roles, capital, lifecycle, and nation registration.
- National Treasury and Organization Fiscal Account LC adapters; budgets, Reservation release/partial settlement, Escrow, transfers, refunds, withdrawals, approvals, ledger, audit, and service authorization policy.
- Cumulative Net Issuance, Issuance Hard Cap, National Issuance Quota, Registered Mint, material custody, and destruction/correction flows.
- FTB Chunks territory prepayment, maintenance, validity, continuity, transfer, restoration, and force-load charging.
- National Strength, Registered Facility, Create production accounting, Global Reference Price, and conservative scoring adapters.
- Commands, menus, public reports, administration, recovery tools, and isolated `DEBUG WORLD` implementation.
- Client runtime, GameTest runtime, playable single-player loop, and multiplayer core loop.
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
- JAR inspection confirmed `META-INF/jarjar/sqlite-jdbc-3.50.3.0.jar` and NeoForge jar-in-jar metadata.

### SQLite integration

- `gradlew.bat test --tests org.civiceconomy.persistence.CivicDatabaseTest` passed against temporary on-disk SQLite files using the real Xerial driver.
- Verified WAL mode, schema v3, persisted identity across close/reopen, foreign-world rejection, and unknown-schema rejection.
- These are real database integration tests, not mocks; backup/restore and crash recovery remain unverified.

### Fiscal domain and database integration

- `MoneyAmountTest` verifies negative rejection, checked overflow, nonnegative subtraction, and exact LC minor-unit arithmetic.
- `FiscalLedgerTest` uses real temporary SQLite databases to verify durable Reservation replay, changed-balance replay stability, insufficient-funds rejection without a row, conflicting-payload rejection, and active/available balances.
- The concurrent oversubscription test passed ten repeated runs: two simultaneous 700-unit holds against 1,000 units produce exactly one success and one active 700-unit Reservation.
- `PaymentRecoveryTest` uses a real temporary SQLite database and controlled external-payment adapter to verify two restart windows: after `EXTERNAL_APPLIED`, and after external application before Civic can record it.
- Recovery after `EXTERNAL_APPLIED` performs no second external call; ambiguous recovery retries the same transaction UUID, producing two adapter attempts but one idempotent economic effect.
- LC balances are currently supplied through a test adapter; no claim of real LC debit, credit, or idempotent payment is made yet.

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

Implement and verify the version-locked LC balance/payment adapter against the real `1.21-2.3.0.5` runtime before extending payment states or starting another functional slice.
