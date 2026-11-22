-- Schema created on PostgreSQL by the backend before applications, Ktor 3 and 64-bit view ids
-- (captured from commit 7886c78, Exposed 0.41). Used to test the startup migrations.
CREATE TABLE users (
    id SERIAL PRIMARY KEY,
    email VARCHAR(128) NOT NULL,
    display_name VARCHAR(256) NOT NULL,
    password_hash VARCHAR(64) NOT NULL
);
ALTER TABLE users ADD CONSTRAINT users_email_unique UNIQUE (email);

CREATE TABLE pages (
    id uuid PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    owner INT NULL,
    CONSTRAINT fk_pages_owner__id FOREIGN KEY (owner) REFERENCES users(id) ON DELETE RESTRICT ON UPDATE RESTRICT
);

CREATE TABLE pagesviews (
    id SERIAL PRIMARY KEY,
    page uuid NOT NULL,
    "sessionId" uuid NOT NULL,
    "timestamp" BIGINT NOT NULL,
    CONSTRAINT fk_pagesviews_page__id FOREIGN KEY (page) REFERENCES pages(id) ON DELETE CASCADE ON UPDATE RESTRICT
);
