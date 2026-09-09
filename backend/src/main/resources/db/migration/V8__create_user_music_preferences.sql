CREATE TABLE user_singer_preference (
    user_id BIGINT NOT NULL,
    singer_id BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_user_singer_preference PRIMARY KEY (user_id, singer_id),
    CONSTRAINT fk_user_singer_preference_user
        FOREIGN KEY (user_id) REFERENCES users (user_id) ON DELETE CASCADE,
    CONSTRAINT fk_user_singer_preference_singer
        FOREIGN KEY (singer_id) REFERENCES singer (singer_id) ON DELETE CASCADE
);

CREATE TABLE user_genre_preference (
    user_id BIGINT NOT NULL,
    genre_id BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_user_genre_preference PRIMARY KEY (user_id, genre_id),
    CONSTRAINT fk_user_genre_preference_user
        FOREIGN KEY (user_id) REFERENCES users (user_id) ON DELETE CASCADE,
    CONSTRAINT fk_user_genre_preference_genre
        FOREIGN KEY (genre_id) REFERENCES genre (genre_id) ON DELETE CASCADE
);
