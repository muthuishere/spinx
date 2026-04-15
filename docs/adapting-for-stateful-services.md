# Spinx — Config Design for Stateful Services
## Databases · Redis · Queues · Secret Vaults · Kamal · Kubernetes

This document describes how Spinx YAML configuration should evolve to support
stateful production infrastructure — Kamal (VPS), AWS Fargate, GCP Cloud Run,
Azure Container Apps, and future Kubernetes.

Two design goals drive everything:

1. **One file, provider modules** — a single `spinx.yaml` in the project root
   with a top-level `providers:` map; each provider is a named module that
   shares the common base and adds its own small section.
2. **Accessories are default-on** — `postgres`, `redis`, and `queue` each
   expose a small set of **mandatory first-class fields** (e.g. `dbname`,
   `username`, `password` for postgres) that Spinx understands and wires
   automatically. Everything beyond those mandatory fields is passed through
   as `args:` — plain container env vars. The short-form `true` uses sensible
   defaults for all mandatory fields so onboarding takes one line.

---

## Table of Contents

1. [The normalised existing config (what we have today)](#1-the-normalised-existing-config-what-we-have-today)
2. [Single-file design — `spinx.yaml` with provider modules](#2-single-file-design--spinxyaml-with-provider-modules)
3. [Accessories — default-on with optional ARGS overrides](#3-accessories--default-on-with-optional-args-overrides)
4. [`.spinxconfig/connections/` — connection strings, certs, and JSON configs](#4-spinxconfigconnections--connection-strings-certs-and-json-configs)
5. [Environment-specific files — dev, qa, prod](#5-environment-specific-files--dev-qa-prod)
6. [SecretResolver — secrets in `~/config/spinx/`](#6-secretresolver--secrets-in-configspinx)
7. [Auto-wiring — how Spinx places env and secrets per provider](#7-auto-wiring--how-spinx-places-env-and-secrets-per-provider)
8. [Kamal (VPS) — accessories run as containers](#8-kamal-vps--accessories-run-as-containers)
9. [Cloud providers — accessories map to managed services](#9-cloud-providers--accessories-map-to-managed-services)
10. [Kubernetes — where everything maps to (proposed)](#10-kubernetes--where-everything-maps-to-proposed)
11. [`spinx setup` — interactive scaffolding with fast onboarding](#11-spinx-setup--interactive-scaffolding-with-fast-onboarding)
12. [Recommended evolution path](#12-recommended-evolution-path)

---

## 1. The normalised existing config (what we have today)

`SpinxBaseConfig` is the common base that every provider config extends. These
fields exist **today** in the codebase and are the same in every provider YAML:

```yaml
serviceName: "myapp"
dockerfilePath: "Dockerfile"   # default: "Dockerfile"
containerPort: 8080            # default: 8080
environmentFile: ".env"        # default: ".env"  — loaded by EnvironmentParser
environmentVariables:          # inline non-secret vars; YAML takes precedence
  APP_ENV: "production"
secretsFile: ".env.secrets"    # loaded by EnvironmentParser.loadSecretsFile()
                               # values are NEVER logged — add to .gitignore
```

Provider-specific classes that extend `SpinxBaseConfig`:

| Class | Provider | Extra fields |
|-------|----------|-------------|
| `KamalConfig` | VPS via Kamal | `image`, `servers`, `registry`, `sshUser`, `secrets`, `accessories` |
| `FargateConfig` | AWS ECS Fargate | `region`, `cpu`, `memory`, `desiredCount`, `healthCheckPath`, `enableHttps`, … |
| `GcpCloudRunConfig` | GCP Cloud Run | `projectId`, `region`, `cpu`, `memory`, `minInstances`, `maxInstances`, `allowUnauthenticated`, … |
| `AzureContainerAppsConfig` | Azure Container Apps | `subscriptionId`, `resourceGroupName`, `location`, `cpu`, `memory`, `minReplicas`, `maxReplicas`, … |

The evolution described in this document keeps every existing field and adds
the new capabilities on top.

---

## 2. Single-file design — `spinx.yaml` with provider modules

Instead of one config file per provider, Spinx uses **one `spinx.yaml` in the
project root** with a `providers:` map. Each key is a provider module that
inherits the shared base and adds its small set of deployment fields.

```
your-project/
├── spinx.yaml                    ← single config, all providers, commit ✅
│
├── .spinxconfig/                 ← generated connection configs, commit ✅
│   └── connections/
│       ├── postgres.dev.json     ← psql connection config for dev  (commit ✅)
│       ├── postgres.qa.json      ← psql connection config for qa   (commit ✅)
│       ├── postgres.prod.json    ← psql connection config for prod (commit ✅)
│       ├── redis.dev.json        ← redis connection config for dev
│       ├── redis.prod.json
│       ├── queue.dev.json
│       └── queue.prod.json
│   # Certificates (e.g. RDS CA bundle, Redis TLS cert) go here too:
│   # .spinxconfig/certs/rds-ca.pem   ← commit ✅  (public CA cert, not a secret)
│
├── application.env               ← non-secret base env vars, commit ✅
├── application.dev.env           ← dev overrides, commit ✅
├── application.qa.env            ← qa overrides, commit ✅
└── application.prod.env          ← prod overrides, commit ✅

# Secret values (passwords, tokens) never live in the project:
# ~/config/spinx/secrets/secrets.<env>.json   (local only ❌)
```

Core commands — the same for every provider:

```bash
spinx setup             # prompt interactively, generate spinx.yaml + env/secret files
spinx deploy --env prod # deploy using every provider module in spinx.yaml
spinx logs              # stream logs from the running service
spinx remove            # tear down the deployment
```

### Full `spinx.yaml` example

```yaml
# ─────────────────────────────────────────────────────────────────────
# Shared base — inherited by every provider module
# ─────────────────────────────────────────────────────────────────────
serviceName: "myapp"
dockerfilePath: "Dockerfile"
containerPort: 3000
environmentFile: "application.env"

# Optional inline non-secret env vars
environmentVariables:
  APP_ENV: "production"

# ── Accessories ───────────────────────────────────────────────────────
# Each accessory has production-ready defaults built in.
# Set to true  → use all defaults (fastest onboarding).
# Set to false → disable.
# Pass args:   → override specific container env vars.
accessories:
  postgres: true          # PostgreSQL 16, port 5432, auto-persisted volume
  redis: true             # Redis 7, port 6379, auto-persisted volume
  queue: true             # Redis 7 isolated, port 6380, noeviction policy

# ── Providers ─────────────────────────────────────────────────────────
# Each key is a provider module. The shared base above is inherited.
# Spinx deploys to every provider listed here when you run `spinx deploy`.
providers:

  kamal:
    image: "ghcr.io/myorg/myapp"
    servers:
      - "10.0.0.1"
    registry:
      server: "ghcr.io"
      username: "myorg"
      passwordEnvVar: "KAMAL_REGISTRY_PASSWORD"
    sshUser: "deploy"

  aws:
    region: "us-east-1"
    cpu: 512
    memory: 1024
    desiredCount: 2
    healthCheckPath: "/api/health"
    enableHttps: true

  gcp:
    projectId: "my-gcp-project"
    region: "us-central1"
    cpu: "1"
    memory: "512Mi"
    minInstances: 0
    maxInstances: 10
    allowUnauthenticated: true

  azure:
    subscriptionId: "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"
    resourceGroupName: "myapp-rg"
    location: "East US"
    cpu: "0.25"
    memory: "0.5Gi"
    minReplicas: 0
    maxReplicas: 10
```

---

## 3. Accessories — default-on with optional ARGS overrides

### The three-line minimum

The fastest onboarding is to declare all three accessories with a single
boolean each:

```yaml
accessories:
  postgres: true
  redis: true
  queue: true
```

Spinx fills in every default:

| Accessory | Default image | Default port | Built-in env vars | Persisted volume |
|-----------|--------------|--------------|-------------------|-----------------|
| `postgres` | `postgres:16` | `5432` | `POSTGRES_DB=<serviceName>`, `POSTGRES_USER=<serviceName>` | `/var/lib/postgresql/data` |
| `redis` | `redis:7.2-alpine` | `6379` | — | `/data` |
| `queue` | `redis:7.2-alpine` | `6380` | `MAXMEMORY_POLICY=noeviction` | `/var/lib/queue-data` |

Secret keys that SecretResolver will look up (values never hardcoded):

| Accessory | Secret keys auto-wired |
|-----------|------------------------|
| `postgres` | `POSTGRES_PASSWORD`, `DATABASE_URL` |
| `redis` | `REDIS_URL` *(optional — bare Redis needs no password by default)* |
| `queue` | `QUEUE_URL` *(optional)* |

### Overriding with `args:`

When you need to change a specific container env var, add an `args:` map.
Everything else stays at its default:

```yaml
accessories:
  postgres:
    args:
      POSTGRES_DB: "myapp_prod"          # override the DB name
      POSTGRES_USER: "app_user"          # override the user

  redis: true                            # no overrides needed

  queue:
    args:
      MAXMEMORY: "256mb"                 # set a memory cap on the queue
      MAXMEMORY_POLICY: "allkeys-lru"    # change eviction policy
```

### Full accessory declaration (when you need everything explicit)

The full form is available when you want to pin image versions or add extra
env vars beyond the built-in defaults:

```yaml
accessories:
  postgres:
    image: "postgres:15"          # pin to a specific version
    port: 5432
    args:
      POSTGRES_DB: "myapp_prod"
      POSTGRES_USER: "app_user"
    secrets:
      - POSTGRES_PASSWORD         # additional secret key names
      - DATABASE_URL
    volumes:
      - "/mnt/data/postgres:/var/lib/postgresql/data"   # override volume path

  redis:
    image: "redis:7.0"
    port: 6379

  queue:
    image: "redis:7.0"
    port: 6380
    args:
      MAXMEMORY: "256mb"
      MAXMEMORY_POLICY: "allkeys-lru"

  rabbitmq:                       # add accessories beyond the defaults
    image: "rabbitmq:3-management"
    port: 5672
    args:
      RABBITMQ_DEFAULT_USER: "myapp"
    secrets:
      - RABBITMQ_DEFAULT_PASS

  mongo:
    image: "mongo:7"
    port: 27017
    secrets:
      - MONGO_INITDB_ROOT_PASSWORD
```

### How `args:` are passed through

`args:` entries map directly to container env vars. Spinx passes them to the
accessory container exactly as written:

- **Kamal** → `env.clear:` block in the generated `deploy.yml` for that accessory
- **Cloud providers** → plain env vars on the managed-service connection configuration
- **Kubernetes** *(future)* → `env:` block in the accessory `Deployment` spec

Secret values are **never** in `args:`. If a value is sensitive, put the key
name in `secrets:` and SecretResolver supplies the value at deploy time.

---

## 4. `.spinxconfig/connections/` — connection strings, certs, and JSON configs

Spinx generates a **`.spinxconfig/connections/`** directory inside the project
for every accessory that has a connection. Each file is a JSON document with the
structural (non-secret) connection details for one accessory + environment. The
secret values (passwords, tokens) are **never** written here — they stay in
`~/config/spinx/secrets/secrets.<env>.json` and are merged in by SecretResolver
at deploy time.

This folder is safe to commit. It gives every developer and CI job an
instant, readable picture of what each service connection looks like without
storing anything sensitive.

### File layout

```
.spinxconfig/
├── connections/
│   ├── postgres.dev.json
│   ├── postgres.qa.json
│   ├── postgres.prod.json
│   ├── redis.dev.json
│   ├── redis.prod.json
│   ├── queue.dev.json
│   └── queue.prod.json
└── certs/
    ├── rds-ca.pem          ← public CA bundle for RDS TLS (commit ✅ — not a secret)
    └── redis-ca.pem        ← public CA for ElastiCache TLS (commit ✅)
```

### Connection file format

Each JSON file captures the **non-secret** structural details of the
connection. Secret values are referenced by their **key name** only — the
actual value is injected by SecretResolver.

**`.spinxconfig/connections/postgres.prod.json`**

```json
{
  "accessory": "postgres",
  "env": "prod",
  "host": "myapp.xyz.rds.amazonaws.com",
  "port": 5432,
  "dbname": "myapp_prod",
  "username": "myapp",
  "sslMode": "verify-full",
  "sslCert": ".spinxconfig/certs/rds-ca.pem",
  "passwordSecretKey": "POSTGRES_PASSWORD",
  "connectionUrlSecretKey": "DATABASE_URL"
}
```

**`.spinxconfig/connections/postgres.dev.json`** (local VPS via Kamal)

```json
{
  "accessory": "postgres",
  "env": "dev",
  "host": "10.0.0.1",
  "port": 5432,
  "dbname": "myapp_dev",
  "username": "myapp",
  "sslMode": "disable",
  "passwordSecretKey": "POSTGRES_PASSWORD",
  "connectionUrlSecretKey": "DATABASE_URL"
}
```

**`.spinxconfig/connections/redis.prod.json`**

```json
{
  "accessory": "redis",
  "env": "prod",
  "host": "myapp.cache.amazonaws.com",
  "port": 6379,
  "tls": true,
  "sslCert": ".spinxconfig/certs/redis-ca.pem",
  "passwordSecretKey": "REDIS_PASSWORD",
  "connectionUrlSecretKey": "REDIS_URL"
}
```

**`.spinxconfig/connections/queue.prod.json`**

```json
{
  "accessory": "queue",
  "env": "prod",
  "host": "myapp-queue.cache.amazonaws.com",
  "port": 6380,
  "tls": true,
  "passwordSecretKey": "QUEUE_PASSWORD",
  "connectionUrlSecretKey": "QUEUE_URL"
}
```

### How Spinx uses the connection files

When you run `spinx deploy --env prod`, Spinx:

1. Reads `.spinxconfig/connections/<accessory>.prod.json` for each enabled accessory
2. Calls SecretResolver to fill in the `passwordSecretKey` and `connectionUrlSecretKey` values
3. Assembles the complete connection env vars (e.g. `DATABASE_URL`, `POSTGRES_PASSWORD`) and injects them into the deployment

The `connectionUrlSecretKey` value in `~/config/spinx/secrets/secrets.prod.json` can either be a full connection string you supply, or Spinx can derive it from the connection file's `host`, `port`, `dbname`, `username`, and the resolved `passwordSecretKey` value.

### Certificates

TLS certificates (e.g. the RDS CA bundle, ElastiCache CA) are **public certificates** — they are safe to commit. Place them in `.spinxconfig/certs/` and reference them from the connection file's `sslCert` field. Spinx mounts or passes them to the deployer as needed.

```
.spinxconfig/certs/
├── rds-ca.pem          ← AWS RDS CA bundle (download from AWS docs — public ✅)
└── redis-ca.pem        ← ElastiCache/Redis CA (public ✅)
```

Private keys and client certificates are secrets — they belong in
`~/config/spinx/secrets/` or a cloud vault, never in `.spinxconfig/`.

---

## 5. Environment-specific files — dev, qa, prod

Non-secret env vars use a **base + profile override** pattern — all committed:

```
application.env          ← base vars, shared across all environments (commit ✅)
application.dev.env      ← dev overrides  e.g. LOG_LEVEL=debug      (commit ✅)
application.qa.env       ← qa overrides                              (commit ✅)
application.prod.env     ← prod overrides e.g. LOG_LEVEL=warn        (commit ✅)
```

`spinx.yaml` always references the base file:

```yaml
environmentFile: "application.env"
# Profile-specific file is selected at deploy time with --env
```

When you run `spinx deploy --env prod`, Spinx:

1. Loads `application.env` (base)
2. Merges `application.prod.env` on top — profile values win
3. Invokes SecretResolver → `~/config/spinx/secrets/secrets.prod.json` (local)
   or CI env vars (pipeline)
4. Wires everything to each provider in `providers:` (see §7)

```
application.env               ← base
      +
application.prod.env          ← profile overrides
      +
SecretResolver (prod secrets) ← ~/config/spinx/secrets/secrets.prod.json  [local]
                                 or CI env vars                             [CI]
      =
Final env + secrets → every provider module
```

### Example files

**`application.env`** — committed:
```
APP_NAME=myapp
LOG_LEVEL=info
CACHE_TTL=300
```

**`application.prod.env`** — committed:
```
LOG_LEVEL=warn
CACHE_TTL=3600
```

**`application.dev.env`** — committed:
```
LOG_LEVEL=debug
CACHE_TTL=0
```

---

## 6. SecretResolver — secrets in `~/config/spinx/`

The existing `secretsFile` field points to a file inside the project, which
must be added to `.gitignore`. The proposed evolution replaces it with a
**SecretResolver** that reads secrets from *outside* the project entirely.

The **non-secret structural parts** of each connection (host, port, dbname,
username, SSL mode, cert path) live in `.spinxconfig/connections/` (see §4)
and are committed. SecretResolver only handles the **values that must stay
secret** — passwords, tokens, and full connection URLs.

### Where secrets come from

| Runtime | Source |
|---------|--------|
| **Local development** | `~/config/spinx/secrets/secrets.<env>.json` |
| **CI (GitHub Actions)** | Repository secrets → runner env vars; SecretResolver auto-detects |
| **Future: cloud vault** | `secretsVault:` → AWS Secrets Manager / GCP Secret Manager / Azure Key Vault |

### Directory structure (outside the project)

```
~/config/spinx/
├── secrets/
│   ├── secrets.dev.json    ← dev secrets  (local only ❌)
│   ├── secrets.qa.json     ← qa secrets   (local only ❌)
│   └── secrets.prod.json   ← prod secrets (local only ❌)
└── kamal/
    └── myapp/
        └── deploy.yml      ← generated Kamal config (regenerated each deploy)
```

### Secrets file format (JSON)

Contains **only** the sensitive values. The structural parts of the
connection are already in `.spinxconfig/connections/` (commit ✅). SecretResolver
merges the two at deploy time.

```json
// ~/config/spinx/secrets/secrets.prod.json — fill in real values
{
  "KAMAL_REGISTRY_PASSWORD": "CHANGE_ME",
  "POSTGRES_PASSWORD":       "CHANGE_ME",
  "DATABASE_URL":            "postgresql://myapp:CHANGE_ME@myapp.rds.amazonaws.com:5432/myapp_prod",
  "REDIS_PASSWORD":          "CHANGE_ME",
  "REDIS_URL":               "rediss://:CHANGE_ME@myapp.cache.amazonaws.com:6379",
  "QUEUE_PASSWORD":          "CHANGE_ME",
  "QUEUE_URL":               "rediss://:CHANGE_ME@myapp-queue.cache.amazonaws.com:6380"
}
```

`spinx setup` creates the skeleton JSON files with `CHANGE_ME` placeholders,
pre-populated from the host/port/dbname already in `.spinxconfig/connections/`.
You only need to fill in the actual password values.

### CI pipelines — GitHub Actions

```yaml
# .github/workflows/deploy.yml — excerpt
- name: Deploy to prod
  env:
    DATABASE_URL:            ${{ secrets.DATABASE_URL }}
    POSTGRES_PASSWORD:       ${{ secrets.POSTGRES_PASSWORD }}
    REDIS_URL:               ${{ secrets.REDIS_URL }}
    REDIS_PASSWORD:          ${{ secrets.REDIS_PASSWORD }}
    QUEUE_URL:               ${{ secrets.QUEUE_URL }}
    QUEUE_PASSWORD:          ${{ secrets.QUEUE_PASSWORD }}
    KAMAL_REGISTRY_PASSWORD: ${{ secrets.KAMAL_REGISTRY_PASSWORD }}
  run: spinx deploy --env prod
```

SecretResolver detects `CI=true`, reads values from runner env vars, and
optionally writes a short-lived temp file for any subprocess. The temp file
is deleted immediately after the deploy.

### Cloud secret vaults (proposed)

```yaml
# Proposed — not yet implemented
secretsVault:
  provider: "aws-secrets-manager"
  secretId: "myapp/prod"
  region: "us-east-1"
```

---

## 7. Auto-wiring — how Spinx places env and secrets per provider

`environmentFile` and accessories are declared once in `spinx.yaml`. Spinx
wires them to each provider module automatically:

| Provider | Non-secret env vars | Secrets (SecretResolver) | Accessories |
|----------|--------------------|--------------------------|----|
| **kamal** | `env.clear:` in `deploy.yml` | Key names in `env.secret:`; values injected into Kamal subprocess | Sidecar containers via `accessories:` in `deploy.yml` |
| **aws** | Plain env vars on ECS task definition | ECS secret env vars (never in task-def JSON in plain text) | Wired to RDS / ElastiCache via env vars |
| **gcp** | Env vars on Cloud Run revision | Secret env vars on the revision | Wired to Cloud SQL / Memorystore via env vars |
| **azure** | Env vars on container app | Secret env vars on the container app | Wired to Azure DB / Cache for Redis via env vars |
| **k8s** *(future)* | `ConfigMap` via `envFrom` | `Secret` via `envFrom` | Separate `Deployment` + `Service` + `PVC` |

---

## 8. Kamal (VPS) — accessories run as containers

On Kamal every accessory runs as a **sidecar container** managed by Kamal
alongside the main app on the same VPS.

Spinx reads `spinx.yaml`, expands the accessory defaults, generates a
`deploy.yml` into `~/config/spinx/kamal/<project>/deploy.yml`, and invokes
Kamal from there. The `deploy.yml` **never lives in the project directory**.

### Accessory default expansion

```yaml
# spinx.yaml (what you write)
accessories:
  postgres: true
  redis: true
  queue:
    args:
      MAXMEMORY: "256mb"
```

```yaml
# Generated ~/config/spinx/kamal/myapp/deploy.yml (what Kamal sees)
accessories:
  postgres:
    image: postgres:16
    host: 10.0.0.1
    port: 5432
    env:
      clear:
        POSTGRES_DB: myapp
        POSTGRES_USER: myapp
      secret:
        - POSTGRES_PASSWORD
        - DATABASE_URL
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
        MAXMEMORY: "256mb"       # ← from args:
    volumes:
      - /var/lib/queue-data:/data
```

Full generated `deploy.yml`:

```yaml
# Generated by Spinx — ~/config/spinx/kamal/myapp/deploy.yml
service: myapp
image: ghcr.io/myorg/myapp
servers:
  - 10.0.0.1
registry:
  server: ghcr.io
  username: myorg
  password:
    - KAMAL_REGISTRY_PASSWORD
env:
  clear:
    APP_ENV: production
  secret:
    - DATABASE_URL
    - POSTGRES_PASSWORD
accessories:
  # ... expanded as shown above ...
```

---

## 9. Cloud providers — accessories map to managed services

On cloud platforms the `accessories` block in `spinx.yaml` is **the same**
as the Kamal version — Spinx reads `.spinxconfig/connections/<accessory>.<env>.json`
to get the host/port/dbname and calls SecretResolver for the password.
The only change between providers is the `host` value inside the connection file.

### Same `spinx.yaml`, different connection files

```
spinx.yaml
  accessories:
    postgres: true   ← identical across all providers
    redis: true
    queue: true

.spinxconfig/connections/postgres.prod.json  (kamal / local Postgres on VPS):
  "host": "10.0.0.1",  "dbname": "myapp_prod",  "sslMode": "disable"

.spinxconfig/connections/postgres.prod.json  (aws / RDS):
  "host": "myapp.xyz.rds.amazonaws.com",  "sslMode": "verify-full",
  "sslCert": ".spinxconfig/certs/rds-ca.pem"

.spinxconfig/connections/postgres.prod.json  (gcp / Cloud SQL):
  "host": "/cloudsql/proj:region:instance",  "sslMode": "disable"

.spinxconfig/connections/postgres.prod.json  (azure / Azure DB for PostgreSQL):
  "host": "myapp-db.postgres.database.azure.com",  "sslMode": "require"
```

The actual password values live only in `~/config/spinx/secrets/secrets.prod.json`
on your local machine (or in CI repo secrets). Spinx assembles the full
`DATABASE_URL` from the connection file + the resolved secret at deploy time.

### Accessory to managed service mapping

| Accessory | AWS Fargate | GCP Cloud Run | Azure Container Apps |
|-----------|------------|---------------|----------------------|
| `postgres` | Amazon RDS | Cloud SQL | Azure Database for PostgreSQL |
| `redis` | Amazon ElastiCache | Cloud Memorystore | Azure Cache for Redis |
| `queue` | Amazon SQS | Cloud Pub/Sub | Azure Service Bus |

---

## 10. Kubernetes — where everything maps to (proposed)

`spinx.yaml` adds a `k8s` provider module. Spinx generates Kubernetes
manifests from the shared base + accessories:

| Spinx field | Kubernetes object |
|-------------|------------------|
| `serviceName` | `Deployment` + `Service` name |
| `containerPort` | Pod `containerPort`; `Service.spec.ports` |
| `environmentFile` (profile merged) | `ConfigMap` via `envFrom` |
| Secrets via SecretResolver | `Secret` via `envFrom` |
| `environmentVariables` | Inline `env:` on container spec |
| `accessories[postgres]` | `Deployment` + `Service` + `PVC` |
| `accessories[redis]` | `Deployment` + `Service` + `PVC` |
| `accessories[queue]` | `Deployment` + `Service` + `PVC` |
| Accessory `secrets:` | `secretKeyRef` entries in accessory pod |
| `registry.passwordEnvVar` | `imagePullSecret` |

```yaml
# spinx.yaml — k8s provider module
providers:
  k8s:
    namespace: "production"
    storageClass: "standard"
    ingressClass: "nginx"
```

---

## 11. `spinx setup` — interactive scaffolding with fast onboarding

`spinx setup` asks a few questions and generates everything — `spinx.yaml`,
`.spinxconfig/connections/` files, env files, and secrets templates. On
subsequent runs it rescans `spinx.yaml` and validates/updates existing config.

```
$ spinx setup

? Service name: [myapp]
? Container port: [3000]
? Path to Dockerfile: [Dockerfile]

? Which environments do you need?
  ❯ ✔ dev
    ✔ qa
    ✔ prod

? Which providers do you want to configure?
  ❯ ✔ kamal  (VPS / bare-metal)
    ✔ aws    (AWS Fargate / ECS)
      gcp    (GCP Cloud Run)
      azure  (Azure Container Apps)

? Include PostgreSQL?  (Y/n)  Y
? Include Redis cache? (Y/n)  Y
? Include message queue (separate Redis)? (Y/n)  Y

→ Generating connection config files in .spinxconfig/connections/ ...

  For kamal:
? Docker image: [ghcr.io/myorg/myapp]
? VPS IP(s): [10.0.0.1]
? Registry: [ghcr.io]   Username: [myorg]   SSH user: [deploy]

  For aws:
? Region: [us-east-1]   CPU: [512]   Memory: [1024]
? RDS hostname (postgres): [myapp.xyz.rds.amazonaws.com]
? ElastiCache hostname (redis): [myapp.cache.amazonaws.com]

✔  spinx.yaml                                          (commit ✅)
✔  .spinxconfig/connections/postgres.dev.json          (commit ✅)
✔  .spinxconfig/connections/postgres.qa.json           (commit ✅)
✔  .spinxconfig/connections/postgres.prod.json         (commit ✅)
✔  .spinxconfig/connections/redis.dev.json             (commit ✅)
✔  .spinxconfig/connections/redis.prod.json            (commit ✅)
✔  .spinxconfig/connections/queue.dev.json             (commit ✅)
✔  .spinxconfig/connections/queue.prod.json            (commit ✅)
✔  application.env                                     (commit ✅)
✔  application.dev.env                                 (commit ✅)
✔  application.qa.env                                  (commit ✅)
✔  application.prod.env                                (commit ✅)
✔  ~/config/spinx/secrets/secrets.dev.json             (local only ❌)
✔  ~/config/spinx/secrets/secrets.qa.json              (local only ❌)
✔  ~/config/spinx/secrets/secrets.prod.json            (local only ❌)
✔  ~/config/spinx/kamal/myapp/deploy.yml               (local only ❌)
```

### Generated `spinx.yaml`

```yaml
# Generated by `spinx setup` — edit as needed

serviceName: "myapp"
dockerfilePath: "Dockerfile"
containerPort: 3000
environmentFile: "application.env"

environmentVariables:
  APP_ENV: "production"

# Accessories — defaults are production-ready.
# Add args: to override specific container env vars.
accessories:
  postgres: true
  redis: true
  queue: true

providers:

  kamal:
    image: "ghcr.io/myorg/myapp"
    servers:
      - "10.0.0.1"
    registry:
      server: "ghcr.io"
      username: "myorg"
      passwordEnvVar: "KAMAL_REGISTRY_PASSWORD"
    sshUser: "deploy"

  aws:
    region: "us-east-1"
    cpu: 512
    memory: 1024
    desiredCount: 1
    healthCheckPath: "/health"
    enableHttps: true
```

### Generated `~/config/spinx/secrets/secrets.prod.json`

Pre-populated from the connection files — you only need to fill in passwords:

```json
{
  "KAMAL_REGISTRY_PASSWORD": "CHANGE_ME",
  "POSTGRES_PASSWORD":       "CHANGE_ME",
  "DATABASE_URL":            "postgresql://myapp:CHANGE_ME@myapp.xyz.rds.amazonaws.com:5432/myapp_prod",
  "REDIS_PASSWORD":          "CHANGE_ME",
  "REDIS_URL":               "rediss://:CHANGE_ME@myapp.cache.amazonaws.com:6379",
  "QUEUE_PASSWORD":          "CHANGE_ME",
  "QUEUE_URL":               "rediss://:CHANGE_ME@myapp-queue.cache.amazonaws.com:6380"
}
```

The host/port/dbname values come from `.spinxconfig/connections/` — already
committed. You only ever type in actual secret values on your local machine.

### File tracking

```
spinx.yaml                                          ← commit ✅
.spinxconfig/connections/postgres.dev.json          ← commit ✅
.spinxconfig/connections/postgres.qa.json           ← commit ✅
.spinxconfig/connections/postgres.prod.json         ← commit ✅
.spinxconfig/connections/redis.dev.json             ← commit ✅
.spinxconfig/connections/redis.prod.json            ← commit ✅
.spinxconfig/connections/queue.dev.json             ← commit ✅
.spinxconfig/connections/queue.prod.json            ← commit ✅
.spinxconfig/certs/rds-ca.pem                       ← commit ✅  (public CA cert)
application.env                                     ← commit ✅
application.dev.env                                 ← commit ✅
application.qa.env                                  ← commit ✅
application.prod.env                                ← commit ✅

~/config/spinx/secrets/secrets.dev.json             ← local only ❌
~/config/spinx/secrets/secrets.qa.json              ← local only ❌
~/config/spinx/secrets/secrets.prod.json            ← local only ❌
~/config/spinx/kamal/myapp/deploy.yml               ← local only ❌
```

---

## 12. Recommended evolution path

```
Stage 1 — VPS with Kamal
────────────────────────────────────────────────────────────────────
  spinx setup   → spinx.yaml + .spinxconfig/connections/*.dev.json
                  + env files + secrets templates
  spinx deploy --env prod
  └── accessories: postgres/redis/queue run as sidecar containers on VPS
  └── Spinx reads .spinxconfig/connections/postgres.prod.json for host/dbname
  └── SecretResolver fills in POSTGRES_PASSWORD + DATABASE_URL
  └── Kamal deploy.yml generated to ~/config/spinx/kamal/myapp/

Stage 2 — Cloud (managed services)
────────────────────────────────────────────────────────────────────
  spinx setup   → adds aws/gcp/azure provider module to spinx.yaml
                  updates .spinxconfig/connections/*.prod.json with cloud hosts
  spinx deploy --env prod
  └── same spinx.yaml accessories block — unchanged
  └── .spinxconfig/connections/postgres.prod.json host now points at RDS
  └── secrets.prod.json still only holds passwords/URLs — same keys, new values
  In CI:
  └── SecretResolver reads from runner env vars (GitHub repo secrets)
  └── no secrets files on disk

Stage 3 — Cloud secret vaults  (proposed)
────────────────────────────────────────────────────────────────────
  Add to spinx.yaml:
    secretsVault:
      provider: "aws-secrets-manager"
      secretId: "myapp/prod"
  └── SecretResolver fetches from vault at deploy time — no local JSON needed
  └── .spinxconfig/connections/ files remain in repo as structural reference

Stage 4 — Kubernetes  (proposed)
────────────────────────────────────────────────────────────────────
  spinx setup   → adds k8s provider module to spinx.yaml
  spinx deploy --env prod
  └── .spinxconfig/connections/ → drives Kubernetes ConfigMap for accessory endpoints
  └── SecretResolver → Kubernetes Secret manifests for passwords
  └── accessories: true → Deployment + Service + PVC manifests generated
```

### What needs to change in Spinx for each stage

| Stage | Config change | Code change |
|-------|--------------|-------------|
| **Kamal full stack** | ✅ works today — uses existing `secretsFile` | ✅ none |
| **`spinx.yaml` single file + provider modules** | New top-level `providers:` map | `ConfigLoader` reads `spinx.yaml`, dispatches per provider key |
| **Accessory defaults + `args:`** | `accessories: true` short-form | `AccessoryExpander` — fills defaults, merges `args:` |
| **`.spinxconfig/connections/` folder** | New JSON files per accessory + env | `SetupCommand` generates them; `AccessoryExpander` reads them at deploy |
| **`.spinxconfig/certs/` folder** | CA certs committed alongside connection files | Deployers mount cert path from connection JSON `sslCert` field |
| **`spinx setup` interactive** | ✅ none | New `SetupCommand` — prompts + file generators for connections |
| **SecretResolver — local** | Remove `secretsFile`; add SecretResolver | Reads `~/config/spinx/secrets/secrets.<env>.json` |
| **SecretResolver — CI** | ✅ none | Auto-detects `CI=true`, reads runner env vars |
| **`--env` profile flag** | ✅ none | Merge `application.<env>.env`; pass to SecretResolver |
| **`spinx remove` command** | ✅ none | Alias of existing `destroy` action |
| **Kamal config in home dir** | ✅ none | Generate `deploy.yml` to `~/config/spinx/kamal/<project>/` |
| **Cloud secret vaults** | Add `secretsVault:` field | SecretResolver vault backend |
| **Kubernetes provider** | Add `namespace`, `storageClass`, `ingressClass` | New `KubernetesDeployer` + manifest generators |
