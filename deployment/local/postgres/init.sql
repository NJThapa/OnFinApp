-- Runs once, on first start of an empty data volume.
-- One database per API so each stack owns its own schema and migrations.
CREATE DATABASE app_java;
CREATE DATABASE app_dotnet;
