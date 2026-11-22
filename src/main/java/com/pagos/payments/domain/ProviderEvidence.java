package com.pagos.payments.domain;

/**
 * Datos que el proveedor informa junto con un estado (webhook o consulta).
 * Se usan para completar el intento y para validar monto y moneda (regla R4).
 * Cualquier campo puede ser null si el proveedor no lo envía.
 */
public record ProviderEvidence(String providerPaymentId, Long amountMinor, String currency) {

    public static final ProviderEvidence NONE = new ProviderEvidence(null, null, null);

    public boolean amountMatches(PaymentAttempt attempt) {
        boolean amountOk = amountMinor == null || amountMinor == attempt.amountMinor();
        boolean currencyOk = currency == null || currency.equalsIgnoreCase(attempt.currency().trim());
        return amountOk && currencyOk;
    }
}
