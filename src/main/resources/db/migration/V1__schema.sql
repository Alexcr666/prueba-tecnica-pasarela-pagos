-- =====================================================================
-- Payment Service - esquema inicial (Oracle 19c+)
-- Convenciones: ids UUIDv7 generados en la aplicación, fechas TIMESTAMP en UTC,
-- montos NUMBER(19) en la unidad mínima de la moneda.
-- =====================================================================

-- Orden ---------------------------------------------------------------
CREATE TABLE customer_order (
  id            VARCHAR2(36)  PRIMARY KEY,
  customer_id   VARCHAR2(64)  NOT NULL,
  amount_minor  NUMBER(19)    NOT NULL CHECK (amount_minor > 0),
  currency      CHAR(3)       NOT NULL,
  status        VARCHAR2(20)  NOT NULL CHECK (status IN
                  ('AWAITING_PAYMENT','PAID','EXPIRED','CANCELLED','REQUIRES_REVIEW')),
  expires_at    TIMESTAMP     NOT NULL,
  version       NUMBER(10)    DEFAULT 0 NOT NULL,
  created_at    TIMESTAMP     DEFAULT SYS_EXTRACT_UTC(SYSTIMESTAMP) NOT NULL,
  updated_at    TIMESTAMP     DEFAULT SYS_EXTRACT_UTC(SYSTIMESTAMP) NOT NULL
);
CREATE INDEX ix_order_expiry   ON customer_order (status, expires_at);
CREATE INDEX ix_order_customer ON customer_order (customer_id, created_at);

-- Intento de pago -----------------------------------------------------
CREATE TABLE payment_attempt (
  id                  VARCHAR2(36)  PRIMARY KEY,      -- referencia enviada al proveedor
  order_id            VARCHAR2(36)  NOT NULL REFERENCES customer_order (id),
  attempt_no          NUMBER(3)     NOT NULL,
  provider            VARCHAR2(20)  NOT NULL,
  provider_payment_id VARCHAR2(64),
  amount_minor        NUMBER(19)    NOT NULL,
  currency            CHAR(3)       NOT NULL,
  status              VARCHAR2(20)  NOT NULL CHECK (status IN
                        ('CREATED','PENDING','UNKNOWN','APPROVED','DECLINED','EXPIRED','FAILED')),
  status_reason       VARCHAR2(100),
  redirect_url        VARCHAR2(1000),
  check_count         NUMBER(5)     DEFAULT 0 NOT NULL,
  next_check_at       TIMESTAMP,
  version             NUMBER(10)    DEFAULT 0 NOT NULL,
  created_at          TIMESTAMP     DEFAULT SYS_EXTRACT_UTC(SYSTIMESTAMP) NOT NULL,
  updated_at          TIMESTAMP     DEFAULT SYS_EXTRACT_UTC(SYSTIMESTAMP) NOT NULL,
  CONSTRAINT uq_attempt_no UNIQUE (order_id, attempt_no)   -- también indexa la FK
);
-- Como máximo un intento activo por orden (regla R1).
-- Oracle no indexa claves enteramente NULL: el índice solo contiene intentos activos.
CREATE UNIQUE INDEX ux_attempt_active ON payment_attempt (
  CASE WHEN status IN ('CREATED','PENDING','UNKNOWN') THEN order_id END);
-- Id del proveedor único, ignorando los intentos que aún no lo tienen.
-- Un UNIQUE (provider, provider_payment_id) normal trataría como duplicadas
-- dos filas con el mismo provider y provider_payment_id NULL.
CREATE UNIQUE INDEX ux_provider_payment ON payment_attempt (
  CASE WHEN provider_payment_id IS NOT NULL THEN provider END, provider_payment_id);
CREATE INDEX ix_attempt_recon ON payment_attempt (status, next_check_at);

-- Historial de transiciones (solo inserción) ---------------------------
-- En producción: PARTITION BY RANGE (occurred_at) INTERVAL (NUMTOYMINTERVAL(1,'MONTH'))
-- (requiere la opción Partitioning; se omite aquí para que corra en Oracle Free).
CREATE TABLE payment_status_history (
  id                 NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  attempt_id         VARCHAR2(36)  NOT NULL REFERENCES payment_attempt (id),
  order_id           VARCHAR2(36)  NOT NULL,
  from_status        VARCHAR2(20),
  to_status          VARCHAR2(20)  NOT NULL,
  source             VARCHAR2(20)  NOT NULL CHECK (source IN
                       ('API','WEBHOOK','RECONCILIATION','SETTLEMENT','MANUAL')),
  provider_event_ref NUMBER,
  correlation_id     VARCHAR2(64)  NOT NULL,
  detail             VARCHAR2(400),
  occurred_at        TIMESTAMP     DEFAULT SYS_EXTRACT_UTC(SYSTIMESTAMP) NOT NULL
);
CREATE INDEX ix_history_attempt ON payment_status_history (attempt_id, occurred_at);
CREATE INDEX ix_history_order   ON payment_status_history (order_id, occurred_at);

