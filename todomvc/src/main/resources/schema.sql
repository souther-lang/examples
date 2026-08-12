-- The shape todomvc.sou's injected behaviors read and write. One row per Todo, mirroring the
-- generated type field for field: id is the primary key the store hands out (TodoId's invariant,
-- value >= 1, holds because H2 identities start at 1), title is never empty (Title's invariant),
-- completed defaults to not done, todo_order is todo-backend's client-settable `order` field
-- (named todo_order, not order, because ORDER is a reserved word in SQL). No other table: there is no
-- relationship in this domain to model. `url` is not a column — it is minted by the boundary from
-- the id, not stored.
CREATE TABLE IF NOT EXISTS todos (
    id         INT AUTO_INCREMENT PRIMARY KEY,
    title      VARCHAR(255) NOT NULL,
    completed  BOOLEAN      NOT NULL DEFAULT FALSE,
    todo_order INT          NOT NULL DEFAULT 0
);
