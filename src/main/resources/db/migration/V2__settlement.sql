-- =====================================================================
-- Payment Service - conciliación diaria contra la liquidación (sección 9.2)
-- Compatible con Oracle 19c y Oracle Free 23.
--
-- Flyway (parser de Oracle) separa las sentencias SQL por ';' y los bloques
-- PL/SQL (CREATE PACKAGE / PACKAGE BODY, DECLARE / BEGIN) por una línea que
-- contiene solo '/'. Dentro de los bloques PL/SQL no debe haber ninguna línea
-- que empiece por '/'.
-- =====================================================================

-- Staging de la liquidación ----------------------------------------------
-- Java carga aquí el archivo del día (borra y recarga run_date + provider en
-- la misma transacción, así que recargar un archivo corregido es seguro).
CREATE TABLE settlement_line (
  run_date            DATE          NOT NULL,
  provider            VARCHAR2(20)  NOT NULL,
  provider_payment_id VARCHAR2(64)  NOT NULL,
  merchant_reference  VARCHAR2(36),                 -- nuestro attempt_id, si el proveedor lo devuelve
  status              VARCHAR2(20)  NOT NULL,       -- ya traducido a nuestro vocabulario (APPROVED, DECLINED...)
  amount_minor        NUMBER(19)    NOT NULL,
  currency            CHAR(3)       NOT NULL,
  loaded_at           TIMESTAMP     DEFAULT SYS_EXTRACT_UTC(SYSTIMESTAMP) NOT NULL,
  CONSTRAINT pk_settlement_line PRIMARY KEY (run_date, provider, provider_payment_id)
);
-- Búsqueda de "aprobado aquí y no liquidado" por nuestra referencia.
CREATE INDEX ix_settlement_reference ON settlement_line (run_date, provider, merchant_reference);

-- Intentos aprobados en un día (caso "aprobado aquí y no liquidado").
CREATE INDEX ix_attempt_status_updated ON payment_attempt (status, updated_at);

-- Discrepancias: tipo y datos de la liquidación ---------------------------
-- Columnas opcionales: las discrepancias INCREMENTAL existentes no las usan.
ALTER TABLE reconciliation_discrepancy ADD (
  discrepancy_type  VARCHAR2(30) CONSTRAINT ck_discrepancy_type CHECK (discrepancy_type IN
                      ('APPROVED_NOT_HERE','NOT_SETTLED','AMOUNT_MISMATCH')),
  settlement_date   DATE,
  provider          VARCHAR2(20),
  our_currency      CHAR(3),
  provider_currency CHAR(3)
);
ALTER TABLE reconciliation_discrepancy ADD CONSTRAINT ck_settlement_discrepancy CHECK (
  run_type <> 'SETTLEMENT'
  OR (discrepancy_type IS NOT NULL AND settlement_date IS NOT NULL AND provider IS NOT NULL));

-- Como máximo una discrepancia OPEN igual por (tipo, proveedor, día, pago).
-- El pago se identifica por el id del proveedor o, si no lo hay, por nuestro attempt_id.
-- Solo indexa discrepancias SETTLEMENT abiertas (Oracle no indexa claves enteramente NULL):
-- al resolverse salen del índice. Es la red de seguridad del NOT EXISTS de pkg_settlement.
CREATE UNIQUE INDEX ux_discrepancy_settlement_open ON reconciliation_discrepancy (
  CASE WHEN run_type = 'SETTLEMENT' AND resolution = 'OPEN' THEN discrepancy_type END,
  CASE WHEN run_type = 'SETTLEMENT' AND resolution = 'OPEN' THEN provider END,
  CASE WHEN run_type = 'SETTLEMENT' AND resolution = 'OPEN' THEN settlement_date END,
  CASE WHEN run_type = 'SETTLEMENT' AND resolution = 'OPEN' THEN NVL(provider_payment_id, attempt_id) END);

