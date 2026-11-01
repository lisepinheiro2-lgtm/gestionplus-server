CREATE ROLE audit_migration_test
    LOGIN PASSWORD 'migration_test_only';

CREATE ROLE audit_application_test
    LOGIN PASSWORD 'application_test_only';

GRANT USAGE, CREATE ON SCHEMA public
    TO audit_migration_test;

GRANT USAGE ON SCHEMA public
    TO audit_application_test;