# Version Production Industry by Exact Recipe

## Status

Accepted

## Decision

Civic assigns a Production Industry to an exact supported production recipe identity and compatibility version, with a future effective time and immutable audit history. Each production contribution uses the assignment effective when its Completion evidence occurred. A whole Registered Facility is not assigned one industry, and item identity, time proximity, player text or facility ownership cannot infer an assignment.

## Context and trade-offs

A Registered Facility can contain several supported machines and legitimately produce across several industries. Facility-wide classification would misclassify mixed production, while item- or tag-based classification would turn an unverifiable guess into National Strength evidence. Exact recipe classification requires explicit governance entries and conservatively excludes unclassified recipes, but it keeps attribution stable, explainable and independent of player claims.

## Consequences

- A later assignment version cannot rewrite earlier production evidence.
- The same facility may contribute to multiple industries through different exact recipes.
- Dependency upgrades require explicit assignment review for the new compatibility version.
- Missing assignments produce no score until trusted policy covers the exact recipe.
