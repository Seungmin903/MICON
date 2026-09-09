CREATE TABLE password_reset_request (
    password_reset_request_id BIGINT NOT NULL AUTO_INCREMENT,
    request_id VARCHAR(36) NOT NULL,
    user_id BIGINT NOT NULL,
    code_hash VARCHAR(255) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    expires_at DATETIME(6) NOT NULL,
    verified_at DATETIME(6) NULL,
    reset_token_hash VARCHAR(64) NULL,
    reset_token_expires_at DATETIME(6) NULL,
    used_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_password_reset_request PRIMARY KEY (password_reset_request_id),
    CONSTRAINT fk_password_reset_request_user FOREIGN KEY (user_id) REFERENCES users (user_id),
    CONSTRAINT uk_password_reset_request_id UNIQUE (request_id),
    CONSTRAINT uk_password_reset_token_hash UNIQUE (reset_token_hash),
    CONSTRAINT ck_password_reset_attempt_count CHECK (attempt_count >= 0)
);

CREATE INDEX idx_password_reset_request_user_id ON password_reset_request (user_id);
CREATE INDEX idx_password_reset_request_expires_at ON password_reset_request (expires_at);
