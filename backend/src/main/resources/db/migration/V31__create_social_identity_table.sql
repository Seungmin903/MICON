CREATE TABLE social_identity (
    social_identity_id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    provider VARCHAR(20) NOT NULL,
    provider_subject VARCHAR(255) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_social_identity PRIMARY KEY (social_identity_id),
    CONSTRAINT fk_social_identity_user FOREIGN KEY (user_id) REFERENCES users (user_id) ON DELETE CASCADE,
    CONSTRAINT uk_social_identity_provider_subject UNIQUE (provider, provider_subject)
);
