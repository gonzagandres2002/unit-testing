# Architecture Decision Records

Each file records one decision that was not obvious at the time, with the
context that forced it and the consequences accepted in exchange. They are
written so that a future reader can tell whether a decision still holds — the
context is the part that expires, not the decision.

| # | Decision | Status |
| --- | --- | --- |
| [0001](0001-no-database.md) | No database; in-memory snapshot only | Accepted |
| [0002](0002-provider-abstraction.md) | Isolate the external API behind a one-method interface | Accepted |
| [0003](0003-graceful-degradation.md) | Degrade gracefully; typed exceptions drive the policy | Accepted |
| [0004](0004-inject-clock.md) | Inject `Clock` instead of calling `Instant.now()` | Accepted |
| [0005](0005-rfc9457-problem-details.md) | RFC 9457 problem details for all errors | Accepted |
| [0006](0006-finnhub-provider.md) | Finnhub as the financial data provider | Accepted |

## Format

```markdown
# ADR-NNN: Title

## Status
Proposed | Accepted | Superseded by ADR-XXX

## Context
What forced a decision. Options considered.

## Decision
What was chosen.

## Rationale
Why, against the alternatives.

## Consequences
What this costs, and what it unlocks. Including the bad parts.
```

Superseding rather than editing keeps the history honest: mark the old record
`Superseded by ADR-XXX` and write a new one.
