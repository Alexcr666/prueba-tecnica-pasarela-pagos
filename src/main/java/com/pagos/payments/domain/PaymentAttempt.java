package com.pagos.payments.domain;

import java.time.Instant;

/** Fotografía de un intento de pago leída de la base de datos. */
public record PaymentAttempt(
        String id,
        String orderId,
        int attemptNo,
        String provider,
        String providerPaymentId,
        long amountMinor,
        String currency,
        PaymentStatus status,
        String statusReason,
        String redirectUrl,
        int checkCount,
        Instant createdAt) {
}
