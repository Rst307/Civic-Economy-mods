# Explicit Cross-Facility Export Handoff

## Status

Accepted

## Decision

Civic records a cross-facility production handoff only when a trusted server-side adapter names an existing `EXPORT` event, the exact destination `Facility Accounting Interface`, and the exact destination `Facility Accounting Receipt`. The persistence layer verifies that both interfaces belong to different Registered Facilities, that the Receipt belongs to the destination interface, that its exact item/component quantity covers the export, that it does not predate the export, and that the export is owned by the same service identity. Each export can have at most one destination handoff, and request replay is immutable.

The system does not discover or match relationships by item ID, component identity, timestamps, destination text, or proximity. Item/component/quantity and causal ordering are consistency checks only after the adapter has supplied the exact source and destination identities. `SALE` and `PUBLIC_WORKS` are not transport handoffs. A missing or conflicting fact fails closed without creating a handoff row.

## Context and trade-offs

Production Value Added must cover a confirmable chain without treating intermediate machine counts or repeated inventory movements as new evidence. A destination Receipt can be real without proving that it came from a particular source export. Automatically matching similar stacks or nearby events would make a plausible story look like an audit fact and would permit cross-facility double counting. Requiring an explicit relationship loses evidence when an adapter is not available, but keeps attribution explainable and reversible.

## Consequences

- Cross-facility transport has a durable, immutable, auditable relationship that can be consumed by later chain aggregation.
- The same source export cannot be assigned to two destinations, and changed request replay cannot alter the recorded relationship.
- This slice does not yet publish the handoff into National Strength or implement marginal returns; production/infrastructure remains `PAUSED_ANOMALY` until those calculations are complete.
