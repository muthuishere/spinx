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
4. [`~/config/spinx/<reponame>/accessories/` — per-provider accessory config](#4-configspinxreponameaccessories--per-provider-accessory-config)
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
├── spinx.yaml      ← single config, all providers, commit ✅
├── .env            ← non-secret base env vars, commit ✅
├── .env.dev        ← dev overrides, commit ✅
├── .env.qa         ← qa overrides, commit ✅
└── .env.prod       ← prod overrides, commit ✅

# Secrets and accessory connection configs live outside the project — never committed:
# ~/config/spinx/<reponame>/secrets/secrets.<env>.json          (local only ❌)
# ~/config/spinx/<reponame>/accessories/<provider>/accessories.json  (local only ❌)
```

Core commands — the same for every provider:

```bash
spinx setup             # prompt interactively, generate spinx.yaml + env/secret files
spinx deploy --env prod # deploy using every provider module in spinx.yaml
spinx logs              # stream logs from the running service
spinx remove            # remove the running deployment only (containers/services stay in infra)
spinx uninstall         # remove everything Spinx created — containers, managed services, volumes
```

### Full `spinx.yaml` example

```yaml
# ─────────────────────────────────────────────────────────────────────
# Shared base — inherited by every provider module
# ─────────────────────────────────────────────────────────────────────
serviceName: "myapp"
dockerfilePath: "Dockerfile"
containerPort: 3000
environmentFile: ".env"          # default; profile file selected at deploy time with --env

# Optional inline non-secret env vars
environmentVariables:
  APP_ENV: "production"

# ── Accessories ───────────────────────────────────────────────────────
# Mandatory fields: dbname, username, password (SecretResolver key),
#                   connectionUrl (SecretResolver key for the full URL).
# Everything else goes in args: — plain container env vars.
accessories:
  postgres:
    dbname: "myapp"
    username: "myapp"
    password: "POSTGRES_PASSWORD"   # SecretResolver key — value never hardcoded
    connectionUrl: "DATABASE_URL"   # SecretResolver key for full psql:// string

  redis:
    password: "REDIS_PASSWORD"      # SecretResolver key (leave empty for no-auth local)
    connectionUrl: "REDIS_URL"

  queue:
    password: "QUEUE_PASSWORD"
    connectionUrl: "QUEUE_URL"
    args:
      MAXMEMORY: "256mb"            # extra image env vars

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

## 3. Accessories — mandatory fields + optional ARGS

Every accessory requires a small set of **mandatory first-class fields** that
Spinx understands directly. Everything beyond those goes in `args:` as plain
container env vars passed through verbatim.

### Mandatory fields per accessory

| Accessory | Mandatory field | Purpose |
|-----------|----------------|---------|
| `postgres` | `dbname` | Database name |
| `postgres` | `username` | DB login user |
| `postgres` | `password` | SecretResolver key whose value is the DB password |
| `postgres` | `connectionUrl` | SecretResolver key whose value is the full `postgresql://` string |
| `redis` | `password` | SecretResolver key (empty string = no-auth) |
| `redis` | `connectionUrl` | SecretResolver key for the full `redis://` or `rediss://` URL |
| `queue` | `password` | SecretResolver key |
| `queue` | `connectionUrl` | SecretResolver key for the full URL |

`password` and `connectionUrl` values are **never** hardcoded — they are the
**key names** that SecretResolver looks up in `~/config/spinx/<reponame>/secrets/secrets.<env>.json`
(or runner env vars in CI).

### Standard declaration

```yaml
accessories:
  postgres:
    dbname: "myapp"
    username: "myapp"
    password: "POSTGRES_PASSWORD"   # SecretResolver key
    connectionUrl: "DATABASE_URL"   # SecretResolver key

  redis:
    password: "REDIS_PASSWORD"
    connectionUrl: "REDIS_URL"

  queue:
    password: "QUEUE_PASSWORD"
    connectionUrl: "QUEUE_URL"
```

### Adding `args:` for extra env vars

`args:` passes additional env vars straight to the container (or managed service
configuration). Use it for anything beyond the four mandatory fields:

```yaml
accessories:
  postgres:
    dbname: "myapp_prod"
    username: "app_user"
    password: "POSTGRES_PASSWORD"
    connectionUrl: "DATABASE_URL"
    args:
      POSTGRES_INITDB_ARGS: "--encoding=UTF8 --locale=en_US.UTF-8"

  redis:
    password: "REDIS_PASSWORD"
    connectionUrl: "REDIS_URL"

  queue:
    password: "QUEUE_PASSWORD"
    connectionUrl: "QUEUE_URL"
    args:
      MAXMEMORY: "256mb"
      MAXMEMORY_POLICY: "allkeys-lru"
```

### Full accessory declaration (pinning image + volumes)

```yaml
accessories:
  postgres:
    image: "postgres:15"          # pin to a specific version
    port: 5432
    dbname: "myapp_prod"
    username: "app_user"
    password: "POSTGRES_PASSWORD"
    connectionUrl: "DATABASE_URL"
    volumes:
      - "/mnt/data/postgres:/var/lib/postgresql/data"
    args:
      POSTGRES_INITDB_ARGS: "--encoding=UTF8"

  redis:
    image: "redis:7.0"
    port: 6379
    password: "REDIS_PASSWORD"
    connectionUrl: "REDIS_URL"

  queue:
    image: "redis:7.0"
    port: 6380
    password: "QUEUE_PASSWORD"
    connectionUrl: "QUEUE_URL"
    args:
      MAXMEMORY: "256mb"
      MAXMEMORY_POLICY: "allkeys-lru"

  rabbitmq:                       # custom accessory beyond the defaults
    image: "rabbitmq:3-management"
    port: 5672
    username: "myapp"
    password: "RABBITMQ_PASSWORD"
    connectionUrl: "RABBITMQ_URL"
    args:
      RABBITMQ_DEFAULT_VHOST: "myapp"
```

### What `postgres` means in each provider

The `postgres` block in `spinx.yaml` is **identical** across all providers.
Spinx reads the mandatory fields and wires them differently per target:

#### Kamal (VPS / bare-metal)

Spinx runs a **PostgreSQL 16 sidecar container** on the VPS and generates:

```yaml
# Generated ~/config/spinx/myapp/kamal/deploy.yml — accessories section
accessories:
  myapp-postgres:
    image: postgres:16
    host: 10.0.0.1          # ← read from ~/config/spinx/myapp/accessories/kamal/accessories.json
    port: 5432
    env:
      clear:
        POSTGRES_DB:   myapp
        POSTGRES_USER: myapp
      secret:
        - POSTGRES_PASSWORD   # ← from ~/config/spinx/myapp/secrets/secrets.prod.json
        - DATABASE_URL
    volumes:
      - /var/lib/postgresql/data:/var/lib/postgresql/data
```

The **host IP** and any **TLS certificates** for the VPS database are stored in
`~/config/spinx/myapp/accessories/kamal/accessories.json` — set during `spinx setup`
and printeed after setup completes. Spinx reads them at deploy time to build the
Kamal config; they never go in `spinx.yaml`.

#### AWS Fargate (Amazon RDS)

Spinx does **not** run a container. It wires the RDS connection details as task
environment variables on the ECS task definition:

```
DATABASE_URL    → "postgresql://myapp:<password>@myapp.xyz.rds.amazonaws.com:5432/myapp_prod?sslmode=verify-full"
POSTGRES_PASSWORD → <resolved by SecretResolver>
```

Host, port, SSL mode, and the CA certificate path are stored in
`~/config/spinx/myapp/accessories/aws/accessories.json`.

#### Azure Container Apps (Azure Database for PostgreSQL)

Same wiring, different host:

```
DATABASE_URL → "postgresql://myapp@myapp-server:<password>@myapp-server.postgres.database.azure.com:5432/myapp_prod?sslmode=require"
```

Host and SSL config stored in `~/config/spinx/myapp/accessories/azure/accessories.json`.

#### GCP Cloud Run (Cloud SQL)

Cloud SQL uses a Unix socket proxy. Spinx generates the correct connection string:

```
DATABASE_URL → "postgresql://myapp:<password>@/myapp_prod?host=/cloudsql/proj:region:instance"
```

Instance connection name stored in `~/config/spinx/myapp/accessories/gcp/accessories.json`.

#### Kubernetes (proposed)

Spinx generates a **PostgreSQL Deployment + Service + PersistentVolumeClaim** in
the target namespace, plus a `Secret` for the password:

```yaml
# Generated — Deployment for the postgres accessory
apiVersion: apps/v1
kind: Deployment
metadata:
  name: myapp-postgres
spec:
  containers:
    - name: postgres
      image: postgres:16
      env:
        - name: POSTGRES_DB
          value: myapp
        - name: POSTGRES_USER
          value: myapp
        - name: POSTGRES_PASSWORD
          valueFrom:
            secretKeyRef:
              name: myapp-secrets
              key: POSTGRES_PASSWORD
```

### How `args:` are passed through

`args:` entries map directly to container env vars:

- **Kamal** → `env.clear:` block in the generated `deploy.yml` for that accessory
- **Cloud providers** → plain env vars on the managed-service connection configuration
- **Kubernetes** *(future)* → `env:` block in the accessory `Deployment` spec

---

## 4. `~/config/spinx/<reponame>/accessories/` — per-provider accessory config

After `spinx setup`, Spinx writes one **`accessories.json`** file per provider
into the user home config directory. This file stores the host/endpoint,
port, SSL mode, TLS certificate paths, and any provider-specific connection
details for every accessory.

These files live **outside the project** alongside secrets. They are printed
to the terminal after every `spinx setup` run so you can verify and update them.

### Directory layout (outside the project)

```
~/config/spinx/
└── myapp/                                   ← <reponame>
    ├── secrets/
    │   ├── secrets.dev.json                 ← local only ❌
    │   ├── secrets.qa.json
    │   └── secrets.prod.json
    ├── accessories/
    │   ├── kamal/
    │   │   └── accessories.json             ← VPS host IPs, certs  ❌
    │   ├── aws/
    │   │   └── accessories.json             ← RDS/ElastiCache endpoints ❌
    │   ├── gcp/
    │   │   └── accessories.json             ← Cloud SQL instance names ❌
    │   └── azure/
    │       └── accessories.json             ← Azure DB hostnames ❌
    └── kamal/
        └── deploy.yml                       ← generated Kamal config ❌
```

### `accessories.json` format

**`~/config/spinx/myapp/accessories/kamal/accessories.json`** (VPS)

```json
{
  "postgres": {
    "host": "10.0.0.1",
    "port": 5432,
    "sslMode": "disable",
    "connectionUrl": "postgresql://myapp:CHANGE_ME@10.0.0.1:5432/myapp"
  },
  "redis": {
    "host": "10.0.0.1",
    "port": 6379,
    "tls": false,
    "connectionUrl": "redis://10.0.0.1:6379"
  },
  "queue": {
    "host": "10.0.0.1",
    "port": 6380,
    "tls": false,
    "connectionUrl": "redis://10.0.0.1:6380"
  }
}
```

**`~/config/spinx/myapp/accessories/aws/accessories.json`** (RDS + ElastiCache)

```json
{
  "postgres": {
    "host": "myapp.xyz.rds.amazonaws.com",
    "port": 5432,
    "sslMode": "verify-full",
    "sslCert": "~/.spinxconfig/certs/rds-ca.pem",
    "connectionUrl": "postgresql://myapp:CHANGE_ME@myapp.xyz.rds.amazonaws.com:5432/myapp_prod?sslmode=verify-full"
  },
  "redis": {
    "host": "myapp.cache.amazonaws.com",
    "port": 6379,
    "tls": true,
    "connectionUrl": "rediss://:CHANGE_ME@myapp.cache.amazonaws.com:6379"
  },
  "queue": {
    "host": "myapp-queue.cache.amazonaws.com",
    "port": 6380,
    "tls": true,
    "connectionUrl": "rediss://:CHANGE_ME@myapp-queue.cache.amazonaws.com:6380"
  }
}
```

### How Spinx uses accessories.json

When you run `spinx deploy --env prod`, Spinx:

1. Reads `~/config/spinx/myapp/accessories/<provider>/accessories.json` for the provider being deployed
2. Reads `~/config/spinx/myapp/secrets/secrets.prod.json` via SecretResolver
3. Merges the structural connection data (host, port, sslMode) with the resolved secret values (password, full URL)
4. Injects the complete `DATABASE_URL`, `REDIS_URL`, `QUEUE_URL` into the deployment environment

### TLS certificates

Public CA certificates (e.g. AWS RDS CA bundle) are **not secrets** — they can
be stored anywhere accessible to Spinx. Convention: `~/.spinxconfig/certs/`:

```
~/.spinxconfig/certs/
├── rds-ca.pem        ← AWS RDS CA bundle (download from AWS docs)
└── redis-ca.pem      ← ElastiCache/Redis CA
```

Reference them from the `accessories.json` `sslCert` field. Private keys and
client certificates are secrets — store them in `~/config/spinx/myapp/secrets/`.

---

## 5. Environment-specific files — dev, qa, prod

Non-secret env vars use a **base + profile override** pattern — all committed:

```
.env          ← base vars, shared across all environments (commit ✅)
.env.dev      ← dev overrides  e.g. LOG_LEVEL=debug      (commit ✅)
.env.qa       ← qa overrides                              (commit ✅)
.env.prod     ← prod overrides e.g. LOG_LEVEL=warn        (commit ✅)
```

`spinx.yaml` always references the base file (`environmentFile: ".env"`).
The profile file is selected at deploy time with `--env`:

```yaml
environmentFile: ".env"    # default; profile merged at deploy time
```

When you run `spinx deploy --env prod`, Spinx:

1. Loads `.env` (base)
2. Merges `.env.prod` on top — profile values win
3. Invokes SecretResolver → `~/config/spinx/myapp/secrets/secrets.prod.json` (local)
   or CI env vars (pipeline)
4. Wires everything to each provider in `providers:` (see §7)

```
.env                ← base
      +
.env.prod           ← profile overrides
      +
SecretResolver      ← ~/config/spinx/myapp/secrets/secrets.prod.json  [local]
                       or CI env vars                                   [CI]
      =
Final env + secrets → every provider module
```

### Example files

**`.env`** — committed:
```
APP_NAME=myapp
LOG_LEVEL=info
CACHE_TTL=300
```

**`.env.prod`** — committed:
```
LOG_LEVEL=warn
CACHE_TTL=3600
```

**`.env.dev`** — committed:
```
LOG_LEVEL=debug
CACHE_TTL=0
```

---

## 6. SecretResolver — secrets in `~/config/spinx/<reponame>/`

The existing `secretsFile` field points to a file inside the project, which
must be added to `.gitignore`. The proposed evolution replaces it with a
**SecretResolver** that reads secrets from *outside* the project entirely.

The **non-secret structural parts** of each connection (host, port, dbname,
username, SSL mode, cert path) live in `~/config/spinx/<reponame>/accessories/<provider>/accessories.json`
(see §4). SecretResolver only handles the **values that must stay secret** —
passwords, tokens, and full connection URLs.

### Where secrets come from

| Runtime | Source |
|---------|--------|
| **Local development** | `~/config/spinx/<reponame>/secrets/secrets.<env>.json` |
| **CI (GitHub Actions)** | Repository secrets → runner env vars; SecretResolver auto-detects |
| **Future: cloud vault** | `secretsVault:` → AWS Secrets Manager / GCP Secret Manager / Azure Key Vault |

### Directory structure (outside the project)

```
~/config/spinx/
└── myapp/                                  ← <reponame>
    ├── secrets/
    │   ├── secrets.dev.json                ← dev secrets  (local only ❌)
    │   ├── secrets.qa.json                 ← qa secrets   (local only ❌)
    │   └── secrets.prod.json               ← prod secrets (local only ❌)
    ├── accessories/
    │   ├── kamal/accessories.json          ← VPS connection details (local only ❌)
    │   └── aws/accessories.json            ← RDS/ElastiCache details (local only ❌)
    └── kamal/
        └── deploy.yml                      ← generated Kamal config (local only ❌)
```

### Secrets file format (JSON)

Contains **only** the sensitive values. The structural parts of the
connection (host, port, dbname) are in `accessories.json`. SecretResolver
merges the two at deploy time.

```json
// ~/config/spinx/myapp/secrets/secrets.prod.json — fill in real values
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

`spinx setup` creates the skeleton JSON files with `CHANGE_ME` placeholders
(pre-populated with host/port/dbname from the accessories.json answers) and
**prints the full content** to the terminal so you can copy values immediately.

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

Spinx reads `spinx.yaml` (mandatory fields) + `~/config/spinx/myapp/accessories/kamal/accessories.json`
(host IPs and certificates), expands everything into a `deploy.yml`, and writes
it to `~/config/spinx/myapp/kamal/deploy.yml`. The `deploy.yml` **never lives
in the project directory**.

### What Spinx expands for Kamal

```yaml
# spinx.yaml (what you write)
accessories:
  postgres:
    dbname: "myapp"
    username: "myapp"
    password: "POSTGRES_PASSWORD"
    connectionUrl: "DATABASE_URL"

  redis:
    password: "REDIS_PASSWORD"
    connectionUrl: "REDIS_URL"

  queue:
    password: "QUEUE_PASSWORD"
    connectionUrl: "QUEUE_URL"
    args:
      MAXMEMORY: "256mb"
```

```json
// ~/config/spinx/myapp/accessories/kamal/accessories.json (set during spinx setup)
{
  "postgres": { "host": "10.0.0.1", "port": 5432, "sslMode": "disable" },
  "redis":    { "host": "10.0.0.1", "port": 6379 },
  "queue":    { "host": "10.0.0.1", "port": 6380 }
}
```

```yaml
# Generated ~/config/spinx/myapp/kamal/deploy.yml (what Kamal sees)
accessories:
  myapp-postgres:
    image: postgres:16
    host: 10.0.0.1
    port: 5432
    env:
      clear:
        POSTGRES_DB:   myapp
        POSTGRES_USER: myapp
      secret:
        - POSTGRES_PASSWORD
        - DATABASE_URL
    volumes:
      - /var/lib/postgresql/data:/var/lib/postgresql/data

  myapp-redis:
    image: redis:7.2-alpine
    host: 10.0.0.1
    port: 6379
    env:
      secret:
        - REDIS_PASSWORD
    volumes:
      - /var/lib/redis:/data

  myapp-queue:
    image: redis:7.2-alpine
    host: 10.0.0.1
    port: 6380
    env:
      clear:
        MAXMEMORY_POLICY: noeviction
        MAXMEMORY: "256mb"       # ← from args:
      secret:
        - QUEUE_PASSWORD
    volumes:
      - /var/lib/queue-data:/data
```

Full generated `deploy.yml`:

```yaml
# Generated by Spinx — ~/config/spinx/myapp/kamal/deploy.yml
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
    - REDIS_URL
    - QUEUE_URL
accessories:
  # ... expanded as shown above ...
```

---

## 9. Cloud providers — accessories map to managed services

On cloud platforms the `accessories` block in `spinx.yaml` is **identical**
to the Kamal version. Spinx reads the mandatory fields from `spinx.yaml` and
the host/endpoint from `~/config/spinx/myapp/accessories/<provider>/accessories.json`,
then calls SecretResolver for the password. Only the `accessories.json` changes
between providers — `spinx.yaml` never changes.

### Same `spinx.yaml`, different accessories.json per provider

```
spinx.yaml — unchanged across all providers:
  accessories:
    postgres:
      dbname: "myapp"
      username: "myapp"
      password: "POSTGRES_PASSWORD"
      connectionUrl: "DATABASE_URL"
    redis:
      password: "REDIS_PASSWORD"
      connectionUrl: "REDIS_URL"
    queue:
      password: "QUEUE_PASSWORD"
      connectionUrl: "QUEUE_URL"

~/config/spinx/myapp/accessories/kamal/accessories.json  (VPS):
  postgres.host = "10.0.0.1",  sslMode = "disable"

~/config/spinx/myapp/accessories/aws/accessories.json  (RDS):
  postgres.host = "myapp.xyz.rds.amazonaws.com",  sslMode = "verify-full"
  postgres.sslCert = "~/.spinxconfig/certs/rds-ca.pem"

~/config/spinx/myapp/accessories/gcp/accessories.json  (Cloud SQL):
  postgres.host = "/cloudsql/proj:region:instance"

~/config/spinx/myapp/accessories/azure/accessories.json  (Azure DB):
  postgres.host = "myapp-db.postgres.database.azure.com",  sslMode = "require"
```

The actual password values live only in `~/config/spinx/myapp/secrets/secrets.prod.json`
on your local machine (or in CI repo secrets). Spinx assembles the full
`DATABASE_URL` from `accessories.json` + the resolved secret at deploy time.

### Accessory to managed service mapping

| Accessory | AWS Fargate | GCP Cloud Run | Azure Container Apps |
|-----------|------------|---------------|----------------------|
| `postgres` | Amazon RDS | Cloud SQL | Azure Database for PostgreSQL |
| `redis` | Amazon ElastiCache | Cloud Memorystore | Azure Cache for Redis |
| `queue` | Amazon SQS / ElastiCache | Cloud Pub/Sub | Azure Service Bus |

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

`spinx setup` asks a few questions, generates all files, and **prints the full
credential summary** at the end so you can immediately copy values into the
secrets file. On subsequent runs it rescans `spinx.yaml` and validates/updates
existing config.

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
  Postgres DB name:    [myapp]
  Postgres username:   [myapp]
  Postgres password secret key: [POSTGRES_PASSWORD]
  Connection URL secret key:    [DATABASE_URL]

? Include Redis cache? (Y/n)  Y
  Redis password secret key:    [REDIS_PASSWORD]
  Connection URL secret key:    [REDIS_URL]

? Include message queue (separate Redis)? (Y/n)  Y
  Queue password secret key:    [QUEUE_PASSWORD]
  Connection URL secret key:    [QUEUE_URL]

  For kamal:
? Docker image: [ghcr.io/myorg/myapp]
? VPS IP(s): [10.0.0.1]
? Registry: [ghcr.io]   Username: [myorg]   SSH user: [deploy]

  For aws:
? Region: [us-east-1]   CPU: [512]   Memory: [1024]
? RDS hostname (postgres): [myapp.xyz.rds.amazonaws.com]
? ElastiCache hostname (redis): [myapp.cache.amazonaws.com]
? ElastiCache hostname (queue): [myapp-queue.cache.amazonaws.com]

✔  spinx.yaml                                                    (commit ✅)
✔  .env                                                          (commit ✅)
✔  .env.dev                                                      (commit ✅)
✔  .env.qa                                                       (commit ✅)
✔  .env.prod                                                     (commit ✅)
✔  ~/config/spinx/myapp/accessories/kamal/accessories.json       (local only ❌)
✔  ~/config/spinx/myapp/accessories/aws/accessories.json         (local only ❌)
✔  ~/config/spinx/myapp/secrets/secrets.dev.json                 (local only ❌)
✔  ~/config/spinx/myapp/secrets/secrets.qa.json                  (local only ❌)
✔  ~/config/spinx/myapp/secrets/secrets.prod.json                (local only ❌)
✔  ~/config/spinx/myapp/kamal/deploy.yml                         (local only ❌)

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  CREDENTIAL SUMMARY — fill in passwords and commit to vault
  Saved to ~/config/spinx/myapp/secrets/secrets.prod.json
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  KAMAL_REGISTRY_PASSWORD  → CHANGE_ME
  POSTGRES_PASSWORD        → CHANGE_ME
  DATABASE_URL             → postgresql://myapp:CHANGE_ME@myapp.xyz.rds.amazonaws.com:5432/myapp
  REDIS_PASSWORD           → CHANGE_ME
  REDIS_URL                → rediss://:CHANGE_ME@myapp.cache.amazonaws.com:6379
  QUEUE_PASSWORD           → CHANGE_ME
  QUEUE_URL                → rediss://:CHANGE_ME@myapp-queue.cache.amazonaws.com:6380
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  ACCESSORY ENDPOINTS (kamal) — ~/config/spinx/myapp/accessories/kamal/accessories.json
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  postgres  → 10.0.0.1:5432  db=myapp  user=myapp  ssl=disable
  redis     → 10.0.0.1:6379
  queue     → 10.0.0.1:6380
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  ACCESSORY ENDPOINTS (aws) — ~/config/spinx/myapp/accessories/aws/accessories.json
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  postgres  → myapp.xyz.rds.amazonaws.com:5432  ssl=verify-full
  redis     → myapp.cache.amazonaws.com:6379  tls=true
  queue     → myapp-queue.cache.amazonaws.com:6380  tls=true
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
```

### Generated `spinx.yaml`

```yaml
# Generated by `spinx setup` — edit as needed

serviceName: "myapp"
dockerfilePath: "Dockerfile"
containerPort: 3000
environmentFile: ".env"

environmentVariables:
  APP_ENV: "production"

# Accessories — declare mandatory fields here.
# host/endpoint per provider lives in ~/config/spinx/myapp/accessories/<provider>/accessories.json
# passwords live in ~/config/spinx/myapp/secrets/secrets.<env>.json
accessories:
  postgres:
    dbname: "myapp"
    username: "myapp"
    password: "POSTGRES_PASSWORD"
    connectionUrl: "DATABASE_URL"

  redis:
    password: "REDIS_PASSWORD"
    connectionUrl: "REDIS_URL"

  queue:
    password: "QUEUE_PASSWORD"
    connectionUrl: "QUEUE_URL"

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

### Generated `~/config/spinx/myapp/secrets/secrets.prod.json`

Pre-populated from setup answers — fill in passwords only:

```json
{
  "KAMAL_REGISTRY_PASSWORD": "CHANGE_ME",
  "POSTGRES_PASSWORD":       "CHANGE_ME",
  "DATABASE_URL":            "postgresql://myapp:CHANGE_ME@myapp.xyz.rds.amazonaws.com:5432/myapp",
  "REDIS_PASSWORD":          "CHANGE_ME",
  "REDIS_URL":               "rediss://:CHANGE_ME@myapp.cache.amazonaws.com:6379",
  "QUEUE_PASSWORD":          "CHANGE_ME",
  "QUEUE_URL":               "rediss://:CHANGE_ME@myapp-queue.cache.amazonaws.com:6380"
}
```

### File tracking

```
# Committed to the repo (safe — no secrets):
spinx.yaml                            ← commit ✅
.env                                  ← commit ✅
.env.dev                              ← commit ✅
.env.qa                               ← commit ✅
.env.prod                             ← commit ✅

# Local only — never committed:
~/config/spinx/myapp/accessories/kamal/accessories.json   ❌
~/config/spinx/myapp/accessories/aws/accessories.json     ❌
~/config/spinx/myapp/secrets/secrets.dev.json             ❌
~/config/spinx/myapp/secrets/secrets.qa.json              ❌
~/config/spinx/myapp/secrets/secrets.prod.json            ❌
~/config/spinx/myapp/kamal/deploy.yml                     ❌
```

---

## 12. Recommended evolution path

```
Stage 1 — VPS with Kamal
────────────────────────────────────────────────────────────────────
  spinx setup   → spinx.yaml (mandatory accessory fields)
                  ~/config/spinx/myapp/accessories/kamal/accessories.json
                  ~/config/spinx/myapp/secrets/secrets.*.json
                  Credentials printed to terminal
  spinx deploy --env prod
  └── postgres/redis/queue run as sidecar containers on VPS
  └── accessories.json provides host IP
  └── SecretResolver fills in POSTGRES_PASSWORD + DATABASE_URL
  └── Kamal deploy.yml generated to ~/config/spinx/myapp/kamal/
  spinx remove        → stop and remove the running containers
  spinx uninstall     → remove containers + volumes + all Spinx-created resources

Stage 2 — Cloud (managed services)
────────────────────────────────────────────────────────────────────
  spinx setup   → adds aws/gcp/azure provider module to spinx.yaml
                  ~/config/spinx/myapp/accessories/aws/accessories.json (RDS endpoints)
  spinx deploy --env prod
  └── same spinx.yaml accessories block — unchanged
  └── accessories/aws/accessories.json provides RDS/ElastiCache hosts
  └── secrets/secrets.prod.json holds passwords — same keys, new values
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
  └── accessories.json still drives host/endpoint configuration

Stage 4 — Kubernetes  (proposed)
────────────────────────────────────────────────────────────────────
  spinx setup   → adds k8s provider module to spinx.yaml
  spinx deploy --env prod
  └── accessories/k8s/accessories.json → drives Kubernetes ConfigMap for endpoints
  └── SecretResolver → Kubernetes Secret manifests for passwords
  └── accessories block → Deployment + Service + PVC manifests generated
```

### What needs to change in Spinx for each stage

| Stage | Config change | Code change |
|-------|--------------|-------------|
| **Kamal full stack** | ✅ works today — uses existing `secretsFile` | ✅ none |
| **`spinx.yaml` single file + provider modules** | New top-level `providers:` map | `ConfigLoader` reads `spinx.yaml`, dispatches per provider key |
| **Mandatory accessory fields** | `dbname`, `username`, `password`, `connectionUrl` required | `AccessoryExpander` validates mandatory fields, reads from `accessories.json` |
| **`~/config/spinx/<repo>/accessories/<provider>/accessories.json`** | New per-repo per-provider JSON | `SetupCommand` generates; printed after setup; `AccessoryExpander` reads at deploy |
| **`~/config/spinx/<repo>/secrets/`** | New path (was `secretsFile` in project) | SecretResolver reads `secrets.<env>.json` from home config dir |
| **`spinx setup` interactive** | ✅ none | New `SetupCommand` — prompts + generators; prints credential summary |
| **`spinx setup` credential print** | ✅ none | `SetupCommand` prints full secrets.json + accessories.json to terminal |
| **SecretResolver — local** | Remove `secretsFile`; add SecretResolver | Reads `~/config/spinx/<repo>/secrets/secrets.<env>.json` |
| **SecretResolver — CI** | ✅ none | Auto-detects `CI=true`, reads runner env vars |
| **`--env` profile flag** | ✅ none | Merge `.env.<profile>`; pass to SecretResolver |
| **`spinx remove` command** | ✅ none | Remove running deployment only |
| **`spinx uninstall` command** | ✅ none | Remove all infra/resources created by Spinx |
| **Kamal config in home dir** | ✅ none | Generate `deploy.yml` to `~/config/spinx/<repo>/kamal/` |
| **Cloud secret vaults** | Add `secretsVault:` field | SecretResolver vault backend |
| **Kubernetes provider** | Add `namespace`, `storageClass`, `ingressClass` | New `KubernetesDeployer` + manifest generators |
