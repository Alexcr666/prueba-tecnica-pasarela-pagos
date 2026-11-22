package com.pagos.payments.domain;

/** Estados de la orden. Ver sección 5.2 del diseño. */
public enum OrderStatus {
    AWAITING_PAYMENT,
    PAID,
    EXPIRED,
    CANCELLED,
    REQUIRES_REVIEW
}
