# ADR-001: No database; in-memory snapshot only

## Status

Accepted

## Context

The MVP screens a fixed universe of ~18 U.S. large-cap companies: search by
name or ticker, filter by P/E and market cap, sort by a metric. All of it
operates on a *current* snapshot.

A relational database is the reflexive choice for a Spring Boot backend, and
the course context makes PostgreSQL + Testcontainers an obvious box to tick.
Options considered:

1. PostgreSQL with JPA, snapshots persisted on refresh
2. An embedded database (H2) to avoid container setup
3. No database — an in-memory snapshot with a TTL

## Decision

No database. `StockService` holds an `AtomicReference<Snapshot>` refreshed
from the provider every 10 minutes.

## Rationale

The MVP has no requirement a database would serve:

- **No history.** The screener shows current values only. Nothing queries the
  past.
- **No user-specific data.** No accounts, no watchlists, no portfolios.
- **No query a list can't answer.** Filtering and sorting ~18 records in
  memory is trivial; there is no join, no aggregate, no index worth building.
- **The data is not ours.** It is a cache of someone else's API, valid for
  minutes. Persisting it would mean storing a copy that is stale by design.

Adding PostgreSQL would introduce a schema, migrations, a connection pool,
container setup for tests and a repository layer — and every one of those is
machinery in service of nothing the MVP does. Testcontainers would then be
added to test the database that exists only to justify Testcontainers.

H2 was rejected for the same reason plus a worse one: it would make tests pass
against a database that is not the production database.

## Consequences

**Accepted costs**

- The cache is lost on restart. The first request after a restart refetches
  the whole universe, ~54 API calls, and is correspondingly slow.
- Multiple instances do not share a cache. Each would hold its own snapshot
  and consume its own quota — this design assumes a single instance.
- Nothing is auditable. There is no record of what the screener showed
  yesterday.

**What it buys**

- The test suite needs no container, no fixtures, no schema. 52 tests run in
  about five seconds.
- There is no persistence layer to keep in sync with the domain model.

**When to revisit**

The roadmap's next features — watchlists, virtual portfolios, price alerts,
historical charts — all need durable, user-specific or time-series data. The
first of those is the trigger to add a database. Because `StockService` is the
only component that knows where stocks come from, a `Repository` slots in
behind it without the web layer changing; see
[ADR-002](0002-provider-abstraction.md) for the same argument applied to the
provider.
