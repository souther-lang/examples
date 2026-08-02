CREATE TABLE IF NOT EXISTS orders (
    id    VARCHAR(64) PRIMARY KEY,
    total INT         NOT NULL
);

CREATE TABLE IF NOT EXISTS order_lines (
    order_id   VARCHAR(64) NOT NULL,
    sku        VARCHAR(64) NOT NULL,
    qty        INT         NOT NULL,
    unit_price INT         NOT NULL
);

CREATE TABLE IF NOT EXISTS stock (
    sku VARCHAR(64) PRIMARY KEY,
    qty INT         NOT NULL
);

-- Every rate ever in force for a tax category, and the day it started. Not one row per category: a
-- rate is revised by law and the old figure stays true of the days it covered, so what is stored is
-- the history and the domain reads it at the date of the transaction. NUMERIC, because a rate is not
-- an integer — it is the one column in this schema that arrives in the domain as a Decimal.
CREATE TABLE IF NOT EXISTS tax_rates (
    category       VARCHAR(32)  NOT NULL,
    effective_from DATE         NOT NULL,
    rate           NUMERIC(4,3) NOT NULL,
    PRIMARY KEY (category, effective_from)
);

-- Japan's consumption tax as it actually went: three per cent from 1989, five from 1997, eight from
-- 2014, ten from October 2019. The reduced rate was introduced with that last revision, so its
-- history starts there and a line dated earlier has no reduced rate to read.
MERGE INTO tax_rates (category, effective_from, rate) KEY (category, effective_from)
    VALUES ('StandardRate', DATE '1989-04-01', 0.030);
MERGE INTO tax_rates (category, effective_from, rate) KEY (category, effective_from)
    VALUES ('StandardRate', DATE '1997-04-01', 0.050);
MERGE INTO tax_rates (category, effective_from, rate) KEY (category, effective_from)
    VALUES ('StandardRate', DATE '2014-04-01', 0.080);
MERGE INTO tax_rates (category, effective_from, rate) KEY (category, effective_from)
    VALUES ('StandardRate', DATE '2019-10-01', 0.100);
MERGE INTO tax_rates (category, effective_from, rate) KEY (category, effective_from)
    VALUES ('ReducedRate',  DATE '2019-10-01', 0.080);
