package com.pagos.payments.domain;

/** Quién provocó una transición. Queda en el historial para reconstruir el ciclo de vida. */
public enum TransitionSource {
    API,
    WEBHOOK,
    RECONCILIATION,
    SETTLEMENT,
    MANUAL
}
