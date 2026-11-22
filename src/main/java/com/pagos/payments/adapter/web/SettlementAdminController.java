package com.pagos.payments.adapter.web;

import com.pagos.payments.application.ApiException;
import com.pagos.payments.application.SettlementReconciliationService;
import com.pagos.payments.application.SettlementReconciliationService.Summary;
import com.pagos.payments.application.port.SettlementFilePort;
import com.pagos.payments.config.PaymentsProperties;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Operación de la conciliación diaria: re-ejecutar un día (archivo corregido o tardío) y ver
 * sus discrepancias. Es idempotente. En producción este endpoint solo es accesible desde la red
 * de operaciones (mTLS / rol de backoffice), no desde internet.
 */
@RestController
public class SettlementAdminController {

    private final ObjectProvider<SettlementFilePort> filePort;
    private final SettlementReconciliationService settlement;
    private final PaymentsProperties props;
    private final JdbcClient jdbc;

    public SettlementAdminController(ObjectProvider<SettlementFilePort> filePort,
                                     SettlementReconciliationService settlement,
                                     PaymentsProperties props, JdbcClient jdbc) {
        this.filePort = filePort;
        this.settlement = settlement;
        this.props = props;
        this.jdbc = jdbc;
    }

    @PostMapping("/admin/settlement/run")
    public Map<String, Object> run(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        SettlementFilePort port = filePort.getIfAvailable();
        if (port == null) {
            throw new ApiException(503, "SETTLEMENT_DISABLED", "No hay origen de liquidación configurado");
        }
        String provider = props.provider().name();
        List<com.pagos.payments.application.SettlementLine> lines;
        try {
            lines = port.fetch(date, provider);
        } catch (IllegalStateException | IllegalArgumentException e) {
            throw new ApiException(422, "SETTLEMENT_FILE", e.getMessage());
        }
        Summary s = settlement.loadAndCompare(date, provider, lines);
        return Map.of("summary", s, "discrepancies", discrepancies(date));
    }

    @GetMapping("/admin/settlement/discrepancies")
    public List<Map<String, Object>> discrepancies(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return jdbc.sql("""
                SELECT id, discrepancy_type, resolution, attempt_id, provider_payment_id,
                       our_status, provider_status, our_amount, provider_amount
                  FROM reconciliation_discrepancy
                 WHERE run_type = 'SETTLEMENT' AND settlement_date = :day AND provider = :provider
                 ORDER BY id
                """)
                .param("day", Date.valueOf(date)).param("provider", props.provider().name())
                .query((rs, n) -> {
                    var row = new java.util.LinkedHashMap<String, Object>();
                    row.put("id", rs.getLong("id"));
                    row.put("type", rs.getString("discrepancy_type"));
                    row.put("resolution", rs.getString("resolution"));
                    row.put("attemptId", rs.getString("attempt_id"));
                    row.put("providerPaymentId", rs.getString("provider_payment_id"));
                    row.put("ourStatus", rs.getString("our_status"));
                    row.put("providerStatus", rs.getString("provider_status"));
                    row.put("ourAmount", rs.getObject("our_amount", Long.class));
                    row.put("providerAmount", rs.getObject("provider_amount", Long.class));
                    return (Map<String, Object>) row;
                })
                .list();
    }
}
