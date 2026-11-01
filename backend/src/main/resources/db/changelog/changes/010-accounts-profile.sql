--liquibase formatted sql
--changeset calorica:010-accounts-profile
CREATE TABLE calorica.accounts (
    id uuid PRIMARY KEY,
    email varchar(254) NOT NULL UNIQUE,
    password_hash text NOT NULL,
    role varchar(5) NOT NULL DEFAULT 'USER' CHECK (role IN ('USER', 'ADMIN')),
    created_at timestamptz NOT NULL
);
CREATE TABLE calorica.sessions (
    id uuid PRIMARY KEY,
    account_id uuid NOT NULL REFERENCES calorica.accounts(id),
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    CHECK (expires_at > created_at)
);
CREATE INDEX sessions_account ON calorica.sessions(account_id);
CREATE TABLE calorica.refresh_tokens (
    token_hash varchar(64) PRIMARY KEY CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    session_id uuid NOT NULL REFERENCES calorica.sessions(id),
    created_at timestamptz NOT NULL,
    used_at timestamptz
);
CREATE INDEX refresh_tokens_session ON calorica.refresh_tokens(session_id);
CREATE TABLE calorica.profiles (
    account_id uuid PRIMARY KEY REFERENCES calorica.accounts(id),
    calorie_target numeric(7,2) CHECK (calorie_target >= 0),
    protein_target numeric(7,2) CHECK (protein_target >= 0),
    fat_target numeric(7,2) CHECK (fat_target >= 0),
    carbs_target numeric(7,2) CHECK (carbs_target >= 0)
);
-- Used refresh hashes are intentionally retained: replay must revoke the whole session.
-- No destructive rollback; older foundation applications remain schema-compatible.
