package com.pagos.payments.adapter.settlement;

import com.pagos.payments.application.SettlementLine;
import com.pagos.payments.application.port.SettlementFilePort;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Lee la liquidación diaria desde un directorio: {@code <dir>/<PROVIDER>_<yyyy-MM-dd>.csv}.
 *
 * <p>En producción el directorio es el buzón donde un proceso SFTP deja el archivo del
 * proveedor; en local es {@code local/liquidacion}. Formato (con encabezado):
 * <pre>provider_payment_id,merchant_reference,status,amount_minor,currency</pre>
 * Si el archivo no llegó se lanza excepción: el job falla, se registra y alerta, y no se
 * concilia contra una liquidación vacía (eso marcaría todo como NOT_SETTLED).
 */
@Component
@ConditionalOnProperty(name = "payments.settlement.dir")
public class CsvSettlementFileAdapter implements SettlementFilePort {

    private final Path dir;

    public CsvSettlementFileAdapter(@Value("${payments.settlement.dir}") String dir) {
        this.dir = Path.of(dir);
    }

    @Override
    public List<SettlementLine> fetch(LocalDate runDate, String provider) {
        Path file = dir.resolve(provider + "_" + runDate + ".csv");
        if (!Files.isReadable(file)) {
            throw new IllegalStateException("Liquidación no disponible: " + file.toAbsolutePath());
        }
        try {
            List<String> rows = Files.readAllLines(file, StandardCharsets.UTF_8);
            List<SettlementLine> lines = new ArrayList<>();
            for (int i = 1; i < rows.size(); i++) {            // fila 0 = encabezado
                String row = rows.get(i).strip();
                if (row.isEmpty() || row.startsWith("#")) {
                    continue;
                }
                String[] c = row.split(",", -1);
                if (c.length != 5) {
                    throw new IllegalArgumentException("Línea " + (i + 1) + " mal formada en " + file + ": " + row);
                }
                lines.add(new SettlementLine(c[0].strip(), c[1].isBlank() ? null : c[1].strip(), c[2],
                        Long.parseLong(c[3].strip()), c[4]));
            }
            return lines;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
