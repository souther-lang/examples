-- The RealWorld schema. Nothing here is a domain rule: the invariants live in the .sou modules, and
-- a column width is only what the store will hold. The uniqueness of an email is the exception — the
-- domain states it too (registerUser answers EmailTaken), and the constraint is what stops two
-- concurrent registrations from both passing that check.

CREATE TABLE users (
    username      VARCHAR(40)  NOT NULL PRIMARY KEY,
    email         VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    bio           VARCHAR(1000),
    image         VARCHAR(500)
);

-- Who follows whom. The primary key is what makes following twice a no-op rather than a second row.
CREATE TABLE follows (
    follower VARCHAR(40) NOT NULL,
    followee VARCHAR(40) NOT NULL,
    PRIMARY KEY (follower, followee)
);
