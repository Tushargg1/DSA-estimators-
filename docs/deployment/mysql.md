# Deploying Aiven MySQL 8

Use a fresh Aiven MySQL 8 service for the production DSA Progress Tracker. The
backend uses MySQL Connector/J and Flyway's MySQL module; it does not support the
former PostgreSQL schema.

## Connection settings

Create the service and an empty database, then map Aiven's connection details to
hosting-platform secrets. Use placeholders in documentation and source:

```text
DB_URL=jdbc:mysql://<host>:<port>/<database>?sslMode=REQUIRED&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true
DB_USERNAME=<username>
DB_PASSWORD=<password>
```

`sslMode=REQUIRED` encrypts traffic. As an optional production-hardening path,
install the Aiven CA certificate into the application's trust configuration and
switch to Connector/J's CA and hostname verification mode. Validate certificate
rotation and trust-store handling in staging first.

Do not place credentials in Git, Docker layers, screenshots copied into files,
or application logs.

## Migrations and connection limits

Flyway applies `V1__init_schema.sql` automatically on first backend startup. Use
a fresh empty database and do not add a PostgreSQL-to-MySQL V2 migration.
Hibernate uses `ddl-auto=validate` after Flyway completes.

Aiven plans can impose small connection limits. The backend defaults to a
conservative Hikari pool and permits tuning without a rebuild:

```text
DB_POOL_MAX=5
DB_POOL_MIN_IDLE=1
```

Set values according to the service plan and number of backend instances.

## Local development

Root `docker-compose.yml` runs the pinned MySQL 8 image on port 3306. The default
JDBC URL in `application.yml` points to it and forces the JDBC session timezone
to UTC. Local placeholder passwords are development-only.
