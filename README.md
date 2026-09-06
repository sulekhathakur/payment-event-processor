# Payment Event Processor

A concurrent payment transaction processor built using Java 17, Spring Boot, Spring Data JPA, and an H2 in-memory database.

The application processes wallet transactions while ensuring:

- Idempotent transaction processing
- Database-level concurrency control
- Prevention of negative wallet balances
- Duplicate transaction protection
- Transaction status tracking
- Integration testing for concurrent requests

---

## Tech Stack

- Java 17
- Spring Boot 4.1.1
- Spring Web MVC
- Spring Data JPA
- Hibernate
- H2 In-Memory Database
- Maven
- JUnit 5
- MockMvc

---

## Project Structure

```text
src/
├── main/
│   ├── java/com/paymentprocessor/
│   │   ├── controller/
│   │   ├── dto/
│   │   ├── entity/
│   │   ├── exception/
│   │   ├── repository/
│   │   └── service/
│   │
│   └── resources/
│       └── application.properties
│
└── test/
    └── java/com/paymentprocessor/
        └── TransactionProcessorIntegrationTest.java
````

## Prerequisites

Make sure the following are installed:

* Java 17 or higher
* Maven

Verify Java:

```bash
java -version
```

Verify Maven:

```bash
mvn -version
```

---

## How to Run

Clone the repository and navigate to the project directory.

Run the application using Maven:

```bash
mvn spring-boot:run
```

Or using the Maven Wrapper.

### Windows PowerShell

```powershell
.\mvnw.cmd spring-boot:run
```

### Linux/macOS

```bash
./mvnw spring-boot:run
```

---

## Database

The application uses an H2 in-memory database.

No external database installation or configuration is required.

The database is configured as:

```text
jdbc:h2:mem:paymentdb
```

The database exists only while the application is running and is recreated when the application starts.

---

## API

### Process Transaction

**Endpoint:**

```text
POST /api/v1/transactions/process
```

### Request Body

```json
{
  "transactionId": "550e8400-e29b-41d4-a716-446655440000",
  "userId": "123e4567-e89b-12d3-a456-426614174000",
  "amount": 250.00,
  "type": "DEBIT"
}
```

### Supported Transaction Types

```text
DEBIT
CREDIT
```

---

## Successful Transaction

For a successful transaction, the API returns:

**HTTP 200 OK**

Example:

```json
{
  "transactionId": "550e8400-e29b-41d4-a716-446655440000",
  "status": "SUCCESS",
  "balance": 250.00,
  "message": "Transaction processed successfully"
}
```

---

## Insufficient Funds

If a debit request is greater than the available wallet balance:

* The wallet balance is not changed.
* The transaction is stored with `FAILED` status.
* The API returns `409 CONFLICT`.

Example:

```json
{
  "transactionId": "550e8400-e29b-41d4-a716-446655440000",
  "status": "FAILED",
  "balance": 50.00,
  "message": "Insufficient funds"
}
```

---

## Idempotency

Each transaction is identified by a unique `transactionId`.

The database enforces uniqueness on the transaction ID so that the same transaction cannot be stored more than once.

The application also checks for an existing transaction before processing and performs a second check after acquiring the wallet lock.

This prevents duplicate requests from processing the same transaction more than once.

For example, if three identical requests with the same `transactionId` arrive concurrently:

```text
3 requests
     |
     v
1 transaction processed successfully
2 duplicate requests rejected with 409 CONFLICT
     |
     v
Wallet debited only once
```

---

## Concurrency Control

Concurrent transactions targeting the same wallet are protected using a database-level pessimistic write lock.

The wallet repository uses:

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
```

This ensures that only one transaction at a time can read and update the locked wallet row.

For example, if a wallet has ₹500 and 10 concurrent requests attempt to debit ₹100:

```text
Initial Balance = ₹500

Request 1 → SUCCESS → ₹400
Request 2 → SUCCESS → ₹300
Request 3 → SUCCESS → ₹200
Request 4 → SUCCESS → ₹100
Request 5 → SUCCESS → ₹0
Request 6 → FAILED  → Insufficient funds
Request 7 → FAILED  → Insufficient funds
Request 8 → FAILED  → Insufficient funds
Request 9 → FAILED  → Insufficient funds
Request 10 → FAILED → Insufficient funds
```

Final balance:

```text
₹0
```

The wallet cannot become negative because each transaction checks the latest balance while holding the database lock.

---

## Transaction Processing Flow

```text
Client
  |
  v
TransactionController
  |
  v
TransactionService
  |
  +--> Check transactionId
  |
  +--> Acquire wallet database lock
  |
  +--> Re-check transactionId
  |
  +--> Check available balance
  |
  +--> Update wallet balance
  |
  +--> Save transaction
  |
  v
Response
```

---

## Testing

The project contains integration tests covering the required concurrency and idempotency scenarios.

Run all tests using:

```bash
mvn clean test
```

### Test 1 — Single Valid Debit

```text
Processes a single valid debit transaction successfully.
```

Verifies that a ₹250 debit from a ₹500 wallet results in a final balance of ₹250.

Expected result:

```text
HTTP 200 OK
Final balance = ₹250
Transaction status = SUCCESS
```

### Test 2 — Concurrent Duplicate Transaction

```text
Sends 3 identical transactionIDs simultaneously.
Ensures the balance is only deducted once.
```

Expected result:

```text
3 requests
1 successful request
2 conflict responses
Final balance = ₹250
1 transaction stored
```

### Test 3 — Concurrent Debit Requests

```text
Sends 10 concurrent debit requests of ₹100 for a wallet with a ₹500 balance.
Ensures the final balance is exactly ₹0 and 5 requests fail with insufficient funds.
```

Expected result:

```text
10 requests
5 successful requests
5 failed requests
Final balance = ₹0
5 SUCCESS transactions
5 FAILED transactions
```

---

## Architecture

The application follows a layered architecture:

```text
Controller
    ↓
Service
    ↓
Repository
    ↓
Database
```

### Controller

Handles HTTP requests and responses.

### Service

Contains the transaction processing business logic, including:

* Idempotency checks
* Wallet locking
* Balance validation
* Balance updates
* Transaction creation

### Repository

Handles database operations using Spring Data JPA.

### Database

H2 in-memory database stores wallets and transactions.

---

## Key Design Decisions

### Database-Level Locking

Pessimistic write locking is used to prevent concurrent transactions from modifying the same wallet incorrectly.

### Idempotency

A unique `transactionId` combined with application-level checks prevents duplicate transactions from being processed.

### Transactional Processing

Transaction processing runs inside a database transaction so that wallet updates and transaction records are handled atomically.

For more details about the design decisions and concurrency approach, see [DECISIONS.md](DECISIONS.md).

---

## Running the Tests

The complete test suite can be executed with:

```bash
mvn clean test
```

A successful build should end with:

```text
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

---

## Assignment

This project was developed as part of a Java Backend Intern assignment focused on:

* REST API development
* Spring Boot
* JPA/Hibernate
* Database transactions
* Idempotency
* Concurrency control
* Integration testing

