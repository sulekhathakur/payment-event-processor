package com.paymentprocessor.controller;

import com.paymentprocessor.dto.TransactionRequest;
import com.paymentprocessor.dto.TransactionResponse;
import com.paymentprocessor.entity.TransactionStatus;
import com.paymentprocessor.service.TransactionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/transactions")
public class TransactionController {

    private final TransactionService transactionService;

    public TransactionController(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    @PostMapping("/process")
    public ResponseEntity<TransactionResponse> processTransaction(
            @Valid @RequestBody TransactionRequest request
    ) {

        TransactionResponse response =
                transactionService.processTransaction(request);

        if (response.status() == TransactionStatus.FAILED) {
            return ResponseEntity
                    .status(HttpStatus.CONFLICT)
                    .body(response);
        }

        return ResponseEntity.ok(response);
    }
}