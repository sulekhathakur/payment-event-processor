package com.paymentprocessor.dto;

import com.paymentprocessor.entity.TransactionStatus;

import java.math.BigDecimal;
import java.util.UUID;

public record TransactionResponse(
        UUID transactionId,
        TransactionStatus status,
        BigDecimal balance,
        String message
) {
}