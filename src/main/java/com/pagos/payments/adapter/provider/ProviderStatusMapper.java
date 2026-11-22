package com.pagos.payments.adapter.provider;

import com.pagos.payments.domain.PaymentStatus;
import java.util.Locale;

/**
 * Traduce los estados del proveedor a los nuestros. Un estado desconocido devuelve null: el
 * evento no se aplica y queda para revisión, en lugar de adivinar.
 */
public final class ProviderStatusMapper {

    private ProviderStatusMapper() {
    }

    public static PaymentStatus map(String providerStatus) {
        if (providerStatus == null) {
            return null;
        }
        return switch (providerStatus.toUpperCase(Locale.ROOT)) {
            case "APPROVED", "CAPTURED", "PAID" -> PaymentStatus.APPROVED;
            case "DECLINED", "REJECTED", "FAILED" -> PaymentStatus.DECLINED;
            case "PENDING", "IN_PROGRESS", "CREATED", "REVIEW" -> PaymentStatus.PENDING;
            case "EXPIRED", "ABANDONED", "CANCELLED" -> PaymentStatus.EXPIRED;
            default -> null;
        };
    }
}