-- Cruce línea de liquidación -> intento --------------------------------------
-- Primero por nuestra referencia (PK del intento); si no viene o no existe, por el id
-- del proveedor. La condición sobre provider_payment_id replica la expresión del índice
-- UX_PROVIDER_PAYMENT para que el optimizador pueda usarlo.
CREATE OR REPLACE VIEW v_settlement_match AS
SELECT sl.run_date,
       sl.provider,
       sl.provider_payment_id,
       sl.merchant_reference,
       sl.status       AS provider_status,
       sl.amount_minor AS provider_amount,
       sl.currency     AS provider_currency,
       pa.id           AS attempt_id,
       pa.status       AS our_status,
       pa.amount_minor AS our_amount,
       pa.currency     AS our_currency
  FROM settlement_line sl
  LEFT JOIN payment_attempt by_ref
         ON by_ref.id = sl.merchant_reference
        AND by_ref.provider = sl.provider
  LEFT JOIN payment_attempt by_ppid
         ON CASE WHEN by_ppid.provider_payment_id IS NOT NULL THEN by_ppid.provider END = sl.provider
        AND by_ppid.provider_payment_id = sl.provider_payment_id
  LEFT JOIN payment_attempt pa
         ON pa.id = NVL(by_ref.id, by_ppid.id);

-- Paquete de comparación ------------------------------------------------------
CREATE OR REPLACE PACKAGE pkg_settlement AS
  -- Tipos de discrepancia (columna reconciliation_discrepancy.discrepancy_type).
  c_approved_not_here CONSTANT VARCHAR2(30) := 'APPROVED_NOT_HERE';
  c_not_settled       CONSTANT VARCHAR2(30) := 'NOT_SETTLED';
  c_amount_mismatch   CONSTANT VARCHAR2(30) := 'AMOUNT_MISMATCH';

  -- Cruza por conjuntos settlement_line (run_date, provider) contra payment_attempt e
  -- inserta en reconciliation_discrepancy (run_type = 'SETTLEMENT'):
  --   APPROVED_NOT_HERE: aprobado en la liquidación y no APPROVED aquí (o desconocido aquí);
  --   NOT_SETTLED:       APPROVED aquí ese día (updated_at) y sin línea APPROVED en la liquidación;
  --   AMOUNT_MISMATCH:   línea aprobada con monto o moneda distintos a los del intento.
  -- Idempotente: no inserta si ya hay una discrepancia OPEN igual. No hace COMMIT:
  -- la transacción la controla quien llama.
  PROCEDURE compare(p_run_date IN DATE, p_provider IN VARCHAR2);
END pkg_settlement;
/

