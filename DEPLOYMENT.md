# Let's Talk deployment configuration

Existing `messenger` database, volume and Kubernetes resource names are retained
so upgrades keep using existing data.

| Setup                                     | Copy template to                               | Compose file             |
| ----------------------------------------- | ---------------------------------------------- | ------------------------ |
| Two local backends and Windows PostgreSQL | `.env.example` to `.env`                       | `compose.yml`            |
| Development with container PostgreSQL     | `.env.dev.example` to `.env`                   | `compose.dev.yaml`       |
| One backend and Windows PostgreSQL        | `.env.windows.example` to `.env.windows`       | `compose.windows.yml`    |
| Production                                | `.env.production.example` to `.env.production` | `compose.production.yml` |
| Local Kubernetes                          | `k8s/.env.example` to `k8s/.env`               | Follow `k8s/README.md`   |

Pass `--env-file` when using a file other than `.env`. Replace credential
placeholders before startup. Editing database credentials does not change a
password in an existing PostgreSQL volume. Never commit real environment files.

For the first administrator, supply `BOOTSTRAP_USERNAME`, `BOOTSTRAP_PASSWORD`
(at least 12 characters) and `BOOTSTRAP_EMAIL`. Remove the bootstrap password
after provisioning. Existing passwords are preserved. Local profiles use servlet
sessions without Redis; production and the two-backend cluster use shared Redis.

## Nginx and HTTPS

Both Nginx files use the same template. The container substitutes only
`BACKEND_SERVERS`, `PUBLIC_SCHEME` and `UPLOAD_MAX_BODY_SIZE`. Kubernetes supplies
its Service address through the ConfigMap; local Compose supplies both backends.

Production Compose binds `127.0.0.1:8080` by default. Put a trusted HTTPS proxy
in front of it, set `PUBLIC_ORIGIN` to the exact browser origin, and use
`PUBLIC_SCHEME=https`. If an external ingress needs access, change
`PUBLIC_BIND_ADDRESS` and restrict the port to that ingress. Production session
cookies remain Secure. CI uses HTTP for isolated startup checks.

Nginx replaces incoming forwarding headers. Behind an ingress, configure Nginx
real-IP handling to trust only that ingress; otherwise clients share its address
for rate limiting. Set backend `TRUSTED_PROXY_PATTERN` to match its frontend proxy
peers only. Adjust the Kubernetes example to the actual pod network.

The Kubernetes base is for local port-forward testing. External deployments need
HTTPS ingress, the public `FRONTEND_ORIGIN`, `PUBLIC_SCHEME=https`,
`SERVER_SERVLET_SESSION_COOKIE_SECURE=true`, authenticated Redis and suitable
shared file storage.

## Uploads and verification

Keep `UPLOAD_MAX_BODY_SIZE` at least as large as `FILE_MAX_REQUEST_SIZE`, with
multipart overhead above `FILE_VIDEO_MAX_SIZE`. Defaults are 201m at Nginx,
201MB per request, 200MB per video, 5MB per other file, 1GB per user daily and
60 days retention. All replicas must mount the same file storage.

Configure `MAIL_*` settings when email delivery is used. Configure browser push
only when all `PUSH_*` values are available. Never put secrets in `VITE_*` values.

```powershell
docker compose --env-file .env.production -f compose.production.yml config --quiet
docker compose --env-file .env.production -f compose.production.yml up --build -d --wait
docker compose --env-file .env.production -f compose.production.yml exec -T frontend nginx -t
kubectl kustomize k8s
```

CI starts the production stack, validates Nginx, checks frontend/API readiness
and runs vulnerability scans. Validate backups, restores and monitoring before
launch. The previously identified public password-reset ownership flaw remains
a separate application launch blocker.
