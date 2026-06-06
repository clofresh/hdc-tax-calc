-- docker/postgres/init/02-create-test-db.sql
-- Creates a parallel test database alongside `hdc`. Backend integration
-- tests (application-test.properties) connect here, isolated from dev data.
CREATE DATABASE hdc_test OWNER hdc;

\c hdc_test

CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS vector;

CREATE SCHEMA IF NOT EXISTS user_schema;
CREATE SCHEMA IF NOT EXISTS tax_benefits;

GRANT ALL ON SCHEMA user_schema, tax_benefits TO hdc;
