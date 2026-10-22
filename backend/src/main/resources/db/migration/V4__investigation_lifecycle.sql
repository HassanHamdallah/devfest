CREATE TABLE investigation_lifecycle (
 challenge_id BIGINT PRIMARY KEY REFERENCES investigation_challenge(id),
 availability VARCHAR(10) NOT NULL CHECK(availability IN ('DRAFT','SCHEDULED','OPEN','CLOSED')),
 observed_at TIMESTAMPTZ NOT NULL
);
