# Design Decisions

## 1. How did you handle the concurrency race condition?

The main concurrency problem occurs when multiple requests attempt to debit the same wallet at the same time.

For example, if a wallet has a balance of ₹100 and two requests simultaneously attempt to debit ₹100, both requests could read the balance as ₹100 before either request updates it. Both requests may then believe that sufficient funds are available, which could result in an incorrect balance.

To prevent this race condition, I used a database-level pessimistic write lock on the wallet row.

The `WalletRepository` uses:

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT w FROM Wallet w WHERE w.userId = :userId")
Optional<Wallet> findByUserIdForUpdate(UUID userId);
````

The transaction processing method is also executed inside a database transaction using:

```java
@Transactional
```

This ensures that concurrent transactions targeting the same wallet cannot read and modify the wallet balance simultaneously.

The processing flow is:

```text
Request 1 → Acquire wallet lock → Read balance → Validate → Update → Commit
Request 2 → Wait for wallet lock → Read updated balance → Validate → Update/Fail
```

Therefore, each transaction makes its balance decision using the latest committed wallet balance.

This prevents the wallet balance from becoming negative due to concurrent debit requests.

The approach is verified by the integration test that sends 10 concurrent debit requests of ₹100 against a wallet containing ₹500.

Expected result:

* 5 transactions succeed.
* 5 transactions fail due to insufficient funds.
* Final wallet balance is exactly ₹0.
* The wallet never becomes negative.

---

## 2. How did you handle idempotency?

Each transaction is identified by a unique `transactionId`.

The transaction database table has a unique constraint on `transaction_id`:

```java
@Table(
    name = "transactions",
    uniqueConstraints = {
        @UniqueConstraint(
            name = "uk_transaction_id",
            columnNames = "transaction_id"
        )
    }
)
```

This ensures that the same transaction cannot be stored more than once in the database.

The service also checks whether the transaction already exists before processing it:

```java
transactionRepository.findByTransactionId(request.transactionId());
```

For concurrent duplicate requests, the service performs the check again after acquiring the wallet lock.

This is important because an initial existence check alone is not sufficient for concurrent requests.

For example:

```text
Request A → transactionId does not exist
Request B → transactionId does not exist

Request A → acquires wallet lock → processes transaction
Request B → waits for wallet lock
Request A → commits transaction
Request B → acquires wallet lock → checks transactionId again
Request B → finds existing transaction → rejects duplicate
```

Therefore, the same transaction is processed only once.

The database unique constraint provides an additional safety layer so that duplicate transaction records cannot be inserted even if concurrent requests reach the database at the same time.

This behavior is verified by the integration test that sends three simultaneous requests with the same `transactionId`.

Expected result:

* 1 request succeeds.
* 2 requests return `409 CONFLICT`.
* The wallet is debited only once.
* Only one transaction record is stored.

---

## 3. Why did you use database-level locking instead of `synchronized`?

An application-level lock such as Java's:

```java
synchronized
```

would only coordinate threads running inside the same application instance.

In a real production environment, the application may run multiple instances:

```text
Application Instance 1
Application Instance 2
Application Instance 3
```

A `synchronized` block in Instance 1 would not prevent Instance 2 from modifying the same wallet.

A database-level pessimistic lock is therefore more appropriate because the database controls access to the shared wallet row regardless of which application instance is processing the request.

This makes the concurrency control work across multiple application instances as well.

---

## 4. Why is the database unique constraint necessary for idempotency?

An application-level check such as:

```java
findByTransactionId(...)
```

is useful, but it cannot guarantee uniqueness by itself under concurrent requests.

For example:

```text
Request A → checks transactionId → not found
Request B → checks transactionId → not found

Request A → inserts transaction
Request B → inserts transaction
```

Both requests could pass the application-level check.

The database unique constraint provides the final guarantee that the same `transactionId` cannot be inserted more than once.

Therefore, idempotency is enforced at both levels:

1. Application-level duplicate detection.
2. Database-level unique constraint.

---

## 5. Why are failed transactions stored?

When a debit request does not have sufficient funds, the wallet balance should not be modified.

However, the attempted transaction is still stored with:

```text
status = FAILED
```

This provides an audit record of the transaction attempt and makes it possible to distinguish successful transactions from failed ones.

For the concurrent debit test:

```text
Initial balance = ₹500

5 successful debits × ₹100 = ₹500

5 remaining requests = FAILED
```

The final wallet balance is:

```text
₹0
```

The database contains:

```text
5 SUCCESS transactions
5 FAILED transactions
```

---

## 6. AI Assistant Usage

AI assistance was used during development to understand Spring Boot, JPA, database locking, idempotency, and integration testing, as well as to review implementation decisions.

AI-generated suggestions were verified by compiling the project and running the complete integration test suite rather than being accepted without validation.

One example was the Spring Boot test configuration. An initial suggestion used the older `AutoConfigureMockMvc` package:

```java
org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
```

With Spring Boot 4, the correct package is:

```java
org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
```

Another issue occurred when the pessimistic-lock repository method was used directly during test verification. A pessimistic locking query requires an active database transaction.

The test verification therefore uses a normal wallet lookup:

```java
Optional<Wallet> findByUserId(UUID userId);
```

while the production transaction-processing logic continues to use:

```java
findByUserIdForUpdate(...)
```

for database-level concurrency control.

This highlighted the importance of validating AI-generated suggestions against the actual Spring Boot version and the application's integration tests.

The final implementation was verified by running:

```bash
mvn clean test
```

with all three required integration tests passing successfully.