CREATE OR REPLACE PACKAGE BODY pkg_settlement AS

  PROCEDURE compare(p_run_date IN DATE, p_provider IN VARCHAR2) IS
    v_day  DATE;
    v_from TIMESTAMP;
    v_to   TIMESTAMP;
  BEGIN
    IF p_run_date IS NULL OR p_provider IS NULL THEN
      RAISE_APPLICATION_ERROR(-20010, 'pkg_settlement.compare: p_run_date y p_provider son obligatorios');
    END IF;
    v_day  := TRUNC(p_run_date);
    v_from := CAST(v_day AS TIMESTAMP);       -- updated_at está en UTC
    v_to   := CAST(v_day + 1 AS TIMESTAMP);

    -- (a) Aprobado en la liquidación y no APPROVED aquí -----------------------
    INSERT INTO reconciliation_discrepancy
      (run_type, discrepancy_type, settlement_date, provider, attempt_id, provider_payment_id,
       our_status, provider_status, our_amount, provider_amount, our_currency, provider_currency)
    SELECT 'SETTLEMENT', c_approved_not_here, v_day, m.provider, m.attempt_id, m.provider_payment_id,
           m.our_status, m.provider_status, m.our_amount, m.provider_amount, m.our_currency, m.provider_currency
      FROM v_settlement_match m
     WHERE m.run_date = v_day
       AND m.provider = p_provider
       AND m.provider_status = 'APPROVED'
       AND (m.our_status IS NULL OR m.our_status <> 'APPROVED')
       AND NOT EXISTS (
             SELECT 1
               FROM reconciliation_discrepancy d
              WHERE d.run_type = 'SETTLEMENT'
                AND d.resolution = 'OPEN'
                AND d.discrepancy_type = c_approved_not_here
                AND d.provider = m.provider
                AND d.settlement_date = v_day
                AND NVL(d.provider_payment_id, d.attempt_id) = m.provider_payment_id);

    -- (b) APPROVED aquí ese día y sin línea APPROVED en la liquidación ---------
    -- provider_status queda NULL si el pago no aparece, o con el estado informado
    -- (p. ej. DECLINED) si aparece pero no aprobado.
    INSERT INTO reconciliation_discrepancy
      (run_type, discrepancy_type, settlement_date, provider, attempt_id, provider_payment_id,
       our_status, provider_status, our_amount, provider_amount, our_currency, provider_currency)
    SELECT 'SETTLEMENT', c_not_settled, v_day, pa.provider, pa.id, pa.provider_payment_id,
           pa.status,
           (SELECT MAX(sl.status)
              FROM settlement_line sl
             WHERE sl.run_date = v_day
               AND sl.provider = pa.provider
               AND (sl.merchant_reference = pa.id OR sl.provider_payment_id = pa.provider_payment_id)),
           pa.amount_minor, NULL, pa.currency, NULL
      FROM payment_attempt pa
     WHERE pa.status = 'APPROVED'
       AND pa.provider = p_provider
       AND pa.updated_at >= v_from
       AND pa.updated_at <  v_to
       AND NOT EXISTS (
             SELECT 1
               FROM settlement_line sl
              WHERE sl.run_date = v_day
                AND sl.provider = pa.provider
                AND sl.merchant_reference = pa.id
                AND sl.status = 'APPROVED')
       AND NOT EXISTS (
             SELECT 1
               FROM settlement_line sl
              WHERE sl.run_date = v_day
                AND sl.provider = pa.provider
                AND sl.provider_payment_id = pa.provider_payment_id
                AND sl.status = 'APPROVED')
       AND NOT EXISTS (
             SELECT 1
               FROM reconciliation_discrepancy d
              WHERE d.run_type = 'SETTLEMENT'
                AND d.resolution = 'OPEN'
                AND d.discrepancy_type = c_not_settled
                AND d.provider = pa.provider
                AND d.settlement_date = v_day
                AND NVL(d.provider_payment_id, d.attempt_id) = NVL(pa.provider_payment_id, pa.id));

    -- (c) Diferencia de monto o moneda en una línea aprobada -------------------
    INSERT INTO reconciliation_discrepancy
      (run_type, discrepancy_type, settlement_date, provider, attempt_id, provider_payment_id,
       our_status, provider_status, our_amount, provider_amount, our_currency, provider_currency)
    SELECT 'SETTLEMENT', c_amount_mismatch, v_day, m.provider, m.attempt_id, m.provider_payment_id,
           m.our_status, m.provider_status, m.our_amount, m.provider_amount, m.our_currency, m.provider_currency
      FROM v_settlement_match m
     WHERE m.run_date = v_day
       AND m.provider = p_provider
       AND m.provider_status = 'APPROVED'
       AND m.attempt_id IS NOT NULL
       AND (m.provider_amount <> m.our_amount OR m.provider_currency <> m.our_currency)
       AND NOT EXISTS (
             SELECT 1
               FROM reconciliation_discrepancy d
              WHERE d.run_type = 'SETTLEMENT'
                AND d.resolution = 'OPEN'
                AND d.discrepancy_type = c_amount_mismatch
                AND d.provider = m.provider
                AND d.settlement_date = v_day
                AND NVL(d.provider_payment_id, d.attempt_id) = m.provider_payment_id);
  END compare;

END pkg_settlement;
/

-- Oracle crea un paquete con errores de compilación como INVALID y solo devuelve un
-- aviso, que Flyway no trata como fallo. Se comprueba aquí para que la migración falle
-- en el despliegue y no en la primera llamada.
DECLARE
  v_errors VARCHAR2(4000);
BEGIN
  SELECT LISTAGG(type || ' ' || line || ':' || position || ' ' || SUBSTR(text, 1, 200), ' | ')
           WITHIN GROUP (ORDER BY type, sequence)
    INTO v_errors
    FROM user_errors
   WHERE name = 'PKG_SETTLEMENT'
     AND attribute = 'ERROR'
     AND ROWNUM <= 10;
  IF v_errors IS NOT NULL THEN
    RAISE_APPLICATION_ERROR(-20001, SUBSTR('pkg_settlement no compila: ' || v_errors, 1, 2000));
  END IF;
END;
/
