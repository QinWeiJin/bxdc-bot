-- Requires spring.jpa.defer-datasource-initialization=true when using spring.sql.init.mode=always,
-- so Hibernate creates tables before this script (see application.properties / application-prod.example.properties).
-- Compatible with MySQL 5.7+ and early 8.0.x (e.g. 8.0.15): `ADD COLUMN IF NOT EXISTS` is not available there;
-- use INFORMATION_SCHEMA + prepared statements instead.

SET @db = DATABASE();

-- skills.requires_confirmation
SET @sql = (
  SELECT IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = @db AND table_name = 'skills' AND column_name = 'requires_confirmation') > 0,
    'SELECT 1',
    'ALTER TABLE skills ADD COLUMN requires_confirmation BOOLEAN DEFAULT FALSE'
  )
);
PREPARE stmt FROM @sql;
 EXECUTE stmt;
 DEALLOCATE PREPARE stmt;

UPDATE skills
SET requires_confirmation = FALSE
WHERE requires_confirmation IS NULL;

ALTER TABLE skills
MODIFY COLUMN requires_confirmation BOOLEAN NOT NULL DEFAULT FALSE;

-- server_ledgers.name
SET @sql = (
  SELECT IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = @db AND table_name = 'server_ledgers' AND column_name = 'name') > 0,
    'SELECT 1',
    'ALTER TABLE server_ledgers ADD COLUMN name VARCHAR(255)'
  )
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

UPDATE server_ledgers
SET name = ip
WHERE name IS NULL OR TRIM(name) = '';

ALTER TABLE server_ledgers
MODIFY COLUMN name VARCHAR(255) NOT NULL;

SET @idx_exists = (
  SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema = @db
    AND table_name = 'server_ledgers'
    AND index_name = 'uq_server_ledgers_user_name'
);
SET @sql = IF(@idx_exists = 0,
  'CREATE UNIQUE INDEX uq_server_ledgers_user_name ON server_ledgers (user_id, name)',
  'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- llm_http_audit_logs (agent-core LLM 原始 HTTP 审计经本网关落库)
SET @tbl = (
  SELECT COUNT(*) FROM information_schema.tables
  WHERE table_schema = @db AND table_name = 'llm_http_audit_logs'
);
SET @sql = IF(@tbl = 0,
  'CREATE TABLE llm_http_audit_logs (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id VARCHAR(128) NULL,
    session_id VARCHAR(128) NULL,
    correlation_id VARCHAR(64) NOT NULL,
    direction VARCHAR(32) NOT NULL,
    recorded_at DATETIME(6) NOT NULL,
    payload_json LONGTEXT NOT NULL,
    PRIMARY KEY (id),
    KEY idx_llm_http_audit_user_recorded (user_id, recorded_at)
  )',
  'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- gateway_outbound_audit_logs（Skill 对外 HTTP/SSH 统一审计，含 agent-core 入站原始报文）
SET @tbl_gw = (
  SELECT COUNT(*) FROM information_schema.tables
  WHERE table_schema = @db AND table_name = 'gateway_outbound_audit_logs'
);
SET @sql = IF(@tbl_gw = 0,
  'CREATE TABLE gateway_outbound_audit_logs (
    id BIGINT NOT NULL AUTO_INCREMENT,
    correlation_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(128) NULL,
    recorded_at DATETIME(6) NOT NULL,
    outbound_kind VARCHAR(16) NOT NULL,
    status VARCHAR(32) NOT NULL,
    error_message TEXT NULL,
    destination TEXT NOT NULL,
    http_method VARCHAR(16) NULL,
    origin_incomplete BOOLEAN NOT NULL DEFAULT FALSE,
    origin_headers_json LONGTEXT NULL,
    origin_body LONGBLOB NULL,
    origin_truncated BOOLEAN NOT NULL DEFAULT FALSE,
    origin_sha256 CHAR(64) NULL,
    outbound_headers_json LONGTEXT NULL,
    outbound_body LONGBLOB NULL,
    outbound_truncated BOOLEAN NOT NULL DEFAULT FALSE,
    outbound_sha256 CHAR(64) NULL,
    ssh_command TEXT NULL,
    skill_context VARCHAR(256) NULL,
    PRIMARY KEY (id),
    KEY idx_gw_out_audit_corr (correlation_id),
    KEY idx_gw_out_audit_user_time (user_id, recorded_at)
  )',
  'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- Hibernate 常把 @Lob byte[] 建成 BLOB(64KB)；app.gateway-audit.max-payload-bytes 默认可到 1MB，需 LONGBLOB。
SET @c_ob_resp = (
  SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = @db AND table_name = 'gateway_outbound_audit_logs' AND column_name = 'outbound_response_body'
);
SET @sql = IF(@c_ob_resp > 0,
  'ALTER TABLE gateway_outbound_audit_logs MODIFY COLUMN outbound_response_body LONGBLOB NULL',
  'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @c_origin = (
  SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = @db AND table_name = 'gateway_outbound_audit_logs' AND column_name = 'origin_body'
);
SET @sql = IF(@c_origin > 0,
  'ALTER TABLE gateway_outbound_audit_logs MODIFY COLUMN origin_body LONGBLOB NULL',
  'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @c_ob = (
  SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = @db AND table_name = 'gateway_outbound_audit_logs' AND column_name = 'outbound_body'
);
SET @sql = IF(@c_ob > 0,
  'ALTER TABLE gateway_outbound_audit_logs MODIFY COLUMN outbound_body LONGBLOB NULL',
  'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 已废弃表：若库中仍存在则删除（agent_core_invocation_audit_logs、user_skill_invocation_logs）
DROP TABLE IF EXISTS user_skill_invocation_logs;
DROP TABLE IF EXISTS agent_core_invocation_audit_logs;

-- skills.schema_properties（持久化计算的 schema 属性，供 Agent 列表接口直接使用）
SET @sql = (
  SELECT IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = @db AND table_name = 'skills' AND column_name = 'schema_properties') > 0,
    'SELECT 1',
    'ALTER TABLE skills ADD COLUMN schema_properties TEXT'
  )
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
