package com.pagos.payments.application;

import com.pagos.payments.config.PaymentsProperties;
import com.pagos.payments.persistence.ProviderEventRepository;
import com.pagos.payments.support.Correlation;
import com.pagos.payments.support.UuidV7;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Procesa el inbox de webhooks. Corre en todas las instancias: SKIP LOCKED reparte los eventos
 * y el lease permite retomar los de una instancia caída.
 */
@Component
public class InboxProcessor {

    private static final Logger log = LoggerFactory.getLogger(InboxProcessor.class);

    private final ProviderEventRepository events;
    private final InboxEventHandler handler;
    private final TransactionTemplate tx;
    private final PaymentsProperties.Inbox config;
    private final MeterRegistry metrics;

    public InboxProcessor(ProviderEventRepository events, InboxEventHandler handler, TransactionTemplate tx,
                          PaymentsProperties props, MeterRegistry metrics) {
        this.events = events;
        this.handler = handler;
        this.tx = tx;
        this.config = props.inbox();
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${payments.inbox.poll-ms:1000}")
    public void poll() {
        if (!config.enabled()) {
            return;
        }
        processBatch();
    }

    /** Público para las pruebas: procesa un lote y devuelve cuántos eventos tomó. */
    public int processBatch() {
        List<Long> ids = tx.execute(status -> events.claim(config.batchSize(), config.leaseSeconds()));
        for (Long id : ids) {
            Correlation.run(UuidV7.next(), () -> processOne(id));
        }
        return ids.size();
    }

    private void processOne(long id) {
        try {
            handler.handle(id);
        } catch (RuntimeException e) {
            metrics.counter("payments.inbox.failures").increment();
            log.warn("Fallo procesando evento provider_event={} error={}", id, e.getMessage());
            tx.executeWithoutResult(status -> events.recordFailure(id, e.getMessage(), config.maxAttempts()));
        }
    }
}
