# Docker Desktop Kubernetes: Messenger

This local deployment runs one frontend pod, two backend pods and one persistent
Redis pod. PostgreSQL stays on Windows and keeps your existing accounts and
messages. Backend startup runs Flyway migrations against that database.

```text
Browser http://localhost:8088
  -> kubectl port-forward
  -> frontend Service -> frontend pod (React + Nginx)
  -> backend Service -> 2 backend pods
                         -> PostgreSQL on Windows
                         -> Redis Service -> Redis pod + persistent volume
```

The frontend uses a Kubernetes-specific Nginx ConfigMap mounted over the image's
default configuration. Existing Docker Compose configurations are unchanged.
REST and WebSocket requests use the same origin. No sticky sessions are configured;
both backends use shared Redis sessions and Redis Pub/Sub. A Service balances
connections, so requests over a reused connection need not alternate between pods.

## 1. Start the cluster

In Docker Desktop, use Linux containers and enable/create Kubernetes. Keep an
existing healthy cluster; resetting it is unnecessary. Open a fresh PowerShell
terminal after enabling Kubernetes so Docker and kubectl are on PATH.

Run from the repository root:

```powershell
kubectl config use-context docker-desktop
kubectl get nodes
kubectl get storageclass
```

Nodes must be Ready. Redis needs a default StorageClass with dynamic provisioning.
Allocate enough resources for two Java pods (each requests 512Mi), Redis, the
frontend, and Kubernetes itself.

## 2. Configure PostgreSQL and the Secret

The existing Windows PostgreSQL database `messenger` must accept TCP connections
from Docker Desktop. The template uses `host.docker.internal`, which refers to
the Windows host, rather than `localhost`, which would refer to the backend pod.
If connections fail, inspect PostgreSQL's listening addresses, `pg_hba.conf`, and
Windows Firewall. Allow the actual Docker Desktop client network using password
authentication; do not add a broad `trust` rule. Existing users must already be
present: the Kubernetes profile does not create bootstrap users.

```powershell
if (-not (Test-Path k8s/.env)) { Copy-Item k8s/.env.example k8s/.env }
notepad k8s/.env
kubectl apply -f k8s/namespace.yaml
kubectl -n messenger create secret generic messenger-secrets --from-env-file=k8s/.env
```

Replace both DB credential placeholders before creating the Secret. Keep
`REDIS_URL=redis://messenger-redis:6379`. The real `.env` is Git-ignored.
For an existing Secret, update it without printing its contents:

```powershell
kubectl -n messenger create secret generic messenger-secrets --from-env-file=k8s/.env --dry-run=client -o yaml | kubectl apply -f -
kubectl -n messenger rollout restart deployment/messenger-backend
```

### File storage

Uploaded files live on the `messenger-files` PersistentVolumeClaim
(`file-storage.yaml`), mounted at `/var/lib/messenger/files` in every backend
pod. Docker Desktop provisions it automatically. On your own server, create a
PersistentVolume for the physical storage first: `file-storage.yaml` has NFS
and local-disk examples. Use NFS (`ReadWriteMany`) when backend pods can run on
more than one node. Files expire after 60 days (`FILE_RETENTION`); the per-file
limit is 50 MB (`FILE_MAX_SIZE`). Back up this volume together with PostgreSQL.

## 3. Build images

```powershell
docker build -t messenger-backend:k8s-v1 ./backend/demo
docker build -t messenger-frontend:k8s-v1 ./frontend
```

Backend image builds run Maven verification. Proceed only when both builds pass.
The manifests use `IfNotPresent` so locally available images are usable. Docker
Desktop image availability depends on its provisioner and image store. If the
cluster cannot see the built images, use a registry accessible from the cluster:

```powershell
$taskDockerHubUser = Read-Host "Docker Hub username"
docker login
docker tag messenger-backend:k8s-v1 "${taskDockerHubUser}/messenger-backend:k8s-v1"
docker tag messenger-frontend:k8s-v1 "${taskDockerHubUser}/messenger-frontend:k8s-v1"
docker push "${taskDockerHubUser}/messenger-backend:k8s-v1"
docker push "${taskDockerHubUser}/messenger-frontend:k8s-v1"
```

Publishing images sends them to your registry; select the intended repository
visibility. Change the two `image:` values in `deployment.yaml` to the registry tags
before applying. Both Deployments now reference `ghcr-pull`; provision that Secret
using the full guide. `docker login` alone does not authenticate Kubernetes pulls.

