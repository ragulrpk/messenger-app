# Production operations

## Required services

- PostgreSQL stores accounts, conversations, memberships, and messages.
- Redis stores shared HTTP sessions and distributed login rate-limit counters.
- The frontend Nginx container terminates the app's HTTP traffic and proxies `/api` to the backend. Put a managed TLS load balancer or reverse proxy in front of it.

Copy `.env.production.example` to `.env.production`, replace every example secret, and keep that file outside source control. Start a single-host deployment with:

```sh
docker compose --env-file .env.production -f compose.production.yml up -d --build
```

Production secrets should come from the deployment platform's secret manager. Do not pass application secrets in image build arguments.

## Health and metrics

- Liveness: `/actuator/health/liveness`
- Readiness: `/actuator/health/readiness`
- Prometheus metrics: `/actuator/prometheus`
- Frontend health: `/healthz`

Restrict the actuator endpoints to the internal monitoring network at the ingress. Every HTTP response includes `X-Request-ID`; application logs include the same `requestId` field when a request is active.

## Backup and restore

Use managed PostgreSQL point-in-time recovery where available. Otherwise, take encrypted daily `pg_dump` backups and retain at least one weekly backup separately from the deployment host. Redis contains recoverable session and rate-limit data, so PostgreSQL is the authoritative backup target.

Test restoration at least monthly:

1. Restore the latest backup into an isolated PostgreSQL instance.
2. Start the backend against the restored database with a disposable Redis instance.
3. Verify Flyway validation, authentication, conversation listing, history, and sending.
4. Record recovery time and the newest restored message timestamp.

## Deployment

1. Run CI and build immutable container images.
2. Back up PostgreSQL before a schema migration.
3. Deploy one backend instance and wait for readiness.
4. Roll the remaining instances without taking all instances down together.
5. Verify login, session restoration, send retry safety, and Prometheus alerts.

Graceful shutdown gives active requests up to 30 seconds to finish. Shared Redis sessions allow requests to move between backend instances.

## Alerts

Alert on readiness failure, elevated 5xx responses, login-rate-limit failures, Redis or PostgreSQL connection failures, high request latency, exhausted database connections, disk pressure, and backup failure. Never log credentials, cookies, CSRF tokens, authorization values, or message bodies.
