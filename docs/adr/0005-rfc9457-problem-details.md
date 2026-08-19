# ADR-005: RFC 9457 problem details for all errors

## Status

Accepted

## Context

The API has five distinct failure modes: unknown sort field, non-numeric
filter, out-of-range filter, unknown ticker, and no data available. A browser
client needs to tell them apart and show something useful.

Handling them at each throw site means `try`/`catch` scattered through the
controller, and an error body whose shape depends on which branch produced it.

Options considered:

1. `try`/`catch` in each controller method, returning `ResponseEntity`
2. A custom error record plus a `@RestControllerAdvice`
3. `ProblemDetail` (RFC 9457) plus a `@RestControllerAdvice`

## Decision

Every exception is mapped to a `ProblemDetail` in a single
`GlobalExceptionHandler`, selected by exception type.

## Rationale

**One shape for every error**, standardised rather than invented:

```json
{
  "type":   "about:blank",
  "title":  "Not Found",
  "status": 404,
  "detail": "No stock found for ticker 'ZZZZ'"
}
```

RFC 9457 is the interoperable form of option 2, already built into Spring
Framework 6+, so a bespoke error record would be strictly more work for a
less recognisable result.

**The controller stays clean.** `StockController` contains no `try`/`catch` at
all — each method delegates on its first statement. Exceptions travel
untouched from wherever they are raised to the handler, which is the only
place that knows about HTTP status codes.

**Adding a failure mode is additive.** A new exception class plus a handler
method; the controller does not change.

**Messages are curated, not forwarded blindly.** Two deliberate choices:

- `IllegalArgumentException` from `SortBy.from` is passed through verbatim,
  because it already names the valid values — more useful than a generic
  "bad request".
- `MethodArgumentTypeMismatchException` is *rewritten* to
  `Parameter 'maxPe' has an invalid value`, because Spring's own message leaks
  internal Java type names into a public response.

## Consequences

**Accepted costs**

- Error handling is not local to the code that fails; a reader must open
  `GlobalExceptionHandler` to see what a given exception produces. The
  Javadoc on each exception names its status to reduce that hop.
- Any exception without a matching handler falls through to a generic `500`.
  The mapping is only as complete as this class.

**What it buys**

- Clients parse one structure for every error.
- The contract is directly testable: `StockScreenerIntegrationTest` asserts
  exact `$.detail` strings for the 400/404/503 paths, so a change in error
  wording fails a test rather than silently breaking a client.
- Status-code policy lives in one file and can be reviewed as a whole.
