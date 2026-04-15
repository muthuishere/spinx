# Spinx — Config Design for Stateful Services
## Databases · Redis · Queues · Secret Vaults · Kamal · Kubernetes

This document describes how Spinx YAML configuration should evolve to support
stateful production infrastructure — Kamal (VPS), AWS Fargate, GCP Cloud Run,
Azure Container Apps, and future Kubernetes — starting from the **normalised
config that already exists in the codebase** and layering each new capability
on top.

---

## Table of Contents

1. [The normalised existing config](#1-the-normalised-existing-config)
2. [One config file per provider — `.spinxconfig/`](#2-one-config-file-per-provider--spinxconfig)
3. [Provider-specific fields (what differs per file)](#3-provider-specific-fields-what-differs-per-file)
4. [Accessories — databases, Redis, queues](#4-accessories--databases-redis-queues)
5. [Environment-specific files — dev, qa, prod](#5-environment-specific-files--dev-qa-prod)
6. [SecretResolver — secrets live in `~/config/spinx/`](#6-secretresolver--secrets-live-in-configspinx)
7. [Auto-wiring — how Spinx places env and secrets per provider](#7-auto-wiring--how-spinx-places-env-and-secrets-per-provider)
8. [Kamal (VPS) — accessories run as containers](#8-kamal-vps--accessories-run-as-containers)
9. [Cloud providers — accessories map to managed services](#9-cloud-providers--accessories-map-to-managed-services)
10. [Kubernetes — where everything maps to (proposed)](#10-kubernetes--where-everything-maps-to-proposed)
11. [`spinx setup` — interactive scaffolding with sensible defaults](#11-spinx-setup--interactive-scaffolding-with-sensible-defaults)
12. [Recommended evolution path](#12-recommended-evolution-path)

---

## 1. The normalised existing config

`SpinxBaseConfig` is the common base class that every provider config extends.
These fields exist **today** in the codebase and are the same in every provider
YAML file:

```yaml
# ── App identity (SpinxBaseConfig) ──────────────────────────────────
serviceName: "myapp"
dockerfilePath: "Dockerfile"          # default: "Dockerfile"
containerPort: 8080                   # default: 8080

# ── Environment (SpinxBaseConfig) ────────────────────────────────────
# Points to a .env-format file of non-secret key=value pairs.
# EnvironmentParser loads this file and merges it with environmentVariables.
environmentFile: "application.env"   # default: ".env"

# Inline non-secret env vars (YAML takes precedence over environmentFile)
environmentVariables:
  APP_ENV: "production"

# ── Secrets (SpinxBaseConfig) ─────────────────────────────────────────
# secretsFile: a .env-format file whose values are NEVER logged or written
# to any generated config in plain text.  Loaded by EnvironmentParser.loadSecretsFile().
# Convention: ".env.secrets", ".env.secrets.dev", ".env.secrets.prod"
# Add the file to .gitignore to prevent it being committed.
secretsFile: ".env.secrets"
```

### Provider-specific classes that extend SpinxBaseConfig

#### KamalConfig (VPS via Kamal)

Adds the Kamal-specific fields on top of the base:

```yaml
# ── Kamal-specific (KamalConfig) ─────────────────────────────────────
image: "ghcr.io/myorg/myapp"    # Docker image to push/pull; defaults to serviceName

servers:
  - "10.0.0.1"                  # SSH-reachable VPS IPs

registry:
  server: "ghcr.io"             # empty = Docker Hub
  username: "myorg"
  passwordEnvVar: "KAMAL_REGISTRY_PASSWORD"   # env-var name holding the password

sshUser: "deploy"               # default: "root"

# App-level secret key names passed to Kamal subprocess env at deploy time
secrets:
  - DATABASE_URL
  - POSTGRES_PASSWORD

# Accessory containers Kamal manages alongside the app
accessories:
  postgres:
    image: "postgres:16"
    host: "10.0.0.1"
    port: 5432
    secrets:
      - POSTGRES_PASSWORD
    env:
      POSTGRES_USER: "myapp"
      POSTGRES_DB: "myapp_prod"
    volumes:
      - "/var/lib/postgresql/data:/var/lib/postgresql/data"
  redis:
    image: "redis:7.2-alpine"
    host: "10.0.0.1"
    port: 6379
    volumes:
      - "/var/lib/redis:/data"
```

#### FargateConfig (AWS ECS Fargate)

```yaml
# ── AWS-specific (FargateConfig) ─────────────────────────────────────
region: "us-east-1"
cpu: 512
memory: 1024
desiredCount: 2
healthCheckPath: "/api/health"
healthCheckIntervalSeconds: 30
deploymentTimeoutMinutes: 10
enableHttps: true
```

#### GcpCloudRunConfig (GCP Cloud Run)

```yaml
# ── GCP-specific (GcpCloudRunConfig) ─────────────────────────────────
projectId: "my-gcp-project"
region: "us-central1"           # default: "us-central1"
cpu: "1"
memory: "512Mi"
minInstances: 0
maxInstances: 10
concurrency: 80
timeout: 300
allowUnauthenticated: true
```

#### AzureContainerAppsConfig (Azure Container Apps)

```yaml
# ── Azure-specific (AzureContainerAppsConfig) ─────────────────────────
subscriptionId: "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"
resourceGroupName: "myapp-rg"   # defaults to "<serviceName>-rg"
location: "East US"
cpu: "0.25"
memory: "0.5Gi"
minReplicas: 0
maxReplicas: 10
healthCheckPath: "/api/health"
healthCheckIntervalSeconds: 30
deploymentTimeoutMinutes: 10
```

---

## 2. One config file per provider — `.spinxconfig/`

Instead of one monolithic config, **each provider gets its own file** stored
inside a `.spinxconfig/` directory at the project root. Spinx discovers every
file in that directory automatically — no `-c` flag is needed on any command.

```
your-project/                        ← everything here is safe to commit
├── .spinxconfig/
│   ├── config.kamal.yaml            ← KamalConfig fields
│   ├── config.aws.yaml              ← FargateConfig fields
│   ├── config.gcp.yaml              ← GcpCloudRunConfig fields
│   ├── config.azure.yaml            ← AzureContainerAppsConfig fields
│   └── config.k8s.yaml             ← (proposed) Kubernetes
│
├── application.env                  ← non-secret base env vars  (commit ✅)
├── application.dev.env              ← dev overrides             (commit ✅)
├── application.qa.env               ← qa overrides              (commit ✅)
└── application.prod.env             ← prod overrides            (commit ✅)

# Secrets live outside the project — SecretResolver reads them at runtime:
# ~/config/spinx/secrets/secrets.<env>.json   (never committed ❌)
```

Core commands — the same regardless of which providers you use:

```bash
spinx setup             # scan .spinxconfig/, prompt for missing info, generate files
spinx deploy --env prod # deploy using every config found in .spinxconfig/
spinx logs              # stream logs from the running service
spinx remove            # tear down the deployment
```

### The shared base section — identical in every file

Every provider config file opens with the **same shared base** (all
`SpinxBaseConfig` fields). Only the provider-specific section at the bottom
differs:

```yaml
# ── Shared base — same in every .spinxconfig/ file ───────────────────
serviceName: "myapp"
dockerfilePath: "Dockerfile"
containerPort: 3000
environmentFile: "application.env"

# Optional inline non-secret env vars
environmentVariables:
  APP_ENV: "production"

# Accessories — stateful services the app depends on.
# Same block in every provider file — Spinx decides how to handle each:
#   Kamal  → run as sidecar containers on the VPS
#   Cloud  → env vars point at managed services (RDS, ElastiCache, etc.)
#   K8s    → generate Deployment + Service + PVC manifests (proposed)
accessories:
  postgres:
    image: "postgres:16"
    port: 5432
    secrets:
      - POSTGRES_PASSWORD
      - DATABASE_URL
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
    env:
      MAXMEMORY_POLICY: "noeviction"
    volumes:
      - "/var/lib/queue:/data"

# ── Provider-specific section below ──────────────────────────────────
```

> **The accessories block is copied as-is into every provider file.** Spinx
> reads the `accessories` block and handles it differently per target
> (containers on Kamal, managed services on cloud, K8s manifests in future).

---

## 3. Provider-specific fields (what differs per file)

Below the shared base, each file adds its small provider-specific section.

### `config.kamal.yaml`

```yaml
# ... shared base above ...

image: "ghcr.io/myorg/myapp"

servers:
  - "10.0.0.1"

registry:
  server: "ghcr.io"
  username: "myorg"
  passwordEnvVar: "KAMAL_REGISTRY_PASSWORD"

sshUser: "deploy"

# App-level secret key names (values injected from SecretResolver at deploy time)
secrets:
  - DATABASE_URL
  - POSTGRES_PASSWORD
  - REDIS_PASSWORD
```

### `config.aws.yaml`

```yaml
# ... shared base above ...

region: "us-east-1"
cpu: 512
memory: 1024
desiredCount: 2
healthCheckPath: "/api/health"
healthCheckIntervalSeconds: 30
deploymentTimeoutMinutes: 10
enableHttps: true
```

### `config.gcp.yaml`

```yaml
# ... shared base above ...

projectId: "my-gcp-project"
region: "us-central1"
cpu: "1"
memory: "512Mi"
minInstances: 0
maxInstances: 10
concurrency: 80
timeout: 300
allowUnauthenticated: true
```

### `config.azure.yaml`

```yaml
# ... shared base above ...

subscriptionId: "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"
resourceGroupName: "myapp-rg"
location: "East US"
cpu: "0.25"
memory: "0.5Gi"
minReplicas: 0
maxReplicas: 10
healthCheckPath: "/api/health"
deploymentTimeoutMinutes: 10
```

### `config.k8s.yaml` (proposed)

```yaml
# ... shared base above ...

namespace: "production"
storageClass: "standard"
ingressClass: "nginx"
```

---

## 4. Accessories — databases, Redis, queues

The `accessories` block (part of `KamalConfig`) uses a map of named entries,
each following the `AccessoryConfig` structure:

```yaml
accessories:
  <name>:
    image: "<docker-image>"     # container image; informational on cloud providers
    host: "<ip-or-hostname>"    # Kamal: where to run the accessory; omit on cloud
    port: <number>
    secrets:                    # key names; values resolved by SecretResolver (never logged)
      - KEY_NAME
    env:                        # non-secret env vars for this accessory
      KEY: value
    volumes:                    # host:container path mappings (Kamal / K8s)
      - "/host/path:/container/path"
```

### Common accessory types

| Name | Image | Port | Notes |
|------|-------|------|-------|
| `postgres` | `postgres:16` | 5432 | Pass `POSTGRES_PASSWORD` + `DATABASE_URL` via `secrets:` |
| `mysql` | `mysql:8` | 3306 | Pass `MYSQL_ROOT_PASSWORD` via `secrets:` |
| `redis` | `redis:7.2-alpine` | 6379 | Cache / session store |
| `queue` | `redis:7.2-alpine` | 6380 | Isolated Redis for jobs; set `MAXMEMORY_POLICY: noeviction` |
| `rabbitmq` | `rabbitmq:3-management` | 5672 | AMQP message broker |
| `mongo` | `mongo:7` | 27017 | Document store |

---

## 5. Environment-specific files — dev, qa, prod

Non-secret env vars use a **base + profile override** pattern — all files are
safe to commit:

```
application.env          ← base env vars shared across all environments (commit ✅)
application.dev.env      ← dev-specific overrides, e.g. DEBUG=true       (commit ✅)
application.qa.env       ← qa-specific overrides                          (commit ✅)
application.prod.env     ← prod-specific overrides, e.g. LOG_LEVEL=warn  (commit ✅)
```

Provider config files always reference the **base** file. The profile is chosen
at deploy time with `--env`:

```yaml
# .spinxconfig/config.aws.yaml  (and every other provider)
environmentFile: "application.env"
# SecretResolver reads ~/config/spinx/secrets/secrets.<env>.json at deploy time
```

### Profile resolution at deploy time

When you run `spinx deploy --env prod`, Spinx:

1. Loads `application.env` (base non-secret env vars)
2. Merges `application.prod.env` on top — profile values win
3. Invokes SecretResolver to load prod secrets from
   `~/config/spinx/secrets/secrets.prod.json` (local) or CI env vars (pipeline)
4. Wires everything to the target platform (see §7)

```
application.env               ← base (always loaded)
      +
application.prod.env          ← profile overrides (merged on top)
      +
SecretResolver (prod secrets) ← ~/config/spinx/secrets/secrets.prod.json  [local]
                                 or CI env vars                             [CI]
      =
Final env + secrets injected into the prod deployment
```

### Example files

**`application.env`** — base values, committed:

```
APP_NAME=myapp
LOG_LEVEL=info
CACHE_TTL=300
```

**`application.prod.env`** — prod overrides, committed:

```
LOG_LEVEL=warn
CACHE_TTL=3600
```

**`application.dev.env`** — dev overrides, committed:

```
LOG_LEVEL=debug
CACHE_TTL=0
```

---

## 6. SecretResolver — secrets live in `~/config/spinx/`

The existing `secretsFile` field in `SpinxBaseConfig` points to a file inside
the project directory, which means it must be added to `.gitignore`.

**The proposed evolution**: replace `secretsFile` with a **SecretResolver** that
reads secrets from *outside* the project — so nothing needs to be added to
`.gitignore` and there is no risk of accidentally committing secrets.

### Where secrets come from

| Runtime | Secret source |
|---------|--------------|
| **Local development** | `~/config/spinx/secrets/secrets.<env>.json` |
| **CI (GitHub Actions)** | CI env vars set as repository secrets; SecretResolver auto-detects the CI environment |
| **Future: cloud vault** | `secretsVault:` field → AWS Secrets Manager / GCP Secret Manager / Azure Key Vault |

### Local development — `~/config/spinx/`

```
~/config/spinx/
├── secrets/
│   ├── secrets.dev.json    ← dev secrets  (your local machine only ❌ never in repo)
│   ├── secrets.qa.json     ← qa secrets   (your local machine only ❌ never in repo)
│   └── secrets.prod.json   ← prod secrets (your local machine only ❌ never in repo)
└── kamal/
    └── <project-name>/
        └── deploy.yml      ← generated Kamal config (regenerated on each deploy)
```

Secrets are **outside the project repository** by design. SecretResolver finds
them by convention — no `.gitignore` entries needed.

`spinx setup` creates the skeleton JSON files on first run:

```json
// ~/config/spinx/secrets/secrets.prod.json  — fill in real values
{
  "DATABASE_URL": "CHANGE_ME",
  "POSTGRES_PASSWORD": "CHANGE_ME",
  "REDIS_PASSWORD": "CHANGE_ME",
  "KAMAL_REGISTRY_PASSWORD": "CHANGE_ME"
}
```

### JSON format for secrets files

The secrets files use **JSON** (not `.env` format) so the structure is explicit
and typed:

```json
{
  "DATABASE_URL": "postgresql://myapp:s3cret@myapp.rds.amazonaws.com:5432/myapp",
  "POSTGRES_PASSWORD": "s3cret",
  "REDIS_PASSWORD": "r3dis",
  "KAMAL_REGISTRY_PASSWORD": "ghp_xxxxxxxxxxxxxxxxxxxx"
}
```

### CI pipelines — GitHub Actions

SecretResolver detects the CI environment (e.g. `CI=true` set by GitHub
Actions) and reads secret values from the runner's environment variables.
Those variables are configured as **repository secrets** in GitHub — they are
never in source control:

```yaml
# .github/workflows/deploy.yml  — excerpt
- name: Deploy to prod
  env:
    DATABASE_URL: ${{ secrets.DATABASE_URL }}
    POSTGRES_PASSWORD: ${{ secrets.POSTGRES_PASSWORD }}
    KAMAL_REGISTRY_PASSWORD: ${{ secrets.KAMAL_REGISTRY_PASSWORD }}
  run: spinx deploy --env prod
```

SecretResolver reads those env vars, optionally writes a short-lived temp file
for any subprocess (e.g. Kamal), and deletes it immediately after the deploy.
No secrets are ever persisted to the runner disk.

### Cloud secret vaults (proposed)

A future `secretsVault:` field in the provider config would let SecretResolver
pull secrets from a cloud-native store at deploy time:

```yaml
# Proposed — not yet implemented
secretsVault:
  provider: "aws-secrets-manager"   # or gcp-secret-manager / azure-key-vault
  secretId: "myapp/prod"
  region: "us-east-1"
```

---

## 7. Auto-wiring — how Spinx places env and secrets per provider

`environmentFile` is declared once. When `--env <profile>` is given, Spinx
merges the profile file on top of the base before wiring. Secrets come from
SecretResolver (§6). The merged result is placed wherever the target platform
expects it:

| Platform | `environmentFile` + profile | Secrets (SecretResolver) |
|----------|-----------------------------|--------------------------|
| **Kamal** | Keys in `deploy.yml` `env.clear:` | Key *names* in `env.secret:`; values injected into Kamal subprocess env |
| **AWS Fargate** | Plain env vars on ECS task definition | ECS secret env vars — never in task-definition JSON in plain text |
| **GCP Cloud Run** | Env vars on the Cloud Run revision | Secret env vars on the revision |
| **Azure Container Apps** | Env vars on the container app | Secret env vars on the container app |
| **Kubernetes** *(future)* | `ConfigMap` mounted via `envFrom` | `Secret` mounted via `envFrom` |

The same auto-wiring applies to accessory `secrets:` entries — Spinx resolves
each key via SecretResolver and passes it to the accessory container (Kamal)
or makes it available as a secret env var (cloud).

---

## 8. Kamal (VPS) — accessories run as containers

On Kamal every `accessories` entry becomes a **sidecar container** managed by
Kamal on the VPS alongside the main app.

```bash
spinx setup             # generates .spinxconfig/config.kamal.yaml + env/secret files
spinx deploy --env prod # build → push → deploy app + accessories via Kamal
spinx logs              # stream app logs
spinx remove            # tear down the deployment
```

Spinx reads `.spinxconfig/config.kamal.yaml`, generates a `deploy.yml` into
`~/config/spinx/kamal/<project>/deploy.yml`, and invokes Kamal from there.
The generated `deploy.yml` **never lives in the project directory**.

The generated `deploy.yml` (from `KamalConfig` fields):

```yaml
# Generated by Spinx — ~/config/spinx/kamal/<project>/deploy.yml
# Do not edit manually; re-run `spinx setup` to regenerate.
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

Secret values are **never** written to `deploy.yml`. SecretResolver injects
the actual values into the Kamal subprocess environment at deploy time.

---

## 9. Cloud providers — accessories map to managed services

On cloud platforms you use managed services (RDS, Cloud SQL, ElastiCache, etc.)
instead of running database containers. The `accessories` block in
`config.aws.yaml` / `config.gcp.yaml` / `config.azure.yaml` is **identical**
to the Kamal version — the only difference is the connection string value in
`~/config/spinx/secrets/secrets.prod.json`.

### Same accessories block, different secret values

```
.spinxconfig/config.kamal.yaml  ─┐
.spinxconfig/config.aws.yaml    ─┤  accessories block is identical
.spinxconfig/config.gcp.yaml    ─┤  environmentFile points to the same base files
.spinxconfig/config.azure.yaml  ─┘

~/config/spinx/secrets/secrets.prod.json  (Kamal):
  "DATABASE_URL": "postgresql://myapp:pass@10.0.0.1:5432/myapp_prod"

~/config/spinx/secrets/secrets.prod.json  (AWS):
  "DATABASE_URL": "postgresql://myapp:pass@myapp.xyz.rds.amazonaws.com:5432/myapp_prod"

~/config/spinx/secrets/secrets.prod.json  (GCP):
  "DATABASE_URL": "postgresql://myapp:pass@/myapp?host=/cloudsql/proj:region:instance"

~/config/spinx/secrets/secrets.prod.json  (Azure):
  "DATABASE_URL": "postgresql://myapp:pass@myapp-db.postgres.database.azure.com:5432/myapp"
```

The app code and provider config files are **identical** across providers.
Only the secret values in the local JSON file change.

### Accessory to managed service mapping

| Accessory | AWS Fargate | GCP Cloud Run | Azure Container Apps |
|-----------|------------|---------------|----------------------|
| `postgres` | Amazon RDS | Cloud SQL | Azure Database for PostgreSQL |
| `redis` | Amazon ElastiCache | Cloud Memorystore | Azure Cache for Redis |
| `queue` | Amazon SQS | Cloud Pub/Sub | Azure Service Bus |

Queue services (SQS, Pub/Sub, Service Bus) are HTTP-based — pass their URLs
via `environmentVariables` or SecretResolver. AWS SQS credentials come from
the ECS task IAM role, so no secret value is needed in the config.

---

## 10. Kubernetes — where everything maps to (proposed)

`config.k8s.yaml` uses the same shared base and accessories block. Spinx would
generate Kubernetes manifests from it:

| Spinx field | Kubernetes object |
|-------------|------------------|
| `serviceName` | `Deployment` name + `Service` name |
| `containerPort` | Pod `containerPort`; `Service.spec.ports` |
| `environmentFile` (via profile) | `ConfigMap` (mounted via `envFrom`) |
| Secrets (via SecretResolver) | Kubernetes `Secret` (mounted via `envFrom`) |
| `environmentVariables` | Inline `env:` on the container spec |
| `accessories[*]` | Separate `Deployment` + `Service` + `PVC` |
| `accessories[*].secrets` | `secretKeyRef` entries in the accessory pod |
| `accessories[*].volumes` | `PersistentVolumeClaim` |
| `registry.passwordEnvVar` | `imagePullSecret` |

Generated output:

```
k8s/
├── app-deployment.yaml      ← serviceName, containerPort, env, secrets
├── app-service.yaml
├── app-configmap.yaml       ← environmentFile contents
├── app-secret.yaml          ← secrets from SecretResolver (base64)
├── postgres-deployment.yaml ← accessories.postgres
├── postgres-service.yaml
├── postgres-pvc.yaml
├── redis-deployment.yaml
├── redis-service.yaml
└── redis-pvc.yaml
```

---

## 11. `spinx setup` — interactive scaffolding with sensible defaults

Running `spinx setup` prompts for provider(s), service name, port, accessories,
environments, and provider-specific settings, then generates all files in the
right places. On subsequent runs it rescans `.spinxconfig/` and validates or
updates existing configs.

```
$ spinx setup

? Which provider(s) do you want to configure?
  ❯ ✔ kamal  (VPS / bare-metal)
    ✔ aws    (AWS Fargate / ECS)
      gcp    (GCP Cloud Run)
      azure  (Azure Container Apps)

? Service name: [myapp]
? Container port: [3000]
? Path to Dockerfile: [Dockerfile]

? Which environments do you need?
  ❯ ✔ dev
    ✔ qa
    ✔ prod

? Add PostgreSQL? (Y/n)  Y
? Add Redis cache? (Y/n)  Y
? Add message queue (separate Redis)? (Y/n)  Y

  For kamal:
? VPS IP address(es): [10.0.0.1]
? Docker image (e.g. ghcr.io/myorg/myapp): [ghcr.io/myorg/myapp]
? Registry server: [ghcr.io]
? Registry username: [myorg]
? SSH user: [deploy]

  For aws:
? AWS region: [us-east-1]
? CPU units: [512]
? Memory (MiB): [1024]

✔ Generated  .spinxconfig/config.kamal.yaml            (commit ✅)
✔ Generated  .spinxconfig/config.aws.yaml              (commit ✅)
✔ Generated  application.env                           (commit ✅)
✔ Generated  application.dev.env                       (commit ✅)
✔ Generated  application.qa.env                        (commit ✅)
✔ Generated  application.prod.env                      (commit ✅)
✔ Generated  ~/config/spinx/secrets/secrets.dev.json   (local only ❌)
✔ Generated  ~/config/spinx/secrets/secrets.qa.json    (local only ❌)
✔ Generated  ~/config/spinx/secrets/secrets.prod.json  (local only ❌)
✔ Generated  ~/config/spinx/kamal/myapp/deploy.yml     (local only ❌)
```

### What gets generated

**`.spinxconfig/config.kamal.yaml`** — all `KamalConfig` fields, fully
commented:

```yaml
# Generated by `spinx setup`

serviceName: "myapp"
dockerfilePath: "Dockerfile"
containerPort: 3000
environmentFile: "application.env"

environmentVariables:
  APP_ENV: "production"

accessories:
  postgres:
    image: "postgres:16"
    port: 5432
    secrets:
      - POSTGRES_PASSWORD
      - DATABASE_URL
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
    env:
      MAXMEMORY_POLICY: "noeviction"
    volumes:
      - "/var/lib/queue:/data"

image: "ghcr.io/myorg/myapp"
servers:
  - "10.0.0.1"
registry:
  server: "ghcr.io"
  username: "myorg"
  passwordEnvVar: "KAMAL_REGISTRY_PASSWORD"
sshUser: "deploy"
secrets:
  - DATABASE_URL
  - POSTGRES_PASSWORD
  - REDIS_PASSWORD
  - KAMAL_REGISTRY_PASSWORD
```

**`~/config/spinx/secrets/secrets.prod.json`** — one file per environment,
on your local machine (fill in real values):

```json
{
  "KAMAL_REGISTRY_PASSWORD": "CHANGE_ME",
  "POSTGRES_PASSWORD": "CHANGE_ME",
  "DATABASE_URL": "postgresql://myapp:CHANGE_ME@10.0.0.1:5432/myapp_prod",
  "REDIS_PASSWORD": "CHANGE_ME"
}
```

### File tracking

```
.spinxconfig/config.kamal.yaml         ← commit ✅
.spinxconfig/config.aws.yaml           ← commit ✅
application.env                        ← commit ✅
application.dev.env                    ← commit ✅
application.qa.env                     ← commit ✅
application.prod.env                   ← commit ✅

~/config/spinx/secrets/secrets.dev.json    ← local only ❌
~/config/spinx/secrets/secrets.qa.json     ← local only ❌
~/config/spinx/secrets/secrets.prod.json   ← local only ❌
~/config/spinx/kamal/<project>/deploy.yml  ← local only ❌
```

Secrets and generated Kamal configs never enter the project repository.
No `.gitignore` entries needed for any of them.

---

## 12. Recommended evolution path

```
Stage 1 — VPS with Kamal  (works with existing code)
────────────────────────────────────────────────────────────────────
  spinx setup  (choose kamal)
  → .spinxconfig/config.kamal.yaml (KamalConfig fields)
  → application.env + application.<env>.env
  → ~/config/spinx/secrets/secrets.<env>.json  ← SecretResolver reads here locally
  → ~/config/spinx/kamal/<project>/deploy.yml  ← generated Kamal config

  spinx deploy --env prod
  └── reads .spinxconfig/config.kamal.yaml
  └── SecretResolver → ~/config/spinx/secrets/secrets.prod.json
  └── accessories: postgres, redis, queue run as containers on VPS

Stage 2 — Cloud (managed services)
────────────────────────────────────────────────────────────────────
  spinx setup  (choose aws / gcp / azure)
  → .spinxconfig/config.aws.yaml (FargateConfig fields)
  → same application.env / application.<env>.env files
  → ~/config/spinx/secrets/secrets.<env>.json
    (only DATABASE_URL value changes — points at RDS instead of local container)

  In CI (GitHub Actions):
  └── SecretResolver reads DATABASE_URL, etc. from runner env vars
  └── no secrets files on disk — values come from CI repository secrets

Stage 3 — Cloud secret vaults  (proposed)
────────────────────────────────────────────────────────────────────
  Add secretsVault: to the provider config:
    secretsVault:
      provider: "aws-secrets-manager"
      secretId: "myapp/prod"
  └── SecretResolver fetches from AWS Secrets Manager / GCP Secret Manager /
      Azure Key Vault at deploy time (no local JSON file needed)

Stage 4 — Kubernetes  (proposed)
────────────────────────────────────────────────────────────────────
  spinx setup  (choose k8s)
  → .spinxconfig/config.k8s.yaml
  → same application.env + secrets files

  spinx deploy --env prod
  └── same accessories block → Spinx generates Deployment + Service + PVC manifests
  └── SecretResolver → Kubernetes Secret manifests
```

### What needs to change in Spinx to reach each stage

| Stage | Config change | Code change |
|-------|--------------|-------------|
| **Kamal full stack** | ✅ works today — `secretsFile` in `SpinxBaseConfig` | ✅ none |
| **Cloud managed services** | Update secret values in `~/config/spinx/secrets/` | ✅ none |
| **`.spinxconfig/` auto-discovery** | ✅ none | Extend `Runner` / `SpinxCli` — scan directory |
| **`spinx setup` interactive prompts** | ✅ none | New `SetupCommand` — prompts + file generators |
| **SecretResolver — local** | Remove `secretsFile` from project; add `~/config/spinx/secrets/` support | New `SecretResolver` — reads `~/config/spinx/secrets/secrets.<env>.json` |
| **SecretResolver — CI** | ✅ none | `SecretResolver` detects `CI=true`, reads from runner env vars |
| **`--env` profile flag** | ✅ none | Load + merge `application.<env>.env`; pass profile to `SecretResolver` |
| **`spinx remove` command** | ✅ none | Alias / rename of existing `destroy` action |
| **Kamal config in home dir** | ✅ none | Generate `deploy.yml` to `~/config/spinx/kamal/<project>/` |
| **Cloud secret vaults** | Add `secretsVault:` field | `SecretResolver` vault backend |
| **Kubernetes provider** | Add `namespace`, `storageClass`, `ingressClass` | New `KubernetesDeployer` + manifest generators |
