package com.pagos.payments.adapter.web;

import com.pagos.payments.application.ApiException;
import com.pagos.payments.application.OrderService;
import com.pagos.payments.application.PaymentInitiationService;
import com.pagos.payments.domain.PaymentAttempt;
import com.pagos.payments.domain.PaymentStatus;
import com.pagos.payments.persistence.OrderRepository.OrderRow;
import com.pagos.payments.persistence.HistoryRepository;
import com.pagos.payments.persistence.PaymentAttemptRepository;
import com.pagos.payments.support.Hashing;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.time.Instant;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OrderController {

    public record CreateOrderRequest(@NotBlank String customerId, @Positive long amountMinor,
                                     @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency) {
    }

    public record OrderResponse(String id, String status, long amountMinor, String currency, Instant expiresAt) {
        static OrderResponse of(OrderRow o) {
            return new OrderResponse(o.id(), o.status().name(), o.amountMinor(), o.currency(), o.expiresAt());
        }
    }

    public record PaymentResponse(String attemptId, String orderId, String status, String providerPaymentId,
                                  String redirectUrl, String statusReason) {
        static PaymentResponse of(PaymentAttempt a) {
            return new PaymentResponse(a.id(), a.orderId(), a.status().name(), a.providerPaymentId(),
                    a.redirectUrl(), a.statusReason());
        }
    }

    private final OrderService orders;
    private final PaymentInitiationService payments;
    private final PaymentAttemptRepository attempts;
    private final HistoryRepository history;

    public OrderController(OrderService orders, PaymentInitiationService payments, PaymentAttemptRepository attempts,
                           HistoryRepository history) {
        this.orders = orders;
        this.payments = payments;
        this.attempts = attempts;
        this.history = history;
    }

    @PostMapping("/orders")
    public ResponseEntity<OrderResponse> create(@RequestHeader("Idempotency-Key") String idempotencyKey,
                                                @Valid @RequestBody CreateOrderRequest body) {
        String hash = Hashing.sha256Hex(body.customerId() + "|" + body.amountMinor() + "|" + body.currency());
        var result = orders.create(body.customerId(), body.amountMinor(), body.currency(), idempotencyKey, hash);
        return ResponseEntity.status(result.replay() ? 200 : 201).body(OrderResponse.of(result.order()));
    }

    @GetMapping("/orders/{id}")
    public OrderResponse get(@PathVariable String id) {
        return OrderResponse.of(orders.get(id));
    }

    @PostMapping("/orders/{id}/cancel")
    public OrderResponse cancel(@PathVariable String id) {
        return OrderResponse.of(orders.cancel(id));
    }

    /**
     * Inicia un pago. 201 con redirectUrl si el proveedor aceptó; 202 si el resultado es incierto
     * (el cliente consulta GET /payments/{id}); 200 si es una repetición de la misma clave.
     */
    @PostMapping("/orders/{id}/payments")
    public ResponseEntity<PaymentResponse> pay(@PathVariable String id,
                                               @RequestHeader("Idempotency-Key") String idempotencyKey) {
        var result = payments.initiate(id, idempotencyKey);
        int status = result.replay() ? 200 : result.attempt().status() == PaymentStatus.UNKNOWN ? 202 : 201;
        return ResponseEntity.status(status).body(PaymentResponse.of(result.attempt()));
    }

    /** La página de retorno consulta aquí: el estado sale de Oracle, nunca de la URL de retorno. */
    @GetMapping("/payments/{attemptId}")
    public PaymentResponse payment(@PathVariable String attemptId) {
        return PaymentResponse.of(attempts.findById(attemptId).orElseThrow(() -> ApiException.notFound("El pago")));
    }

    /** Ciclo de vida completo del intento: cada transición con su origen, motivo y correlation_id. */
    @GetMapping("/payments/{attemptId}/history")
    public List<HistoryRepository.Entry> history(@PathVariable String attemptId) {
        attempts.findById(attemptId).orElseThrow(() -> ApiException.notFound("El pago"));
        return history.findByAttempt(attemptId);
    }
}
