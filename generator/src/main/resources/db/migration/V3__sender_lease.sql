-- Which instance of the generator sends. One row at most; an instance takes it when it is free or has expired, and the
-- holder renews it. Times are the database's own, so the instances' clocks do not matter.
CREATE TABLE sender_lease (
    only_row   boolean     PRIMARY KEY DEFAULT true CHECK (only_row),
    owner      text        NOT NULL,
    expires_at timestamptz NOT NULL
);
