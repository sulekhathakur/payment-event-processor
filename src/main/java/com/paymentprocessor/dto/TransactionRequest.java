package com.paymentprocessor.dto;

import com.paymentprocessor.entity.TransactionType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

public record TransactionRequest(

        @NotNull(message = "transactionId is required")
        UUID transactionId,

        @NotNull(message = "userId is required")
        UUID userId,

        @NotNull(message = "amount is required")
        @DecimalMin(value = "0.01", message = "amount must be greater than 0")
        BigDecimal amount,

        @NotNull(message = "type is required")
        TransactionType type

) {
}