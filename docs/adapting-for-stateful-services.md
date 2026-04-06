# Spinx — Config Design for Stateful Services
## Databases · Redis · Queues · Secret Vaults · Kamal · Kubernetes

This document describes the **target design** for how Spinx YAML configuration
files should evolve to support stateful production infrastructure across all
deployment targets — Kamal (VPS), AWS Fargate, GCP Cloud Run, Azure Container
Apps, and future Kubernetes — keeping the format as simple and consistent as
possible.

---

## Table of Contents

1. [One config file per provider](#1-one-config-file-per-provider)
2. [The shared base — identical across every file](#2-the-shared-base--identical-across-every-file)
3. [Provider-specific fields](#3-provider-specific-fields)
4. [`spinx init` — interactive setup with sensible defaults](#4-spinx-init--interactive-setup-with-sensible-defaults)
5. [Environment-specific files — dev, qa, prod](#5-environment-specific-files--dev-qa-prod)
6. [Accessories — databases, Redis, queues](#6-accessories--databases-redis-queues)
7. [Auto-wiring — how Spinx places env and secrets per provider](#7-auto-wiring--how-spinx-places-env-and-secrets-per-provider)
8. [Kamal (VPS) — accessories run as containers](#8-kamal-vps--accessories-run-as-containers)
9. [Cloud providers — accessories map to managed services](#9-cloud-providers--accessories-map-to-managed-services)
10. [Kubernetes — where everything maps to (proposed)](#10-kubernetes--where-everything-maps-to-proposed)
11. [Secret vaults (proposed)](#11-secret-vaults-proposed)
12. [Recommended evolution path](#12-recommended-evolution-path)

---

## 1. One config file per provider

Each provider has its **own config file**. The files share an identical base
structure (app fields, accessories, environment, secrets) and differ only in the
small set of provider-specific deployment fields.

```
your-project/
├── config.kamal.yaml        ← VPS / bare-metal via Kamal
├── config.aws.yaml          ← AWS Fargate (ECS)
├── config.gcp.yaml          ← GCP Cloud Run
├── config.azure.yaml        ← Azure Container Apps
├── config.k8s.yaml          ← Kubernetes  (future)
│
├── application.env          ← non-secret env vars, base (commit this ✅)
├── application.dev.env      ← dev overrides, non-secret  (commit this ✅)
├── application.qa.env       ← qa overrides, non-secret   (commit this ✅)
├── application.prod.env     ← prod overrides, non-secret (commit this ✅)
│
├── secrets.env              ← secrets template with CHANGE_ME (commit this ✅)
├── secrets.dev.env          ← dev secret values  (NEVER commit ❌)
├── secrets.qa.env           ← qa secret values   (NEVER commit ❌)
└── secrets.prod.env         ← prod secret values (NEVER commit ❌)
```

Usage:

```bash
spinx kamal deploy              -c config.kamal.yaml --env prod
spinx aws-fargate deploy        -c config.aws.yaml   --env prod
spinx gcp-cloudrun deploy       -c config.gcp.yaml   --env qa
spinx azure-container-apps deploy -c config.azure.yaml --env dev
```

---

## 2. The shared base — identical across every file

These fields appear in **every** provider config file and mean exactly the same
thing regardless of where you deploy:

```yaml
# ── App identity ─────────────────────────────────────────────────────
serviceName: "myapp"
image: "ghcr.io/myorg/myapp"   # pre-built image, or omit to build from Dockerfile
dockerfilePath: "Dockerfile"
containerPort: 3000

# ── Environment & secrets ────────────────────────────────────────────
# environmentFile : base non-secret env vars — safe to commit to source control
# secretsFile     : base secrets template — safe to commit (values are CHANGE_ME)
# Spinx reads both and places them wherever the target platform needs them.
# When --env <profile> is given, Spinx also loads the profile-specific overrides
# (e.g. application.prod.env merged over application.env).
environmentFile: "application.env"
secretsFile: "secrets.env"

# Optional inline env vars (non-secret, visible in generated configs)
environmentVariables:
  APP_ENV: "production"

# ── Accessories ──────────────────────────────────────────────────────
# Stateful services the app depends on.
# Same block in every provider file — Spinx decides how to handle each one:
#   Kamal  → run as sidecar containers on the VPS
#   Cloud  → env vars point at managed services (RDS, ElastiCache, etc.)
#   K8s    → generate Deployment + Service + PVC manifests  (future)
accessories:
  postgres:
    image: "postgres:16"
    port: 5432
    secrets:
      - POSTGRES_PASSWORD    # key name only — value comes from secrets.<env>.env
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
    volumes:
      - "/var/lib/queue:/data"
    env:
      MAXMEMORY_POLICY: "noeviction"
```

**The accessories block is copied as-is into every provider file.** Only the
provider-specific section at the bottom changes.

---

## 3. Provider-specific fields

Below the shared base, each file has a small provider-specific section.

### `config.kamal.yaml`

```yaml
# ... shared base above ...

# ── Kamal / VPS ──────────────────────────────────────────────────────
servers:
  - "10.0.0.1"          # SSH-reachable VPS IPs

registry:
  server: "ghcr.io"
  username: "myorg"
  passwordEnvVar: "KAMAL_REGISTRY_PASSWORD"   # key name; value from secrets.<env>.env

sshUser: "deploy"
```

### `config.aws.yaml`

```yaml
# ... shared base above ...

# ── AWS Fargate ───────────────────────────────────────────────────────
region: "us-east-1"
cpu: 512
memory: 1024
desiredCount: 2
healthCheckPath: "/api/health"
```

### `config.gcp.yaml`

```yaml
# ... shared base above ...

# ── GCP Cloud Run ─────────────────────────────────────────────────────
projectId: "my-gcp-project"
region: "us-central1"
cpu: "1"
memory: "512Mi"
minInstances: 0
maxInstances: 10
allowUnauthenticated: true
```

### `config.azure.yaml`

```yaml
# ... shared base above ...

# ── Azure Container Apps ──────────────────────────────────────────────
location: "East US"
resourceGroup: "myapp-rg"
cpu: "0.25"
memory: "0.5Gi"
minReplicas: 0
maxReplicas: 5
```

### `config.k8s.yaml` (proposed)

```yaml
# ... shared base above ...

# ── Kubernetes ────────────────────────────────────────────────────────
namespace: "production"
storageClass: "standard"
ingressClass: "nginx"
```

---

## 4. `spinx init` — interactive setup with sensible defaults

Running `spinx init` starts an **interactive prompt** that asks a few questions
and generates the correct config file and secrets template — no manual editing
of YAML required to get started.

```
$ spinx init

? Which provider?
  ❯ kamal  (VPS / bare-metal)
    aws    (AWS Fargate / ECS)
    gcp    (GCP Cloud Run)
    azure  (Azure Container Apps)
    k8s    (Kubernetes)

? Service name: [myapp]
? Container port: [3000]
? Path to Dockerfile: [Dockerfile]

? Which environments do you need? (space to select, enter to confirm)
  ❯ ✔ dev
    ✔ qa
    ✔ prod

? Add PostgreSQL? (Y/n)  Y
? Add Redis cache? (Y/n)  Y
? Add message queue (separate Redis)? (Y/n)  Y

  For kamal:
? VPS IP address(es): [10.0.0.1]
? Registry server (leave blank for Docker Hub): [ghcr.io]
? Registry username: [myorg]
? SSH user: [deploy]

✔ Generated  config.kamal.yaml
✔ Generated  application.env          (base env vars — commit this)
✔ Generated  application.dev.env      (dev overrides — commit this)
✔ Generated  application.qa.env       (qa overrides  — commit this)
✔ Generated  application.prod.env     (prod overrides — commit this)
✔ Generated  secrets.env              (secrets template — commit this)
✔ Generated  secrets.dev.env          (fill in dev secrets — DO NOT commit)
✔ Generated  secrets.qa.env           (fill in qa secrets  — DO NOT commit)
✔ Generated  secrets.prod.env         (fill in prod secrets — DO NOT commit)
✔ Updated    .gitignore               (added secrets.*.env)
```

### What gets generated

**`config.kamal.yaml`** — fully commented, ready to deploy:

```yaml
# Generated by `spinx init` — edit as needed

serviceName: "myapp"
dockerfilePath: "Dockerfile"
containerPort: 3000
environmentFile: "application.env"
secretsFile: "secrets.env"

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
    volumes:
      - "/var/lib/queue:/data"
    env:
      MAXMEMORY_POLICY: "noeviction"

servers:
  - "10.0.0.1"
registry:
  server: "ghcr.io"
  username: "myorg"
  passwordEnvVar: "KAMAL_REGISTRY_PASSWORD"
sshUser: "deploy"
```

**`secrets.dev.env`** — template with every required key, placeholder values,
ready to fill in (one generated per environment):

```
# Generated by `spinx init` — fill in real values. NEVER commit this file.
# Add secrets.*.env to your .gitignore

# Registry
KAMAL_REGISTRY_PASSWORD=CHANGE_ME

# Database
POSTGRES_PASSWORD=CHANGE_ME
DATABASE_URL=postgresql://myapp:CHANGE_ME@10.0.0.1:5432/myapp_dev

# Redis / queue (no auth by default on private VPS; add password if needed)
# REDIS_PASSWORD=CHANGE_ME
```

**Every key the deployment needs is visible from day one.** You never have to
hunt through logs or documentation to discover missing secrets.

### File tracking

```
config.kamal.yaml      ← commit to source control ✅
application.env        ← commit to source control ✅  (base non-secret env vars)
application.dev.env    ← commit to source control ✅  (dev overrides)
application.qa.env     ← commit to source control ✅  (qa overrides)
application.prod.env   ← commit to source control ✅  (prod overrides)
secrets.env            ← commit to source control ✅  (keys only, CHANGE_ME values)
secrets.dev.env        ← NEVER commit ❌  (real dev secrets)
secrets.qa.env         ← NEVER commit ❌  (real qa secrets)
secrets.prod.env       ← NEVER commit ❌  (real prod secrets)
```

---

## 5. Environment-specific files — dev, qa, prod

Real projects need different configuration values per environment (dev, qa,
prod). Spinx uses a **base + profile override** file pattern that keeps
non-secret values in committed files and secret values in files that are never
committed.

### File naming convention

```
application.env          ← base env vars — shared across all environments
application.dev.env      ← dev-specific overrides (e.g. DEBUG=true)
application.qa.env       ← qa-specific overrides
application.prod.env     ← prod-specific overrides (e.g. LOG_LEVEL=warn)

secrets.env              ← template listing every secret key with CHANGE_ME
secrets.dev.env          ← real dev secret values  (NEVER commit)
secrets.qa.env           ← real qa secret values   (NEVER commit)
secrets.prod.env         ← real prod secret values (NEVER commit)
```

Add this to `.gitignore` to protect secret files:

```
secrets.*.env
```

### How profiles resolve at deploy time

When you run `spinx aws-fargate deploy -c config.aws.yaml --env prod`, Spinx:

1. Loads `application.env` (base non-secret env vars)
2. Merges `application.prod.env` on top — values in the profile file win
3. Loads `secrets.prod.env` (real secret values for prod)
4. Wires everything to the target platform using the rules in §7

```
application.env         ← base (always loaded)
      +
application.prod.env    ← profile overrides (merged on top)
      +
secrets.prod.env        ← profile secrets
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

**`secrets.env`** — keys template, committed (values are placeholders):

```
# Add real values to secrets.<env>.env — NEVER commit those files.
DATABASE_URL=CHANGE_ME
POSTGRES_PASSWORD=CHANGE_ME
REDIS_PASSWORD=CHANGE_ME
```

**`secrets.prod.env`** — real values, **never committed**:

```
DATABASE_URL=postgresql://myapp:s3cret@myapp.rds.amazonaws.com:5432/myapp
POSTGRES_PASSWORD=s3cret
REDIS_PASSWORD=r3dis
```

### Provider config files — declare the base files only

The provider config files always reference the **base** file names. The profile
is selected at deploy time with `--env`:

```yaml
# config.aws.yaml
environmentFile: "application.env"
secretsFile: "secrets.env"
```

```bash
# Deploy to dev
spinx aws-fargate deploy -c config.aws.yaml --env dev
# → loads application.env + application.dev.env + secrets.dev.env

# Deploy to prod
spinx aws-fargate deploy -c config.aws.yaml --env prod
# → loads application.env + application.prod.env + secrets.prod.env
```

---

## 6. Accessories — databases, Redis, queues

```yaml
accessories:
  <name>:
    image: "<docker-image>"     # used on Kamal / K8s; informational on cloud
    port: <number>
    secrets:                    # key names from secretsFile (value never logged)
      - KEY_NAME
    env:                        # non-secret env vars for this accessory
      KEY: value
    volumes:                    # host:container path mapping
      - "/host/path:/container/path"
```

### Common accessory types

| Name | Image | Port | Notes |
|------|-------|------|-------|
| `postgres` | `postgres:16` | 5432 | Pass `POSTGRES_PASSWORD` + `DATABASE_URL` via `secrets:` |
| `mysql` | `mysql:8` | 3306 | Pass `MYSQL_ROOT_PASSWORD` via `secrets:` |
| `redis` | `redis:7.2-alpine` | 6379 | Cache and session store |
| `queue` | `redis:7.2-alpine` | 6380 | Isolated Redis for jobs; set `MAXMEMORY_POLICY: noeviction` |
| `rabbitmq` | `rabbitmq:3-management` | 5672 | AMQP message broker |
| `mongo` | `mongo:7` | 27017 | Document store |

---

## 7. Auto-wiring — how Spinx places env and secrets per provider

`environmentFile` and `secretsFile` are declared **once** in the config. When
`--env <profile>` is provided, Spinx merges the profile-specific env file on
top of the base before wiring. The merged result is placed wherever the target
platform expects it — you change nothing in the config when switching providers
or environments:

| Platform | `environmentFile` | `secretsFile` |
|----------|------------------|---------------|
| **Kamal** | Keys added to `deploy.yml` `env.clear:` — passed to containers over SSH | Key *names* added to `deploy.yml` `env.secret:` section; values injected into Kamal subprocess env (never written to files) |
| **AWS Fargate** | Added as plain environment variables on the ECS task definition | Added as ECS secret environment variables — never written to task-definition JSON in plain text |
| **GCP Cloud Run** | Added as environment variables on the Cloud Run revision | Added as secret environment variables on the revision |
| **Azure Container Apps** | Added as environment variables on the container app | Added as secret environment variables on the container app |
| **Kubernetes** *(future)* | Generated into a `ConfigMap`, mounted via `envFrom` | Generated into a Kubernetes `Secret`, mounted via `envFrom` |

The same auto-wiring applies to accessory `secrets:` entries — Spinx pulls
each key from `secretsFile` and passes it to the accessory container (Kamal)
or makes it available as a secret env var on the cloud task.

---

## 8. Kamal (VPS) — accessories run as containers

On Kamal, every `accessories` entry becomes a **sidecar container** managed by
Kamal on the VPS alongside the main app. No extra infrastructure is required —
it all runs on your server.

```bash
spinx init                                              # generates config.kamal.yaml + env/secrets files
spinx kamal deploy -c config.kamal.yaml --env prod      # build, push, deploy app + accessories
spinx kamal logs   -c config.kamal.yaml                 # stream app logs
```

Spinx reads `config.kamal.yaml`, generates a `deploy.yml`, and invokes Kamal.
The generated `deploy.yml` looks like:

```yaml
# Generated deploy.yml — do not edit manually
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

Secret values are **never** written to `deploy.yml` — only the key names appear.
Actual values come from `secrets.<env>.env` at deploy time.

---

## 9. Cloud providers — accessories map to managed services

On cloud platforms you use managed services (RDS, Cloud SQL, ElastiCache, etc.)
instead of running database containers. The `accessories` block in
`config.aws.yaml` / `config.gcp.yaml` / `config.azure.yaml` is identical to
the Kamal version — the only difference is what you put in `secrets.prod.env`.

### Same config, different secret values

```
config.kamal.yaml  ─┐
config.aws.yaml    ─┤  accessories block is identical
config.gcp.yaml    ─┤  environmentFile / secretsFile point to the same base files
config.azure.yaml  ─┘

secrets.prod.env (Kamal):
  DATABASE_URL=postgresql://myapp:pass@10.0.0.1:5432/myapp_prod        ← local container

secrets.prod.env (AWS):
  DATABASE_URL=postgresql://myapp:pass@myapp.xyz.rds.amazonaws.com:5432/myapp_prod  ← RDS

secrets.prod.env (GCP):
  DATABASE_URL=postgresql://myapp:pass@/myapp?host=/cloudsql/proj:region:instance   ← Cloud SQL

secrets.prod.env (Azure):
  DATABASE_URL=postgresql://myapp:pass@myapp-db.postgres.database.azure.com:5432/myapp  ← Azure DB
```

The app code and the config files are **identical** across providers. You swap
the target by choosing a different config file; you swap the service endpoint
by changing a line in `secrets.prod.env`.

### Accessory to managed service mapping

| Accessory | AWS Fargate | GCP Cloud Run | Azure Container Apps |
|-----------|------------|---------------|----------------------|
| `postgres` | Amazon RDS | Cloud SQL | Azure Database for PostgreSQL |
| `redis` | Amazon ElastiCache | Cloud Memorystore | Azure Cache for Redis |
| `queue` | Amazon SQS* | Cloud Pub/Sub* | Azure Service Bus* |

\* Queue services (SQS, Pub/Sub, Service Bus) are HTTP-based — pass their URLs
via `environmentVariables` or `secretsFile` the same way. AWS SQS credentials
come from the ECS task IAM role (no secret needed in the config).

---

## 10. Kubernetes — where everything maps to (proposed)

`config.k8s.yaml` uses the same shared base and accessories block. Spinx would
generate Kubernetes manifests from it:

| Spinx field | Kubernetes object |
|-------------|------------------|
| `serviceName` | `Deployment` name + `Service` name |
| `containerPort` | Pod `containerPort`; `Service.spec.ports` |
| `environmentFile` | `ConfigMap` (mounted via `envFrom`) |
| `secretsFile` | `Secret` (mounted via `envFrom`) |
| `environmentVariables` | Inline `env:` on the container spec |
| `accessories[*]` | Separate `Deployment` + `Service` + `PVC` |
| `accessories[*].secrets` | `secretKeyRef` entries in the accessory pod |
| `accessories[*].volumes` | `PersistentVolumeClaim` |
| `registry` | `imagePullSecret` |

Spinx would generate:

```
k8s/
├── app-deployment.yaml      ← serviceName, containerPort, env, secrets
├── app-service.yaml
├── app-configmap.yaml       ← environmentFile contents
├── app-secret.yaml          ← secretsFile contents (base64)
├── postgres-deployment.yaml ← accessories.postgres
├── postgres-service.yaml
├── postgres-pvc.yaml        ← accessories.postgres.volumes
├── redis-deployment.yaml    ← accessories.redis
├── redis-service.yaml
├── redis-pvc.yaml
├── queue-deployment.yaml    ← accessories.queue
├── queue-service.yaml
└── queue-pvc.yaml
```

**One accessories block — three execution models:** Kamal runs them as
containers via SSH, cloud providers wire them to managed services via env vars,
Kubernetes generates manifests. You never rewrite the accessories YAML.

---

## 11. Secret vaults (proposed)

When `secrets.<env>.env` is not enough (rotation, audit trails, centralised
management), a `secretsVault:` field could pull secrets from a cloud-native
store at deploy time instead of reading a local file:

```yaml
# Proposed — not implemented yet
# Works as a drop-in replacement for secretsFile
secretsVault:
  provider: "aws-secrets-manager"   # or gcp-secret-manager / azure-key-vault
  secretId: "myapp/prod"
  region: "us-east-1"              # AWS only
```

The rest of the config — including the accessories block — is unchanged.

---

## 12. Recommended evolution path

```
Stage 1 — VPS with Kamal  (works today)
────────────────────────────────────────────────────────────────────
  spinx init                        → generates config.kamal.yaml +
                                      application.env + secrets.env +
                                      secrets.dev.env / secrets.prod.env
  spinx kamal deploy -c config.kamal.yaml --env prod
  └── accessories: postgres, redis, queue run as containers on VPS

Stage 2 — Cloud (managed services)  (works today)
────────────────────────────────────────────────────────────────────
  spinx init  (choose aws / gcp / azure)
                                    → generates config.aws.yaml +
                                      application.env + secrets.env +
                                      secrets.dev.env / secrets.prod.env
  spinx aws-fargate deploy -c config.aws.yaml --env prod
  └── same accessories block — only secrets.prod.env values change to
      point at RDS / ElastiCache / Cloud SQL etc.

Stage 3 — Cloud secret vaults  (proposed)
────────────────────────────────────────────────────────────────────
  Replace secretsFile with secretsVault: in provider config
  └── Spinx fetches secrets from AWS Secrets Manager / GCP Secret
      Manager / Azure Key Vault at deploy time

Stage 4 — Kubernetes  (proposed)
────────────────────────────────────────────────────────────────────
  spinx init  (choose k8s)          → generates config.k8s.yaml +
                                      application.env + secrets.env
  spinx kubernetes deploy -c config.k8s.yaml --env prod
  └── same accessories block → Spinx generates Deployment + Service +
      PVC manifests
  └── same secretsFile / secretsVault → Spinx generates Secret manifests
```

### What needs to change in Spinx to reach each stage

| Stage | Config change | Code change |
|-------|--------------|-------------|
| **Kamal full stack** | ✅ works today | ✅ none |
| **Cloud managed services** | ✅ works today — only `secrets.prod.env` values differ | ✅ none |
| **`spinx init` with interactive prompts** | ✅ none | New `InitCommand` — prompts + file generator |
| **`spinx init` generates env + secrets per profile** | ✅ none | Part of `InitCommand` |
| **`--env` profile flag** | ✅ none | Load + merge `application.<env>.env` and `secrets.<env>.env` at deploy time |
| **Cloud secret vaults** | Add `secretsVault:` field | New `SecretVaultLoader` per provider |
| **Kubernetes provider** | Add `namespace`, `storageClass`, `ingressClass` | New `KubernetesDeployer` + manifest generators |
