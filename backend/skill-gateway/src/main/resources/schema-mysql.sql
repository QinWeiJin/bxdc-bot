CREATE TABLE IF NOT EXISTS users (
    id VARCHAR(6) PRIMARY KEY,
    nickname VARCHAR(255),
    avatar VARCHAR(255),
    created_at DATETIME,
    llm_api_base VARCHAR(512),
    llm_model_name VARCHAR(128),
    llm_api_key VARCHAR(2048)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS skills (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(255) NOT NULL UNIQUE,
    description TEXT,
    type VARCHAR(255) NOT NULL,
    configuration TEXT,
    execution_mode VARCHAR(255) DEFAULT 'CONFIG',
    enabled TINYINT(1) DEFAULT 1,
    requires_confirmation TINYINT(1) NOT NULL DEFAULT 0,
    visibility VARCHAR(16) NOT NULL DEFAULT 'PUBLIC',
    avatar VARCHAR(32),
    created_by VARCHAR(128),
    created_at DATETIME,
    updated_at DATETIME,
    schema_properties TEXT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS audit_logs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    agent_id VARCHAR(255),
    skill_name VARCHAR(255),
    command_or_url VARCHAR(255),
    params TEXT,
    status VARCHAR(255),
    timestamp DATETIME
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS server_ledgers (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id VARCHAR(255) NOT NULL,
    name VARCHAR(255) NOT NULL,
    host VARCHAR(255),
    port INT,
    username VARCHAR(255),
    password VARCHAR(255),
    private_key_path VARCHAR(255),
    created_at DATETIME,
    updated_at DATETIME,
    UNIQUE KEY uk_server_ledgers_user_name (user_id, name),
    UNIQUE KEY uk_server_ledgers_user_host (user_id, host)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS system_skills (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tool_name VARCHAR(128) NOT NULL UNIQUE,
    description TEXT,
    kind VARCHAR(32) NOT NULL,
    configuration TEXT,
    enabled TINYINT(1) NOT NULL DEFAULT 1,
    schema_version INT DEFAULT 1,
    created_at DATETIME,
    updated_at DATETIME
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS gateway_outbound_audit_logs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    correlation_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(128),
    recorded_at DATETIME(3) NOT NULL,
    outbound_kind VARCHAR(16) NOT NULL,
    status VARCHAR(32) NOT NULL,
    error_message TEXT,
    destination TEXT NOT NULL,
    http_method VARCHAR(16),
    origin_incomplete TINYINT(1) NOT NULL,
    origin_headers_json LONGTEXT,
    origin_body LONGBLOB,
    origin_truncated TINYINT(1) NOT NULL,
    origin_sha256 VARCHAR(64),
    outbound_headers_json LONGTEXT,
    outbound_body LONGBLOB,
    outbound_truncated TINYINT(1) NOT NULL,
    outbound_sha256 VARCHAR(64),
    ssh_command TEXT,
    skill_context VARCHAR(256),
    skill_id BIGINT,
    proxy_request_json LONGTEXT,
    outbound_response_status INT,
    outbound_response_headers_json LONGTEXT,
    outbound_response_body LONGBLOB,
    outbound_response_truncated TINYINT(1) NOT NULL,
    outbound_response_sha256 VARCHAR(64),
    INDEX idx_gw_out_audit_corr (correlation_id),
    INDEX idx_gw_out_audit_user_time (user_id, recorded_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS llm_http_audit_logs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id VARCHAR(128),
    session_id VARCHAR(128),
    correlation_id VARCHAR(64) NOT NULL,
    direction VARCHAR(32) NOT NULL,
    recorded_at DATETIME(3) NOT NULL,
    payload_json LONGTEXT NOT NULL,
    INDEX idx_llm_http_audit_user_recorded (user_id, recorded_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS skill_ssh_invocation_audit_logs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    correlation_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(128),
    skill_id BIGINT,
    recorded_at DATETIME(3) NOT NULL,
    skill_context VARCHAR(256),
    agent_request_json LONGTEXT,
    resolved_host TEXT,
    resolved_port INT,
    executed_command TEXT,
    server_ledger_id BIGINT,
    status VARCHAR(32) NOT NULL,
    error_message TEXT,
    result_body LONGTEXT,
    result_truncated TINYINT(1) NOT NULL,
    INDEX idx_ssh_inv_audit_user_time (user_id, recorded_at),
    INDEX idx_ssh_inv_audit_skill (skill_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS async_tasks (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    skill_id BIGINT,
    user_id VARCHAR(64),
    external_task_id VARCHAR(255),
    poll_endpoint VARCHAR(1024),
    poll_method VARCHAR(16) DEFAULT 'GET',
    poll_interval_seconds INT DEFAULT 5,
    max_wait_seconds INT,
    completion_json_path VARCHAR(255),
    completion_value VARCHAR(64),
    failed_values TEXT COMMENT 'JSON array of failure status values',
    result_json_path VARCHAR(255),
    poll_headers TEXT COMMENT 'JSON, polling request headers',
    initial_response MEDIUMTEXT,
    poll_result MEDIUMTEXT,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    error_message TEXT,
    poll_retry_count INT DEFAULT 0 COMMENT 'consecutive poll failure count; reset on success',
    last_polled_at DATETIME,
    started_at DATETIME,
    completed_at DATETIME,
    created_at DATETIME,
    updated_at DATETIME,
    INDEX idx_async_status (status),
    INDEX idx_async_skill_id (skill_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS skill_text_prompts (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    field_id VARCHAR(64) NOT NULL UNIQUE,
    field_label VARCHAR(128),
    system_prompt TEXT,
    user_prompt_template TEXT,
    created_at DATETIME,
    updated_at DATETIME
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
