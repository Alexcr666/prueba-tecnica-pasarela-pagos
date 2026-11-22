package com.pagos.payments.application;

import com.pagos.payments.config.PaymentsProperties;
import com.pagos.payments.domain.OrderStatus;
import com.pagos.payments.persistence.IdempotencyRepository;
import com.pagos.payments.persistence.OrderRepository;
import com.pagos.payments.persistence.OrderRepository.OrderRow;
import com.pagos.payments.persistence.PaymentAttemptRepository;
import com.pagos.payments.support.UuidV7;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    public record OrderResult(OrderRow order, boolean replay) {
    }

    private final OrderRepository orders;
    private final PaymentAttemptRepository attempts;
    private final IdempotencyRepository idempotency;
    private final PaymentsProperties props;

    public OrderService(OrderRepository orders, PaymentAttemptRepository attempts,
                        IdempotencyRepository idempotency, PaymentsProperties props) {
        this.orders = orders;
        this.attempts = attempts;
        this.idempotency = idempotency;
        this.props = props;
    }

    /** Crea la orden de forma idempotente: la clave y la orden se confirman en la misma transacción. */
    @Transactional
    public OrderResult create(String customerId, long amountMinor, String currency,
                              String idempotencyKey, String requestHash) {
        String id = UuidV7.next();
        if (!idempotency.tryInsert("POST /orders", idempotencyKey, requestHash, id)) {
            var existing = idempotency.find("POST /orders", idempotencyKey).orElseThrow();
            if (!existing.requestHash().equals(requestHash)) {
                throw ApiException.idempotencyMismatch();
            }
            return new OrderResult(get(existing.resourceId()), true);
        }
        orders.insert(id, customerId, amountMinor, currency, props.orderTtlMinutes());
        return new OrderResult(get(id), false);
    }

    @Transactional(readOnly = true)
    public OrderRow get(String id) {
        return orders.findById(id).orElseThrow(() -> ApiException.notFound("La orden"));
    }

    /** Regla R6: no se cancela una orden con un intento activo. */
    @Transactional
    public OrderRow cancel(String id) {
        OrderRow order = orders.lockForUpdate(id).orElseThrow(() -> ApiException.notFound("La orden"));
        if (order.status() != OrderStatus.AWAITING_PAYMENT) {
            throw ApiException.orderNotPayable(order.status().name());
        }
        attempts.findActiveByOrder(id).ifPresent(a -> {
            throw ApiException.activePaymentExists(a.id());
        });
        orders.updateStatusIf(id, List.of(OrderStatus.AWAITING_PAYMENT), OrderStatus.CANCELLED);
        return get(id);
    }

    /**
     * Expira una orden si sigue vencida y sin intento activo (regla R3). Se evalúa con la orden
     * bloqueada: la creación de un intento también la bloquea, así que no hay carrera.
     */
    @Transactional
    public boolean expireIfDue(String id) {
        OrderRow order = orders.lockForUpdate(id).orElse(null);
        if (order == null || order.status() != OrderStatus.AWAITING_PAYMENT || !order.expired()) {
            return false;
        }
        if (attempts.hasActiveAttempt(id)) {
            return false;
        }
        orders.updateStatusIf(id, List.of(OrderStatus.AWAITING_PAYMENT), OrderStatus.EXPIRED);
        log.info("Orden expirada order_id={}", id);
        return true;
    }
}
