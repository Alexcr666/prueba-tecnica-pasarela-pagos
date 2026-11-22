package com.pagos.payments.application;

import com.pagos.payments.application.port.SettlementFilePort;
import com.pagos.payments.config.PaymentsProperties;
import com.pagos.payments.support.Correlation;
import com.pagos.payments.support.UuidV7;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Job diario de conciliación contra la liquidación del día anterior (UTC). Un solo nodo por
 * corrida (ShedLock).
 *
 * <p>El archivo llega por {@link SettlementFilePort}, que no tiene implementación en este
 * repositorio: sin ese bean el job no hace nada y el arranque no se rompe. Para no registrar
 * siquiera el disparador, {@code payments.settlement.cron=-}.
 */
@Component
public class SettlementReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(SettlementReconciliationJob.class);

    private final ObjectProvider<SettlementFilePort> filePort;
    private final SettlementReconciliationService settlement;
    private final PaymentsProperties props;
    private final Clock clock;

    public SettlementReconciliationJob(ObjectProvider<SettlementFilePort> filePort,
                                       SettlementReconciliationService settlement,
                                       PaymentsProperties props, Clock clock) {
        this.filePort = filePort;
        this.settlement = settlement;
        this.props = props;
        this.clock = clock;
        if (filePort.getIfAvailable() == null) {
            log.info("Sin SettlementFilePort: la conciliación diaria contra la liquidación está inactiva");
        }
    }

    @Scheduled(cron = "${payments.settlement.cron:0 30 4 * * *}", zone = "UTC")
    @SchedulerLock(name = "payments-settlement", lockAtMostFor = "PT2H", lockAtLeastFor = "PT1M")
    public void run() {
        SettlementFilePort port = filePort.getIfAvailable();
        if (port == null) {
            return;
        }
        LocalDate runDate = LocalDate.now(clock).minusDays(1);
        String provider = props.provider().name();
        Correlation.run(UuidV7.next(), () -> {
            List<SettlementLine> lines = port.fetch(runDate, provider);
            settlement.loadAndCompare(runDate, provider, lines);
        });
    }
}
