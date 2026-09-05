package com.paymentprocessor.service;

import com.paymentprocessor.dto.TransactionRequest;
import com.paymentprocessor.dto.TransactionResponse;
import com.paymentprocessor.entity.Transaction;
import com.paymentprocessor.entity.TransactionStatus;
import com.paymentprocessor.entity.TransactionType;
import com.paymentprocessor.entity.Wallet;
import com.paymentprocessor.exception.DuplicateTransactionException;
import com.paymentprocessor.exception.WalletNotFoundException;
import com.paymentprocessor.repository.TransactionRepository;
import com.paymentprocessor.repository.WalletRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;

@Service
public class TransactionService {

    private final WalletRepository walletRepository;
    private final TransactionRepository transactionRepository;

    public TransactionService(
            WalletRepository walletRepository,
            TransactionRepository transactionRepository
    ) {
        this.walletRepository = walletRepository;
        this.transactionRepository = transactionRepository;
    }

    @Transactional
    public TransactionResponse processTransaction(TransactionRequest request) {

        // STEP 1: Fast idempotency check
        Optional<Transaction> existingTransaction =
                transactionRepository.findByTransactionId(request.transactionId());

        if (existingTransaction.isPresent()) {
            throw new DuplicateTransactionException(
                    "Transaction " + request.transactionId() + " has already been processed"
            );
        }

        // STEP 2: Acquire database-level wallet lock
        Wallet wallet = walletRepository
                .findByUserIdForUpdate(request.userId())
                .orElseThrow(() ->
                        new WalletNotFoundException(
                                "Wallet not found for user " + request.userId()
                        )
                );

        // STEP 3: Re-check idempotency AFTER acquiring wallet lock
        //
        // This is important for concurrent duplicate requests.
        existingTransaction =
                transactionRepository.findByTransactionId(request.transactionId());

        if (existingTransaction.isPresent()) {
            throw new DuplicateTransactionException(
                    "Transaction " + request.transactionId() + " has already been processed"
            );
        }

        // STEP 4: Handle DEBIT
        if (request.type() == TransactionType.DEBIT) {

            if (wallet.getBalance().compareTo(request.amount()) < 0) {

                Transaction failedTransaction = new Transaction(
                        request.transactionId(),
                        request.userId(),
                        request.amount(),
                        request.type(),
                        TransactionStatus.FAILED
                );

                transactionRepository.save(failedTransaction);

                return new TransactionResponse(
                        request.transactionId(),
                        TransactionStatus.FAILED,
                        wallet.getBalance(),
                        "Insufficient funds"
                );
            }

            wallet.setBalance(
                    wallet.getBalance().subtract(request.amount())
            );
        }

        // STEP 5: Handle CREDIT
        if (request.type() == TransactionType.CREDIT) {

            wallet.setBalance(
                    wallet.getBalance().add(request.amount())
            );
        }

        // STEP 6: Save wallet
        walletRepository.save(wallet);

        // STEP 7: Save successful transaction
        Transaction transaction = new Transaction(
                request.transactionId(),
                request.userId(),
                request.amount(),
                request.type(),
                TransactionStatus.SUCCESS
        );

        transactionRepository.save(transaction);

        // STEP 8: Return result
        return new TransactionResponse(
                request.transactionId(),
                TransactionStatus.SUCCESS,
                wallet.getBalance(),
                "Transaction processed successfully"
        );
    }
}