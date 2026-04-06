# Spinx — Unified Config for Stateful Services
## Databases · Redis · Queues · Secret Vaults · Kamal · Kubernetes

This document describes the **target design** for how a single Spinx YAML config
should work across all deployment targets — Kamal (VPS), AWS Fargate, GCP Cloud
Run, Azure Container Apps, and future Kubernetes support — covering stateful
services (databases, caches, queues) and secrets management.

---

## Table of Contents

1. [Core principle — one config, every target](#1-core-principle--one-config-every-target)
2. [The unified config format](#2-the-unified-config-format)
3. [How `setup` works — generated spec + secrets file](#3-how-setup-works--generated-spec--secrets-file)
4. [Auto-wiring — how Spinx places env and secrets per provider](#4-auto-wiring--how-spinx-places-env-and-secrets-per-provider)
5. [Accessories — databases, Redis, queues](#5-accessories--databases-redis-queues)
6. [Kamal (VPS) — accessories run as containers](#6-kamal-vps--accessories-run-as-containers)
7. [Cloud providers — accessories map to managed services](#7-cloud-providers--accessories-map-to-managed-services)
8. [Kubernetes — where everything maps to](#8-kubernetes--where-everything-maps-to)
9. [Secret vaults (proposed)](#9-secret-vaults-proposed)
10. [Recommended evolution path](#10-recommended-evolution-path)

---

## 1. Core principle — one config, every target

**You write the config once.** Spinx reads it and does the right thing on each
platform:

```
myapp.yaml   ──┬──▶  spinx kamal deploy              → Kamal deploy.yml + SSH
               ├──▶  spinx aws-fargate deploy         → ECS task definition
               ├──▶  spinx gcp-cloudrun deploy        → Cloud Run revision
               ├──▶  spinx azure-container-apps deploy→ Container App revision
               └──▶  spinx kubernetes deploy  (future)→ K8s manifests
```

You declare your app, its accessories (database, cache, queue), environment
variables, and secrets **in one file**. Spinx handles the platform-specific
wiring automatically.

---

## 2. The unified config format

```yaml
# myapp.yaml — the same file used for every provider

# ── App ─────────────────────────────────────────────────────────────
serviceName: "myapp"
image: "ghcr.io/myorg/myapp"      # registry image (or just the Dockerfile path)
dockerfilePath: "Dockerfile"
containerPort: 3000

# ── Environment & Secrets ───────────────────────────────────────────
#  environmentFile  : plain key=value pairs — safe to commit
#  secretsFile      : sensitive key=value pairs — NEVER commit (add to .gitignore)
#  Spinx reads both and places them wherever the target platform needs them.
environmentFile: ".env"
secretsFile: ".env.secrets.prod"

# Optional inline env vars (non-secret, visible in generated config)
environmentVariables:
  APP_ENV: "production"
  REDIS_URL: "redis://10.0.0.1:6379/0"
  QUEUE_URL: "redis://10.0.0.1:6380/0"

# ── Accessories ──────────────────────────────────────────────────────
#  Accessories are named stateful services your app depends on.
#  On Kamal  → run as sidecar containers on the VPS.
#  On cloud  → wiring is via env vars (managed services like RDS, ElastiCache).
#  On K8s    → generate Deployment + Service + PVC manifests.
accessories:
  postgres:
    image: "postgres:16"
    port: 5432
    secrets:
      - POSTGRES_PASSWORD          # key name only — value comes from secretsFile
    env:
      POSTGRES_USER: "myapp"
      POSTGRES_DB: "myapp_prod"
    volumes:
      - "/var/lib/postgresql/data:/var/lib/postgresql/data"

  redis:
    image: "redis:7.2-alpine"
    port: 6379
    volumes:
      - "/var/lib/redis:/data"

  queue:
    image: "redis:7.2-alpine"
    port: 6380
    volumes:
      - "/var/lib/queue:/data"
    env:
      MAXMEMORY_POLICY: "noeviction"

# ── Provider-specific sections (only the relevant one is used) ───────

# Kamal / VPS
servers:
  - "10.0.0.1"
registry:
  server: "ghcr.io"
  username: "myorg"
  passwordEnvVar: "KAMAL_REGISTRY_PASSWORD"
sshUser: "deploy"

# AWS Fargate (used when provider = aws-fargate)
# region: "us-east-1"
# cpu: 512
# memory: 1024

# GCP Cloud Run (used when provider = gcp-cloudrun)
# projectId: "my-gcp-project"
# region: "us-central1"

# Azure Container Apps (used when provider = azure-container-apps)
# location: "East US"

# Kubernetes / future (used when provider = kubernetes)
# namespace: "production"
# storageClass: "standard"
```

**Key rules:**
- `environmentFile` and `secretsFile` are declared **once** at the top level.
- Spinx auto-wires their content onto every container — app and accessories —
  using the mechanism each platform supports (see §4).
- The `accessories` block is the same regardless of provider. Spinx decides at
  deploy time whether to run them as containers, map them to managed services,
  or generate manifests.

---

## 3. How `setup` works — generated spec + secrets file

Running `spinx <provider> setup` does two things before any infrastructure is
created:

### 3a. Generates a spec file

If no `-c <config>` is given, `setup` writes a **starter spec file** that you
can edit:

```
myapp-spinx.yaml          ← generated — edit this
```

The file contains:
- All base fields with sensible defaults
- The `accessories` block with postgres, redis, and queue commented in
- All provider-specific fields for the chosen provider
- Comments explaining every field

### 3b. Generates a secrets template

`setup` also writes a **secrets template** alongside the spec:

```
.env.secrets              ← generated — fill in real values, add to .gitignore
```

Contents match every key referenced in `secretsFile` across the app and all
accessories, with placeholder values:

```
# Spinx secrets template — generated by `spinx kamal setup`
# Fill in real values. NEVER commit this file. Add it to .gitignore.

# Registry
KAMAL_REGISTRY_PASSWORD=CHANGE_ME

# App secrets
SECRET_KEY_BASE=CHANGE_ME

# Accessories
POSTGRES_PASSWORD=CHANGE_ME
DATABASE_URL=postgres://myapp:CHANGE_ME@10.0.0.1:5432/myapp_prod
```

This means **every key you need is visible from day one** — you never have to
hunt through logs or documentation to find out what secrets your deployment
needs.

### File tracking summary

```
myapp-spinx.yaml          ← committed to source control ✅
.env                      ← committed to source control ✅  (non-secret env vars)
.env.secrets              ← NEVER committed ❌  (add to .gitignore)
```

---

## 4. Auto-wiring — how Spinx places env and secrets per provider

You declare `environmentFile` and `secretsFile` once. Spinx reads both at
deploy time and places their contents where each platform expects them:

| Platform | `environmentFile` | `secretsFile` |
|----------|------------------|---------------|
| **Kamal** | Keys from `.env` added to `deploy.yml` `env:` → passed to containers over SSH | Key *names* added to `deploy.yml` `secrets:` section; actual values injected into Kamal's subprocess environment so Kamal passes them securely |
| **AWS Fargate** | Added as plain environment variables on the ECS task definition | Added as secret environment variables (ECS `secrets:` field) — never written to the task-definition JSON in plain text |
| **GCP Cloud Run** | Added as environment variables on the Cloud Run revision | Added as secret environment variables on the revision |
| **Azure Container Apps** | Added as environment variables on the container app | Added as secret environment variables on the container app |
| **Kubernetes** *(future)* | Generated into a `ConfigMap`, mounted via `envFrom` | Generated into a Kubernetes `Secret`, mounted via `envFrom` |

**You change nothing in the config when switching providers.** Spinx adapts
the wiring automatically.

### Accessories also receive env and secrets

Each accessory's `secrets:` list references keys that Spinx pulls from the same
`secretsFile`. Spinx passes those secrets to the accessory container (Kamal) or
makes them available via the managed service's connection string (cloud).

---

## 5. Accessories — databases, Redis, queues

The `accessories` block is the same across all providers. Each entry has:

```yaml
accessories:
  <name>:
    image: "<docker-image>"        # used on Kamal / K8s; informational on cloud
    port: <port-number>            # container port
    secrets:                       # key names from secretsFile
      - KEY_NAME
    env:                           # non-secret env vars for this accessory
      KEY: value
    volumes:                       # host:container path mapping
      - "/host/path:/container/path"
```

### Supported accessory types

| Name | Image | Port | Notes |
|------|-------|------|-------|
| `postgres` | `postgres:16` | 5432 | Pass `POSTGRES_PASSWORD` via `secrets:` |
| `mysql` | `mysql:8` | 3306 | Pass `MYSQL_ROOT_PASSWORD` via `secrets:` |
| `redis` | `redis:7.2-alpine` | 6379 | Cache and session store |
| `queue` | `redis:7.2-alpine` | 6380 | Isolated Redis; set `MAXMEMORY_POLICY: noeviction` |
| `rabbitmq` | `rabbitmq:3-management` | 5672 | AMQP message broker |
| `mongo` | `mongo:7` | 27017 | Document store |

You can also add any other image — Spinx treats unknown accessories generically.

---

## 6. Kamal (VPS) — accessories run as containers

On Kamal, every `accessories` entry becomes a **sidecar container** managed by
Kamal on the VPS. This is how the full-stack config above works today — no extra
infrastructure required.

```bash
spinx kamal setup    # generates myapp-spinx.yaml + .env.secrets, then runs kamal setup
spinx kamal deploy   # builds image, pushes to registry, deploys app + accessories
spinx kamal logs     # streams logs from the app container
```

Spinx generates a `deploy.yml` from your config and invokes Kamal. Each
accessory becomes a Kamal accessory entry with the `host`, `port`, `image`,
`env`, `secrets`, and `volumes` you declared.

### Example — what Spinx generates for Kamal

Given the config in §2, Spinx produces:

```yaml
# Generated deploy.yml (do not edit manually)
service: myapp
image: ghcr.io/myorg/myapp
servers:
  - 10.0.0.1
registry:
  server: ghcr.io
  username: myorg
  password:
    - KAMAL_REGISTRY_PASSWORD     # ← from secretsFile (name only)
env:
  clear:
    APP_ENV: production
    REDIS_URL: redis://10.0.0.1:6379/0
    QUEUE_URL: redis://10.0.0.1:6380/0
  secret:
    - POSTGRES_PASSWORD           # ← from secretsFile (name only)
    - DATABASE_URL
accessories:
  postgres:
    image: postgres:16
    host: 10.0.0.1
    port: 5432
    env:
      clear:
        POSTGRES_USER: myapp
        POSTGRES_DB: myapp_prod
      secret:
        - POSTGRES_PASSWORD
    volumes:
      - /var/lib/postgresql/data:/var/lib/postgresql/data
  redis:
    image: redis:7.2-alpine
    host: 10.0.0.1
    port: 6379
    volumes:
      - /var/lib/redis:/data
  queue:
    image: redis:7.2-alpine
    host: 10.0.0.1
    port: 6380
    env:
      clear:
        MAXMEMORY_POLICY: noeviction
    volumes:
      - /var/lib/queue:/data
```

Secret values are **never** written to `deploy.yml` — only the key names appear.
Actual values come from `.env.secrets.prod` at deploy time.

---

## 7. Cloud providers — accessories map to managed services

On cloud platforms, you do not run database containers — you use managed
services (RDS, Cloud SQL, ElastiCache, etc.). The `accessories` block in your
Spinx config still serves as **documentation and wiring guidance**: Spinx reads
the accessory `secrets:` list to know which secrets to pass to the app
container, even when it is not spinning up a sidecar.

### How to wire each accessory type on each cloud

| Accessory | AWS Fargate | GCP Cloud Run | Azure Container Apps |
|-----------|------------|---------------|----------------------|
| `postgres` | Amazon RDS — set `DATABASE_URL` in `secretsFile` | Cloud SQL — set `DATABASE_URL` + `INSTANCE_CONNECTION_NAME` in `environmentVariables` | Azure Database for PostgreSQL — set `DATABASE_URL` in `secretsFile` |
| `redis` | Amazon ElastiCache — set `REDIS_URL` in `secretsFile` | Memorystore via VPC connector — set `REDIS_URL` in `environmentVariables` | Azure Cache for Redis — set `REDIS_HOST` + `REDIS_PASSWORD` in `secretsFile` |
| `queue` | Amazon SQS — set `JOB_QUEUE_URL` in `environmentVariables` | Cloud Pub/Sub — set `PUBSUB_TOPIC` in `environmentVariables` | Azure Service Bus — set `SERVICE_BUS_CONNECTION_STRING` in `secretsFile` |

The **Spinx config format does not change** — only the values inside
`environmentVariables` and `secretsFile` change to point at the managed
service endpoint instead of a local container.

### AWS Fargate example

```yaml
# myapp.yaml — same file, different values in .env.secrets.prod

region: "us-east-1"
serviceName: "myapp"
dockerfilePath: "Dockerfile"
containerPort: 3000
environmentFile: ".env"
secretsFile: ".env.secrets.prod"
environmentVariables:
  APP_ENV: "production"

accessories:
  postgres:
    image: "postgres:16"    # informational — RDS is the actual service
    port: 5432
    secrets:
      - DATABASE_URL
  redis:
    image: "redis:7.2-alpine"
    port: 6379
    secrets:
      - REDIS_URL
  queue:
    image: "redis:7.2-alpine"
    port: 6380
    env:
      JOB_QUEUE_URL: "https://sqs.us-east-1.amazonaws.com/123456789/myapp-jobs"
```

**.env.secrets.prod:**
```
DATABASE_URL=postgres://myapp:password@myapp.xyz.us-east-1.rds.amazonaws.com:5432/myapp
REDIS_URL=redis://myapp-cache.xyz.cache.amazonaws.com:6379/0
```

Spinx reads `DATABASE_URL` and `REDIS_URL` from the secrets file and adds them
as secret environment variables on the ECS task — the app container gets them
as ordinary env vars at runtime.

---

## 8. Kubernetes — where everything maps to

The Spinx config concepts map cleanly onto Kubernetes primitives:

| Spinx field | Kubernetes object |
|-------------|------------------|
| `serviceName` | `Deployment` name + `Service` name |
| `containerPort` | Pod `containerPort`; `Service.spec.ports` |
| `environmentFile` | `ConfigMap` (mounted via `envFrom`) |
| `secretsFile` | `Secret` (mounted via `envFrom`) |
| `environmentVariables` | Inline `env:` on the container spec |
| `accessories[*]` | Separate `Deployment` + `Service` + `PersistentVolumeClaim` |
| `accessories[*].secrets` | `secretKeyRef` entries in the accessory pod |
| `accessories[*].volumes` | `PersistentVolumeClaim` |
| `registry` | `imagePullSecret` |
| `minReplicas` / `maxReplicas` | `HorizontalPodAutoscaler` |

### Proposed Kubernetes config additions

To support `spinx kubernetes deploy`, the only additions to the existing format
would be a small set of K8s-specific fields:

```yaml
# Same myapp.yaml — add these for Kubernetes
namespace: "production"          # K8s namespace (default: "default")
storageClass: "standard"         # PVC storage class
ingressClass: "nginx"            # Ingress controller class
```

Everything else — `accessories`, `secretsFile`, `environmentFile` — stays
exactly the same. Spinx would generate:

```
k8s/
├── app-deployment.yaml        ← serviceName, containerPort, environmentFile, secretsFile
├── app-service.yaml
├── app-configmap.yaml         ← environmentFile contents
├── app-secret.yaml            ← secretsFile contents (base64)
├── postgres-deployment.yaml   ← accessories.postgres
├── postgres-service.yaml
├── postgres-pvc.yaml          ← accessories.postgres.volumes
├── redis-deployment.yaml      ← accessories.redis
├── redis-service.yaml
├── redis-pvc.yaml
├── queue-deployment.yaml      ← accessories.queue
├── queue-service.yaml
└── queue-pvc.yaml
```

**One YAML config, two execution models** — Kamal runs accessories as
containers via SSH, Kubernetes generates manifests. Same source of truth.

---

## 9. Secret vaults (proposed)

When `.env.secrets` is not enough (e.g. you need rotation, audit trails, or
centralized management), a `secretsVault:` field could pull secrets from a
cloud-native store at deploy time — replacing or supplementing `secretsFile`:

```yaml
# Proposed — not implemented yet
secretsVault:
  provider: "aws-secrets-manager"    # or gcp-secret-manager / azure-key-vault
  secretId: "myapp/prod"
  region: "us-east-1"               # AWS only
```

The rest of the config is unchanged. Spinx fetches the secret bundle at deploy
time and injects values exactly as it does with `secretsFile` today.

---

## 10. Recommended evolution path

```
Stage 1 — VPS with Kamal  (works today)
────────────────────────────────────────────────────────────────────
  spinx kamal setup    → generates myapp-spinx.yaml + .env.secrets
  spinx kamal deploy
  └── accessories: postgres, redis, queue run as containers on VPS

Stage 2 — Cloud (managed services)  (works today)
────────────────────────────────────────────────────────────────────
  spinx aws-fargate | gcp-cloudrun | azure-container-apps deploy
  └── same config — only .env.secrets values change to point at RDS /
      ElastiCache / Cloud SQL etc.

Stage 3 — Cloud secret vaults  (proposed)
────────────────────────────────────────────────────────────────────
  Add secretsVault: to config
  └── Spinx fetches secrets from AWS Secrets Manager / GCP Secret
      Manager / Azure Key Vault at deploy time instead of a local file

Stage 4 — Kubernetes  (proposed)
────────────────────────────────────────────────────────────────────
  spinx kubernetes deploy
  └── same accessories block → Spinx generates Deployment + Service +
      PVC manifests
  └── same secretsFile / secretsVault → Spinx generates Secret manifests
```

### What needs to change in Spinx to reach each stage

| Stage | Config change | Code change |
|-------|--------------|-------------|
| **Kamal full stack** (§6) | ✅ works today | ✅ none |
| **Cloud managed services** (§7) | ✅ works today — only values in `.env.secrets` change | ✅ none |
| **`setup` generates spec + secrets template** (§3) | ✅ none | `SpinxCli` setup action writes starter files |
| **Cloud secret vaults** (§9) | Add `secretsVault:` field | New `SecretVaultLoader` per provider |
| **Kubernetes provider** (§8) | Add `namespace`, `storageClass`, `ingressClass` | New `KubernetesDeployer` + manifest generators |