-- Inbox de webhooks ---------------------------------------------------
CREATE TABLE provider_event (
  id                  NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  provider            VARCHAR2(20)  NOT NULL,
  provider_event_id   VARCHAR2(100) NOT NULL,
  merchant_reference  VARCHAR2(36),
  provider_payment_id VARCHAR2(64),
  event_type          VARCHAR2(50)  NOT NULL,
  reported_status     VARCHAR2(20),
  reported_amount     NUMBER(19),
  reported_currency   CHAR(3),
  payload             CLOB          NOT NULL CHECK (payload IS JSON),
  processing_status   VARCHAR2(20)  DEFAULT 'RECEIVED' NOT NULL CHECK (processing_status IN
                        ('RECEIVED','PROCESSED','IGNORED_STALE','FAILED')),
  attempts            NUMBER(3)     DEFAULT 0 NOT NULL,
  next_attempt_at     TIMESTAMP     DEFAULT SYS_EXTRACT_UTC(SYSTIMESTAMP) NOT NULL,
  last_error          VARCHAR2(400),
  received_at         TIMESTAMP     DEFAULT SYS_EXTRACT_UTC(SYSTIMESTAMP) NOT NULL,
  processed_at        TIMESTAMP,
  CONSTRAINT uq_provider_event UNIQUE (provider, provider_event_id)
);
CREATE INDEX ix_event_pending ON provider_event (processing_status, next_attempt_at);

-- Outbox --------------------------------------------------------------
CREATE TABLE outbox_event (
  id              VARCHAR2(36)  PRIMARY KEY,           -- message_id en RabbitMQ
  aggregate_type  VARCHAR2(30)  NOT NULL,
  aggregate_id    VARCHAR2(36)  NOT NULL,
  event_type      VARCHAR2(50)  NOT NULL,
  payload         CLOB          NOT NULL CHECK (payload IS JSON),
  status          VARCHAR2(10)  DEFAULT 'NEW' NOT NULL CHECK (status IN ('NEW','SENT')),
  attempts        NUMBER(5)     DEFAULT 0 NOT NULL,
  next_attempt_at TIMESTAMP     DEFAULT SYS_EXTRACT_UTC(SYSTIMESTAMP) NOT NULL,
  created_at      TIMESTAMP     DEFAULT SYS_EXTRACT_UTC(SYSTIMESTAMP) NOT NULL,
  sent_at         TIMESTAMP
);
CREATE INDEX ix_outbox_pending ON outbox_event (status, next_attempt_at);

-- Idempotencia de la API ------------------------------------------------
CREATE TABLE idempotency_record (
  scope          VARCHAR2(100) NOT NULL,
  idem_key       VARCHAR2(100) NOT NULL,
  request_hash   CHAR(64)      NOT NULL,
  resource_id    VARCHAR2(36),
  response_code  NUMBER(3),
  response_body  CLOB,
  created_at     TIMESTAMP     DEFAULT SYS_EXTRACT_UTC(SYSTIMESTAMP) NOT NULL,
  expires_at     TIMESTAMP     NOT NULL,
  CONSTRAINT pk_idempotency PRIMARY KEY (scope, idem_key)
);

-- Discrepancias de conciliación ------------------------------------------
CREATE TABLE reconciliation_discrepancy (
  id                  NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  run_type            VARCHAR2(20)  NOT NULL CHECK (run_type IN ('INCREMENTAL','SETTLEMENT')),
  attempt_id          VARCHAR2(36),
  provider_payment_id VARCHAR2(64),
  our_status          VARCHAR2(20),
  provider_status     VARCHAR2(20),
  our_amount          NUMBER(19),
  provider_amount     NUMBER(19),
  resolution          VARCHAR2(20)  DEFAULT 'OPEN' NOT NULL CHECK (resolution IN
                        ('OPEN','AUTO_FIXED','MANUAL_FIXED','ACCEPTED')),
  detected_at         TIMESTAMP     DEFAULT SYS_EXTRACT_UTC(SYSTIMESTAMP) NOT NULL,
  resolved_at         TIMESTAMP
);
CREATE INDEX ix_discrepancy_open ON reconciliation_discrepancy (resolution, detected_at);

-- ShedLock ------------------------------------------------------------
CREATE TABLE shedlock (
  name       VARCHAR2(64)  NOT NULL PRIMARY KEY,
  lock_until TIMESTAMP(3)  NOT NULL,
  locked_at  TIMESTAMP(3)  NOT NULL,
  locked_by  VARCHAR2(255) NOT NULL
);
