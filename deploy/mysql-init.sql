-- Run as a database administrator before configuring your application account.
CREATE DATABASE IF NOT EXISTS stocks_monitor_platform CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
-- Create a dedicated user with a password from your secret manager, then grant
-- SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES, DROP
-- on stocks_monitor_platform.*. Flyway creates and maintains the schema.
