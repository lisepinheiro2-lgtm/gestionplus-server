CREATE TABLE user_accounts (
    user_id UUID PRIMARY KEY,
    user_name VARCHAR(255) NOT NULL UNIQUE,
    active BOOLEAN NOT NULL
);
CREATE TABLE user_account_roles (
    user_id UUID NOT NULL,
    role VARCHAR(50) NOT NULL,

    PRIMARY KEY (user_id, role),

    FOREIGN KEY (user_id)
        REFERENCES user_accounts(user_id)
);
CREATE TABLE user_credentials (
    user_id UUID PRIMARY KEY,
    password_hash VARCHAR(512) NOT NULL,

    FOREIGN KEY (user_id)
        REFERENCES user_accounts(user_id)
);