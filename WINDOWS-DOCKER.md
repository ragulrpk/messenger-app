# Docker deployment with PostgreSQL on Windows

This deployment runs the production frontend image and backend image in Docker. PostgreSQL remains installed on Windows and is reached from the backend as `host.docker.internal:5432`. The backend uses its explicit `local` profile, so sessions stay in the backend process and Redis is not required for this single-backend local deployment.

## Prerequisites

1. Start Docker Desktop.
2. Start the Windows PostgreSQL service and confirm that it listens on TCP port 5432.
3. PostgreSQL must allow the Docker Desktop network through `listen_addresses`, `pg_hba.conf`, and Windows Firewall. Restrict the rule to the Docker network rather than opening port 5432 publicly.
4. Create the `messenger` database if it does not exist.

## Configure and start

Copy `.env.windows.example` to `.env.windows`, enter the Windows PostgreSQL credentials, and optionally set a local bootstrap login. Then run:

```powershell
docker compose --env-file .env.windows -f compose.windows.yml up -d --build
```

Open `http://localhost:8080`. Nginx serves the frontend and proxies `/api` to the backend, so authentication cookies remain same-site.

## Verify and stop

```powershell
docker compose --env-file .env.windows -f compose.windows.yml ps
Invoke-WebRequest http://localhost:8080/healthz
Invoke-WebRequest http://localhost:8080/api/v1/users/csrf -SessionVariable session
docker compose --env-file .env.windows -f compose.windows.yml down
```

`down` removes only the frontend/backend containers and network. It does not alter the Windows PostgreSQL service or database.
