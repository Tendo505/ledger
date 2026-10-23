# Ledger

**A double-entry payments ledger API in Java 21 and Spring Boot.** It has accounts, transfers that can't overdraw or double-spend, idempotency keys that make retries safe, card-style holds (authorize → capture / void / expire), signed webhooks via a transactional outbox, and an audit that recomputes the books from scratch.

[![CI](https://github.com/Tendo505/ledger/actions/workflows/ci.yml/badge.svg)](https://github.com/Tendo505/ledger/actions/workflows/ci.yml)

```bash
./gradlew bootRun          # or: docker compose up --build   (PostgreSQL)
open http://localhost:8080/docs
```

## A tour

```bash
B=http://localhost:8080/v1
curl -X POST $B/accounts -H 'Content-Type: application/json' -d '{"name":"Bank","currency":"USD","allowNegative":true}'
curl -X POST $B/accounts -H 'Content-Type: application/json' -d '{"name":"Alice","currency":"USD"}'

# Move 100.00 (amounts are integers in minor units). Retrying with the same key is safe.
curl -X POST $B/transfers -H 'Content-Type: application/json' -H 'Idempotency-Key: deposit-001' \
     -d '{"from":"acct_…bank","to":"acct_…alice","amount":10000,"currency":"USD","description":"top-up"}'
```

```json
{
  "id": "txn_Lkcx9YnojA7jLcQ3", "kind": "transfer", "description": "top-up",
  "entries": [
    { "accountId": "acct_…bank",  "amount": -10000, "balanceAfter": -10000 },
    { "accountId": "acct_…alice", "amount":  10000, "balanceAfter":  10000 }
  ]
}
```

Errors are [RFC 7807](https://www.rfc-editor.org/rfc/rfc7807) problems with a stable `code`:

```json
{ "status": 422, "code": "insufficient_funds", "detail": "account acct_… has 10000 available, needs 50000" }
```

| Endpoint | |
| --- | --- |
| `POST /v1/accounts` · `GET /v1/accounts/{id}` | `balance`, `pending` (reserved by holds) and `available` |
| `GET /v1/accounts/{id}/entries?limit=&before=` | the statement, newest first, keyset-paginated |
| `POST /v1/transfers` *(Idempotency-Key)* · `GET /v1/transactions/{id}` | |
| `POST /v1/holds` *(Idempotency-Key)* · `POST /v1/holds/{id}/capture` · `…/void` | reserve funds, then pay all or part, or release |
| `POST /v1/webhooks` · `GET /v1/webhooks` | subscribe a URL to every event |
| `GET /v1/audit` | re-derive the books and report any inconsistency |

## How it stays correct

**Double entry.** Money never appears or disappears: every transaction is a set of entries that sum to zero, and entries are never edited. Funding accounts (`allowNegative`) stand for the outside world, so per currency all balances net to exactly zero.

**No overdrafts, no lost updates, no deadlocks.** A transfer locks both accounts with `SELECT … FOR UPDATE`, always in id order, then checks the available balance and writes. Two transfers between the same accounts in opposite directions queue up instead of deadlocking. Two transfers spending the same balance can't both pass the check. A `CHECK (allow_negative OR balance - pending >= 0)` constraint in the schema is the last line of defence.

**Idempotency keys.** Clients retry when a response is lost; that must not pay twice. The key is claimed (`INSERT … ON CONFLICT DO NOTHING`) *in the same database transaction* as the transfer, together with a hash of the request and the response:

* same key, same request → the stored response, byte for byte, with `Idempotent-Replayed: true`;
* same key, different request → `422 idempotency_key_reused`;
* a refused request (e.g. insufficient funds) rolls back, key included, so it can be retried once it would succeed;
* concurrent duplicates: the second insert waits on the first's row lock, then replays its response. The test fires 20 at once, and exactly one transfer happens.

**Holds** work like card authorizations. Creating one moves the amount into `pending`, so it's no longer `available` but hasn't left the account. A capture pays all or part to the destination and releases the rest. A void releases everything. A sweeper expires holds that are past their deadline, and a capture after the deadline is refused.

**Transactional outbox → webhooks.** Each change writes its event row *in the same transaction*, so there is never an event for a rolled-back transfer, and never a committed transfer without its event. A dispatcher delivers events afterwards, **at least once**: retried with exponential backoff (1 s, 2 s, 4 s … up to an hour, 10 attempts) until the receiver answers 2xx. Each delivery is signed like Stripe's:

```
Ledger-Signature: t=1792771200,v1=hex(HMAC-SHA256(secret, "1792771200." + body))
```

Receivers verify with a constant-time compare and reject stale timestamps, so a captured delivery can't be replayed later ([`Signatures.verify`](src/main/java/io/github/tendo505/ledger/events/Signatures.java)).

**The audit** (`GET /v1/audit`) trusts nothing cached. It checks that every transaction sums to zero and that each account's balance equals the sum of its entries. It checks each entry's `balanceAfter` against the running total (a window function), reserved amounts against the open holds, and that nobody is overdrawn and every currency nets to zero.

## Tests

`./gradlew test` runs 11 end-to-end tests against the real server on a random port, with a clock the tests can move. CI runs them twice: on H2, and on **PostgreSQL 16**, where the row locks and concurrent transactions are the real thing.

| Test | |
| --- | --- |
| Concurrency | 16 threads, ~2,000 transfers, holds, captures and voids between six accounts in both directions: no deadlock, nobody overdrawn, total money unchanged, audit clean over ~3,200 entries |
| Idempotency race | 20 identical requests at the same instant: 20 × `201`, one transaction, 19 replays, paid once |
| Idempotency rules | replay is byte-identical; a different body is refused; refusals aren't remembered |
| Holds | reserve, partial capture, void, expiry by the sweeper, capture-after-expiry refused |
| Webhooks | a real HTTP receiver: signatures verify (and fail for a wrong secret, a changed body or an old timestamp); a delivery answered with 500 is retried after its backoff |
| Audit | tamper with a balance directly in the database, and the audit names the account and the currency |
| Errors | each refusal has its code; validation lists every bad field |

## Stack

Java 21 (records, pattern matching, text blocks), Spring Boot 3.5 (Web MVC, `JdbcClient`, validation, scheduling, `ProblemDetail`), Flyway migrations, PostgreSQL 16 (H2 for zero-setup runs), springdoc OpenAPI at `/docs`, Docker. Plain SQL instead of an ORM, because the locking and the queries are the point.

## Not implemented

* Authentication. Put it behind a gateway, or add API keys. Every caller is trusted here.
* Multi-currency transfers (FX). A transfer must be in its accounts' currency.
* Running several dispatcher instances: it would need `FOR UPDATE SKIP LOCKED` to share deliveries.

MIT License
