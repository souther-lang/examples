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

-- The slug is the key because it is how an article is addressed, and it does not move when the title
-- does. `body` is its own column so the listing queries can leave it unread — which is the whole
-- reason ArticleSummary exists beside Article.
CREATE TABLE articles (
    slug        VARCHAR(255)  NOT NULL PRIMARY KEY,
    title       VARCHAR(255)  NOT NULL,
    description VARCHAR(1000) NOT NULL,
    body        CLOB          NOT NULL,
    author      VARCHAR(40)   NOT NULL,
    created_at  TIMESTAMP     NOT NULL,
    updated_at  TIMESTAMP     NOT NULL
);

CREATE TABLE article_tags (
    slug VARCHAR(255) NOT NULL,
    tag  VARCHAR(60)  NOT NULL,
    PRIMARY KEY (slug, tag)
);

-- Favoriting twice is favoriting once, for the same reason following twice is.
CREATE TABLE favorites (
    username VARCHAR(40)  NOT NULL,
    slug     VARCHAR(255) NOT NULL,
    PRIMARY KEY (username, slug)
);

-- The id is the store's to hand out, which is why CommentId's invariant says only that one exists.
CREATE TABLE comments (
    id         INT AUTO_INCREMENT PRIMARY KEY,
    slug       VARCHAR(255) NOT NULL,
    body       CLOB         NOT NULL,
    author     VARCHAR(40)  NOT NULL,
    created_at TIMESTAMP    NOT NULL,
    updated_at TIMESTAMP    NOT NULL
);
