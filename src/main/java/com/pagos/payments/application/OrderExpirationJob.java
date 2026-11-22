package com.pagos.payments.application;

import com.pagos.payments.config.PaymentsProperties;
import com.pagos.payments.persistence.OrderRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Expira órdenes vencidas sin intento activo (regla R3). Un solo nodo por corrida. */
@Component
public class OrderExpirationJob {

    private final OrderRepository orders;
    private final OrderService orderService;
    private final PaymentsProperties props;

    public OrderExpirationJob(OrderRepository orders, OrderService orderService, PaymentsProperties props) {
        this.orders = orders;
        this.orderService = orderService;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${payments.expiration.interval-ms:60000}")
    @SchedulerLock(name = "payments-order-expiration", lockAtMostFor = "PT5M")
    public void run() {
        if (!props.expiration().enabled()) {
            return;
        }
        for (String id : orders.findExpirationCandidates(500)) {
            orderService.expireIfDue(id);
        }
    }
}
