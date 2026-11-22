package com.pagos.payments.application.port;

import com.pagos.payments.application.SettlementLine;
import java.time.LocalDate;
import java.util.List;

/**
 * Origen del archivo de liquidación diaria del proveedor (SFTP, API de reportes...).
 *
 * <p>No tiene implementación en este repositorio: sin un bean de este tipo, el job de
 * conciliación diaria no hace nada. La implementación traduce los estados del proveedor a
 * nuestro vocabulario, igual que {@code ProviderStatusMapper}.
 */
public interface SettlementFilePort {

    /** Líneas liquidadas por {@code provider} el día {@code runDate} (UTC). */
    List<SettlementLine> fetch(LocalDate runDate, String provider);
}
