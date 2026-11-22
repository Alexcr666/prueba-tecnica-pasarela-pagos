package com.pagos.payments.application;

import java.util.Locale;
import java.util.Objects;

/**
 * Una línea del archivo de liquidación diaria del proveedor (sección 9.2).
 *
 * @param providerPaymentId id del pago en el proveedor (obligatorio; clave de la línea en el día)
 * @param merchantReference nuestra referencia (attempt_id), si el proveedor la devuelve
 * @param status            estado ya traducido a nuestro vocabulario ({@code APPROVED}, {@code DECLINED}...)
 * @param amountMinor       monto en la unidad mínima de la moneda
 * @param currency          código ISO 4217
 */
public record SettlementLine(String providerPaymentId, String merchantReference, String status,
                             long amountMinor, String currency) {

    public SettlementLine {
        Objects.requireNonNull(providerPaymentId, "providerPaymentId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(currency, "currency");
        status = status.trim().toUpperCase(Locale.ROOT);
        currency = currency.trim().toUpperCase(Locale.ROOT);
    }
}
