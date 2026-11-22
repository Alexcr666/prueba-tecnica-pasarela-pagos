package com.pagos.payments.application;

import com.pagos.payments.config.PaymentsProperties;
import com.pagos.payments.persistence.OutboxRepository;
import com.pagos.payments.persistence.OutboxRepository.OutboxRow;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Publica el outbox a RabbitMQ con confirmación del broker (publisher confirms).
 *
 * <p>Entrega al menos una vez: si la instancia muere tras publicar y antes de marcar SENT, el
 * evento se publica de nuevo. Los consumidores deduplican por {@code message_id}.
 *
 * <p>Los mensajes se publican como {@code mandatory}: si ninguna cola está enlazada para esa
 * routing key, el broker los devuelve (basic.return, siempre antes del ack) y el evento se
 * reprograma en vez de marcarse SENT. Sin esto RabbitMQ confirma y descarta el mensaje.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRepository outbox;
    private final RabbitTemplate rabbit;
    private final TransactionTemplate tx;
    private final PaymentsProperties.Outbox config;
    private final MeterRegistry metrics;
    private final Set<String> returned = ConcurrentHashMap.newKeySet();

    public OutboxRelay(OutboxRepository outbox, RabbitTemplate rabbit, TransactionTemplate tx,
                       PaymentsProperties props, MeterRegistry metrics) {
        this.outbox = outbox;
        this.rabbit = rabbit;
        this.tx = tx;
        this.config = props.outbox();
        this.metrics = metrics;
        rabbit.setMandatory(true);
        rabbit.setReturnsCallback(r -> returned.add(r.getMessage().getMessageProperties().getMessageId()));
    }

    @Scheduled(fixedDelayString = "${payments.outbox.poll-ms:500}")
    public void poll() {
        if (config.relayEnabled()) {
            relayBatch();
        }
    }

    public int relayBatch() {
        List<OutboxRow> rows = tx.execute(status -> outbox.claim(config.batchSize(), config.leaseSeconds()));
        for (OutboxRow row : rows) {
            try {
                publish(row);
                outbox.markSent(row.id());
                metrics.counter("payments.outbox.published", "type", row.eventType()).increment();
            } catch (RuntimeException e) {
                outbox.reschedule(row.id());
                metrics.counter("payments.outbox.failures").increment();
                log.warn("No se pudo publicar outbox_id={} error={}", row.id(), e.getMessage());
            }
        }
        return rows.size();
    }

    private void publish(OutboxRow row) {
        Message message = MessageBuilder.withBody(row.payload().getBytes(StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setMessageId(row.id())
                .setHeader("aggregate_type", row.aggregateType())
                .setHeader("aggregate_id", row.aggregateId())
                .build();
        rabbit.invoke(ops -> {
            ops.send(config.exchange(), row.eventType(), message);
            ops.waitForConfirmsOrDie(config.confirmTimeoutMs());
            return Boolean.TRUE;
        });
        if (returned.remove(row.id())) {
            metrics.counter("payments.outbox.unroutable", "type", row.eventType()).increment();
            throw new IllegalStateException("Mensaje no enrutable: ninguna cola enlazada a "
                    + config.exchange() + " para " + row.eventType());
        }
    }
}