## 4. Deploy and wait

```powershell
kubectl apply -k k8s
kubectl -n messenger rollout status statefulset/messenger-redis --timeout=180s
kubectl -n messenger rollout status deployment/messenger-backend --timeout=300s
kubectl -n messenger rollout status deployment/messenger-frontend --timeout=180s
kubectl -n messenger get deployments,pods,svc,pvc
```

Expect frontend READY `1/1`, backend READY `2/2`, four ready pods in total, and
a Bound Redis PVC. Kubernetes has no Compose-style `depends_on`; initial backend
startup may retry while Redis starts. Readiness removes unhealthy backends from
Service endpoints. Liveness checks application health, without restarting every
backend just because PostgreSQL or Redis temporarily fails.

## 5. Open the app

```powershell
kubectl -n messenger port-forward service/messenger-frontend 8088:80
```

Leave this terminal open. Browse exactly `http://localhost:8088`; this matches
the configured WebSocket origin. Use existing accounts. HTTP Secure cookies are
disabled only in this local configuration. Ctrl+C stops port forwarding, while
the workloads keep running.

In a second terminal:

```powershell
curl.exe --fail http://localhost:8088/healthz
curl.exe --fail http://localhost:8088/actuator/health/readiness
kubectl -n messenger get endpointslices -l kubernetes.io/service-name=messenger-backend
kubectl -n messenger logs -l app=messenger-backend --prefix --tail=100
```

The backend Service must have two ready endpoints. Open normal and private
browser sessions, sign in as different users, and check messages and typing.
In browser DevTools, `/api/v1/chat/events` should return HTTP 101 and receive
heartbeat frames. Browser testing alone does not prove each client landed on a
different backend; correlate connection logs or use the existing production
integration tests to verify cross-instance behavior.

To test recovery, select one backend pod from `kubectl get pods` and replace the
placeholder below. This deliberately interrupts that pod's connections:

```powershell
kubectl -n messenger delete pod <one-backend-pod-name>
kubectl -n messenger get pods -w
```

Wait for the replacement to become Ready and verify reconnection, saved history,
new sends, and no duplicated messages. One frontend replica cannot provide
frontend availability during its restart. Two backend pods on one node also
do not protect against loss of that node.

## Troubleshooting and updates

```powershell
kubectl -n messenger get events --sort-by=.metadata.creationTimestamp
kubectl -n messenger describe pod <pod-name>
kubectl -n messenger logs <pod-name> --previous
```

- ImagePullBackOff: check tag, registry access, pull credentials or local image availability.
- Backend CrashLoopBackOff: inspect DB connectivity, credentials, migration errors and Redis connectivity.
- Redis Pending: check the PVC and default StorageClass.
- Nginx 502: check backend readiness and the backend Service endpoints.
- WebSocket 403: use the exact configured browser origin.

For code updates, build a new immutable tag such as `k8s-v2`, make it available
to the cluster, edit the matching `image:` value, and run `kubectl apply -k k8s`.
ConfigMap changes in this Kustomize setup generate a new name and trigger the
dependent Deployment rollout. Secret changes require a backend rollout restart.

Before an external deployment, add HTTPS ingress/gateway routing, change
FRONTEND_ORIGIN to the public HTTPS origin, restore Secure cookies, use
authenticated Redis, and configure trusted proxy addresses for client IP handling.
This local Redis instance persists data but is not a high-availability service.

## References and validation

- [Docker Desktop Kubernetes](https://docs.docker.com/desktop/use-desktop/kubernetes/)
- [Docker Desktop host networking](https://docs.docker.com/desktop/features/networking/networking-how-tos/)
- [Kubernetes Services](https://kubernetes.io/docs/concepts/services-networking/service/)
- [Kubernetes probes](https://kubernetes.io/docs/tasks/configure-pod-container/configure-liveness-readiness-startup-probes/)
- [NGINX WebSocket proxying](https://nginx.org/en/docs/http/websocket.html)

YAML syntax and the Nginx Service upstream, WebSocket directives and SPA fallback
were checked locally. The manifests still require validation against your cluster.
Docker was unavailable on PATH and the installed kubectl executable could not
run because execution was denied in the authoring environment. Image builds,
Kustomize rendering and cluster rollouts have not been verified here.
