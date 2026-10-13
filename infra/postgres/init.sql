-- Local development only. One database, one schema and one role per service.
CREATE ROLE ledger LOGIN PASSWORD 'ledger';
CREATE ROLE gateway LOGIN PASSWORD 'gateway';
CREATE ROLE control LOGIN PASSWORD 'control';
CREATE ROLE reporting LOGIN PASSWORD 'reporting';

CREATE SCHEMA ledger AUTHORIZATION ledger;
CREATE SCHEMA gateway AUTHORIZATION gateway;
CREATE SCHEMA control AUTHORIZATION control;
CREATE SCHEMA reporting AUTHORIZATION reporting;

REVOKE ALL ON SCHEMA public FROM PUBLIC;
