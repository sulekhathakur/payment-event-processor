package com.paymentprocessor;

import tools.jackson.databind.json.JsonMapper;
import com.paymentprocessor.dto.TransactionRequest;
import com.paymentprocessor.entity.Transaction;
import com.paymentprocessor.entity.TransactionStatus;
import com.paymentprocessor.entity.TransactionType;
import com.paymentprocessor.entity.Wallet;      
import com.paymentprocessor.repository.TransactionRepository;
import com.paymentprocessor.repository.WalletRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest
@AutoConfigureMockMvc
class TransactionProcessorIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JsonMapper objectMapper;

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    private UUID userId;

    @BeforeEach
    void setUp() {

        // Delete transactions first, then wallets.
        transactionRepository.deleteAll();
        walletRepository.deleteAll();

        userId = UUID.randomUUID();

        Wallet wallet = new Wallet(
                userId,
                new BigDecimal("500.00")
        );

        walletRepository.saveAndFlush(wallet);
    }

    @Test
    @DisplayName("Processes a single valid debit transaction successfully.")
    void processesSingleValidDebitSuccessfully() throws Exception {

        System.out.println();
        System.out.println("==============================================");
        System.out.println("TEST: Processes a single valid debit transaction successfully.");
        System.out.println("==============================================");

        UUID transactionId = UUID.randomUUID();

        TransactionRequest request = new TransactionRequest(
                transactionId,
                userId,
                new BigDecimal("250.00"),
                TransactionType.DEBIT
        );

        var result = mockMvc.perform(
                post("/api/v1/transactions/process")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
        ).andReturn();

        assertEquals(200, result.getResponse().getStatus());

        Wallet wallet = walletRepository
                .findByUserId(userId)
                .orElseThrow();

        assertEquals(
                new BigDecimal("250.00"),
                wallet.getBalance()
        );

        assertEquals(
                1,
                transactionRepository.count()
        );

        System.out.println("Initial balance : ₹500.00");
        System.out.println("Debit           : ₹250.00");
        System.out.println("Final balance   : ₹250.00");
        System.out.println("Transactions    : 1");
        System.out.println("RESULT          : PASSED");
        System.out.println("==============================================");
    }

    @Test
    @DisplayName("Sends 3 identical transactionIDs simultaneously. Ensures the balance is only deducted once.")
    void handlesThreeIdenticalTransactions() throws Exception {

        System.out.println();
        System.out.println("==============================================================");
        System.out.println("TEST: Sends 3 identical transactionIDs simultaneously.");
        System.out.println("Ensures the balance is only deducted once.");
        System.out.println("==============================================================");

        UUID transactionId = UUID.randomUUID();

        TransactionRequest request = new TransactionRequest(
                transactionId,
                userId,
                new BigDecimal("250.00"),
                TransactionType.DEBIT
        );

        ExecutorService executor = Executors.newFixedThreadPool(3);

        CountDownLatch ready = new CountDownLatch(3);
        CountDownLatch start = new CountDownLatch(1);

        List<Future<Integer>> futures = new ArrayList<>();

        for (int i = 0; i < 3; i++) {

            futures.add(executor.submit(() -> {

                ready.countDown();

                start.await();

                return mockMvc.perform(
                        post("/api/v1/transactions/process")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request))
                ).andReturn().getResponse().getStatus();
            }));
        }

        ready.await();
        start.countDown();

        List<Integer> statuses = new ArrayList<>();

        for (Future<Integer> future : futures) {
            statuses.add(future.get());
        }

        executor.shutdown();

        long successfulRequests = statuses.stream()
                .filter(status -> status == 200)
                .count();

        long conflictRequests = statuses.stream()
                .filter(status -> status == 409)
                .count();

        Wallet wallet = walletRepository
                .findByUserId(userId)
                .orElseThrow();

        assertEquals(1, successfulRequests);
        assertEquals(2, conflictRequests);

        assertEquals(
                new BigDecimal("250.00"),
                wallet.getBalance()
        );

        assertEquals(
                1,
                transactionRepository.count()
        );

        System.out.println("Requests sent    : 3");
        System.out.println("Successful       : " + successfulRequests);
        System.out.println("Conflict         : " + conflictRequests);
        System.out.println("Final balance    : ₹" + wallet.getBalance());
        System.out.println("Stored transactions: " + transactionRepository.count());
        System.out.println("RESULT           : PASSED");
        System.out.println("==============================================================");
    }

    @Test
    @DisplayName("Sends 10 concurrent debit requests of ₹100 for a wallet with a ₹500 balance. Ensures the final balance is exactly ₹0 and 5 requests fail with insufficient funds.")
    void handlesTenConcurrentDebits() throws Exception {

        System.out.println();
        System.out.println("======================================================================");
        System.out.println("TEST: Sends 10 concurrent debit requests of ₹100");
        System.out.println("Wallet balance = ₹500");
        System.out.println("Expected = 5 successful + 5 insufficient funds");
        System.out.println("======================================================================");

        ExecutorService executor = Executors.newFixedThreadPool(10);

        CountDownLatch ready = new CountDownLatch(10);
        CountDownLatch start = new CountDownLatch(1);

        List<Future<Integer>> futures = new ArrayList<>();

        for (int i = 0; i < 10; i++) {

            UUID transactionId = UUID.randomUUID();

            TransactionRequest request = new TransactionRequest(
                    transactionId,
                    userId,
                    new BigDecimal("100.00"),
                    TransactionType.DEBIT
            );

            futures.add(executor.submit(() -> {

                ready.countDown();

                start.await();

                return mockMvc.perform(
                        post("/api/v1/transactions/process")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request))
                ).andReturn().getResponse().getStatus();
            }));
        }

        ready.await();
        start.countDown();

        List<Integer> statuses = new ArrayList<>();

        for (Future<Integer> future : futures) {
            statuses.add(future.get());
        }

        executor.shutdown();

        long successfulRequests = statuses.stream()
                .filter(status -> status == 200)
                .count();

        long insufficientFundsRequests = statuses.stream()
                .filter(status -> status == 409)
                .count();

        Wallet wallet = walletRepository
                .findByUserId(userId)
                .orElseThrow();

        assertEquals(5, successfulRequests);
        assertEquals(5, insufficientFundsRequests);

        assertEquals(
                new BigDecimal("0.00"),
                wallet.getBalance()
        );

        assertEquals(
                10,
                transactionRepository.count()
        );

        long successfulTransactions =
                transactionRepository.findAll()
                        .stream()
                        .filter(t -> t.getStatus() == TransactionStatus.SUCCESS)
                        .count();

        long failedTransactions =
                transactionRepository.findAll()
                        .stream()
                        .filter(t -> t.getStatus() == TransactionStatus.FAILED)
                        .count();

        assertEquals(5, successfulTransactions);
        assertEquals(5, failedTransactions);

        System.out.println("Requests sent       : 10");
        System.out.println("Successful          : " + successfulRequests);
        System.out.println("Insufficient funds   : " + insufficientFundsRequests);
        System.out.println("Successful DB rows  : " + successfulTransactions);
        System.out.println("Failed DB rows      : " + failedTransactions);
        System.out.println("Final balance       : ₹" + wallet.getBalance());
        System.out.println("RESULT              : PASSED");
        System.out.println("======================================================================");
    }
}