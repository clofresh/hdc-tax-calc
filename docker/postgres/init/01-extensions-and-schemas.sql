-- docker/postgres/init/01-extensions-and-schemas.sql
CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS vector;

CREATE SCHEMA IF NOT EXISTS user_schema;
CREATE SCHEMA IF NOT EXISTS tax_benefits;

GRANT ALL ON SCHEMA user_schema, tax_benefits TO hdc;
