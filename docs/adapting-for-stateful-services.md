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
4. [`spinx setup` — interactive scaffolding with sensible defaults](#4-spinx-setup--interactive-scaffolding-with-sensible-defaults)
5. [Environment-specific files — dev, qa, prod](#5-environment-specific-files--dev-qa-prod)
6. [Accessories — databases, Redis, queues](#6-accessories--databases-redis-queues)
7. [Auto-wiring — how Spinx places env and secrets per provider](#7-auto-wiring--how-spinx-places-env-and-secrets-per-provider)
8. [Kamal (VPS) — accessories run as containers](#8-kamal-vps--accessories-run-as-containers)
9. [Cloud providers — accessories map to managed services](#9-cloud-providers--accessories-map-to-managed-services)
10. [Kubernetes — where everything maps to (proposed)](#10-kubernetes--where-everything-maps-to-proposed)
11. [SecretResolver — how secrets are loaded](#11-secretresolver--how-secrets-are-loaded)
12. [Recommended evolution path](#12-recommended-evolution-path)

---

## 1. One config file per provider

Each provider has its **own config file**, stored in the `.spinxconfig/`
directory. Spinx discovers all files in that directory automatically — you never
need to specify a config path on the command line.

The files share an identical base structure (app fields, accessories,
environment, secrets) and differ only in the small set of provider-specific
deployment fields.

```
your-project/                    ← everything here is safe to commit
├── .spinxconfig/
│   ├── config.kamal.yaml        ← VPS / bare-metal via Kamal
│   ├── config.aws.yaml          ← AWS Fargate (ECS)
│   ├── config.gcp.yaml          ← GCP Cloud Run
│   ├── config.azure.yaml        ← Azure Container Apps
│   └── config.k8s.yaml          ← Kubernetes  (future)
│
├── application.env          ← non-secret env vars, base (commit this ✅)
├── application.dev.env      ← dev overrides, non-secret  (commit this ✅)
├── application.qa.env       ← qa overrides, non-secret   (commit this ✅)
└── application.prod.env     ← prod overrides, non-secret (commit this ✅)

# Secrets never live in the project directory.
# SecretResolver reads from one of these locations at runtime:
#
#   Local  → ~/config/spinx/secrets/secrets.<env>.json
#   CI     → auto-generated from CI env vars, deleted after the run
#   Vault  → AWS Secrets Manager / GCP Secret Manager / Azure Key Vault (future)
```

Core commands — the same regardless of which provider(s) you use:

```bash
spinx setup             # scan .spinxconfig/, generate missing files, validate
spinx deploy --env prod # deploy using all configs in .spinxconfig/
spinx logs              # stream logs from the running service
spinx remove            # tear down the deployment
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
# Spinx reads this and places the values wherever the target platform needs them.
# When --env <profile> is given, Spinx also loads the profile-specific overrides
# (e.g. application.prod.env merged over application.env).
# Secrets are never stored in the project. SecretResolver loads them at runtime
# from ~/config/spinx/secrets/secrets.<env>.json (local) or from CI env vars.
environmentFile: "application.env"

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
      - POSTGRES_PASSWORD    # key name only — value loaded by SecretResolver at deploy time
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
  passwordEnvVar: "KAMAL_REGISTRY_PASSWORD"   # key name; value loaded by SecretResolver

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

## 4. `spinx setup` — interactive scaffolding with sensible defaults

Running `spinx setup` starts an **interactive prompt** that asks a few questions
and generates provider config files in `.spinxconfig/`, plus env files in the
project and a secrets template in your user home config dir — no manual editing
of YAML required to get started. On subsequent runs it scans `.spinxconfig/`
and validates or updates existing configs.

```
$ spinx setup

? Which provider(s) do you want to configure? (space to select)
  ❯ ✔ kamal  (VPS / bare-metal)
    ✔ aws    (AWS Fargate / ECS)
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

  For aws:
? AWS region: [us-east-1]
? ECR repository URI: [123456789.dkr.ecr.us-east-1.amazonaws.com/myapp]
? ECS cluster name: [myapp-cluster]

✔ Generated  .spinxconfig/config.kamal.yaml       (commit ✅)
✔ Generated  .spinxconfig/config.aws.yaml          (commit ✅)
✔ Generated  application.env                       (commit ✅ — base env vars)
✔ Generated  application.dev.env                   (commit ✅ — dev overrides)
✔ Generated  application.qa.env                    (commit ✅ — qa overrides)
✔ Generated  application.prod.env                  (commit ✅ — prod overrides)
✔ Generated  ~/config/spinx/secrets/secrets.dev.json    (local only — never commit)
✔ Generated  ~/config/spinx/secrets/secrets.qa.json     (local only — never commit)
✔ Generated  ~/config/spinx/secrets/secrets.prod.json   (local only — never commit)
```

Secrets are written to your **user home config directory**, not the project.
They are never in the repository — there is nothing to add to `.gitignore`.

After running `spinx setup`, the core workflow is:

```bash
spinx setup              # (re)scan .spinxconfig/, validate, generate missing files
spinx deploy --env prod  # deploy using every config found in .spinxconfig/
spinx logs               # stream logs from the running service
spinx remove             # tear down the deployment
```

### What gets generated

**`.spinxconfig/config.kamal.yaml`** — fully commented, ready to deploy:

```yaml
# Generated by `spinx setup` — edit as needed

serviceName: "myapp"
dockerfilePath: "Dockerfile"
containerPort: 3000
environmentFile: "application.env"
# Secrets are resolved at runtime by SecretResolver (see §11).
# No secretsFile needed in the project.

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

**`~/config/spinx/secrets/secrets.dev.json`** — real dev secrets, in your user
home directory (one file per environment, never in the project):

```json
{
  "KAMAL_REGISTRY_PASSWORD": "CHANGE_ME",
  "POSTGRES_PASSWORD": "CHANGE_ME",
  "DATABASE_URL": "postgresql://myapp:CHANGE_ME@10.0.0.1:5432/myapp_dev"
}
```

**Every key the deployment needs is visible from day one.** You fill in the real
values in the JSON file and they stay on your machine — outside the project.

### File tracking

```
.spinxconfig/config.kamal.yaml    ← commit to source control ✅
application.env                   ← commit to source control ✅  (base non-secret env vars)
application.dev.env               ← commit to source control ✅  (dev overrides)
application.qa.env                ← commit to source control ✅  (qa overrides)
application.prod.env              ← commit to source control ✅  (prod overrides)

~/config/spinx/secrets/secrets.dev.json   ← local only, NEVER commit ❌
~/config/spinx/secrets/secrets.qa.json    ← local only, NEVER commit ❌
~/config/spinx/secrets/secrets.prod.json  ← local only, NEVER commit ❌
```

Secrets never enter the project directory. No `.gitignore` entries needed for
secrets — they are outside the repository by design.

---

## 5. Environment-specific files — dev, qa, prod

Real projects need different configuration values per environment (dev, qa,
prod). Spinx uses a **base + profile override** file pattern. Non-secret env
vars are split into committed files in the project; secrets are resolved
separately by SecretResolver (see §11) and never touch the project directory.

### Application env files (committed)

```
application.env          ← base env vars — shared across all environments (commit ✅)
application.dev.env      ← dev-specific overrides, e.g. DEBUG=true       (commit ✅)
application.qa.env       ← qa-specific overrides                          (commit ✅)
application.prod.env     ← prod-specific overrides, e.g. LOG_LEVEL=warn  (commit ✅)
```

### Secrets (never in project)

```
~/config/spinx/secrets/secrets.dev.json   ← dev secrets  (local machine only)
~/config/spinx/secrets/secrets.qa.json    ← qa secrets   (local machine only)
~/config/spinx/secrets/secrets.prod.json  ← prod secrets (local machine only)
```

In CI, SecretResolver reads secret values directly from CI environment
variables — no JSON file is needed on the CI runner (see §11 for details).

### How profiles resolve at deploy time

When you run `spinx deploy --env prod`, Spinx reads every config in
`.spinxconfig/` and for each one:

1. Loads `application.env` (base non-secret env vars)
2. Merges `application.prod.env` on top — values in the profile file win
3. Invokes SecretResolver to load prod secrets (`~/config/spinx/secrets/secrets.prod.json` locally, or CI env vars in a pipeline)
4. Wires everything to the target platform using the rules in §7

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

**`~/config/spinx/secrets/secrets.prod.json`** — real prod secrets, local only:

```json
{
  "DATABASE_URL": "postgresql://myapp:s3cret@myapp.rds.amazonaws.com:5432/myapp",
  "POSTGRES_PASSWORD": "s3cret",
  "REDIS_PASSWORD": "r3dis",
  "KAMAL_REGISTRY_PASSWORD": "ghp_..."
}
```

### Provider config files — declare the env file only

The provider config files always reference the **base** env file. The profile
is selected at deploy time with `--env`. Secrets are resolved automatically:

```yaml
# .spinxconfig/config.aws.yaml
environmentFile: "application.env"
# SecretResolver reads ~/config/spinx/secrets/secrets.<env>.json locally,
# or CI env vars in a pipeline — no secretsFile needed here.
```

```bash
# Deploy to dev (Spinx reads .spinxconfig/config.aws.yaml automatically)
spinx deploy --env dev
# → loads application.env + application.dev.env
# → SecretResolver reads ~/config/spinx/secrets/secrets.dev.json

# Deploy to prod
spinx deploy --env prod
# → loads application.env + application.prod.env
# → SecretResolver reads ~/config/spinx/secrets/secrets.prod.json
```

---

## 6. Accessories — databases, Redis, queues

```yaml
accessories:
  <name>:
    image: "<docker-image>"     # used on Kamal / K8s; informational on cloud
    port: <number>
    secrets:                    # key names; values loaded by SecretResolver (never logged)
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

`environmentFile` is declared once in the config. When `--env <profile>` is
provided, Spinx merges the profile-specific override file on top of the base
before wiring. Secrets are resolved separately by SecretResolver (§11) and
never written to the project. The merged result is placed wherever the target
platform expects it:

| Platform | `environmentFile` | Secrets (via SecretResolver) |
|----------|------------------|-------------------------------|
| **Kamal** | Keys added to `deploy.yml` `env.clear:` — passed to containers over SSH | Key *names* in `deploy.yml` `env.secret:` section; values injected into Kamal subprocess env (never written to files) |
| **AWS Fargate** | Added as plain environment variables on the ECS task definition | Added as ECS secret environment variables — never written to task-definition JSON in plain text |
| **GCP Cloud Run** | Added as environment variables on the Cloud Run revision | Added as secret environment variables on the revision |
| **Azure Container Apps** | Added as environment variables on the container app | Added as secret environment variables on the container app |
| **Kubernetes** *(future)* | Generated into a `ConfigMap`, mounted via `envFrom` | Generated into a Kubernetes `Secret`, mounted via `envFrom` |

The same auto-wiring applies to accessory `secrets:` entries — Spinx resolves
each key via SecretResolver and passes it to the accessory container (Kamal)
or makes it available as a secret env var on the cloud task.

---

## 8. Kamal (VPS) — accessories run as containers

On Kamal, every `accessories` entry becomes a **sidecar container** managed by
Kamal on the VPS alongside the main app. No extra infrastructure is required —
it all runs on your server.

```bash
spinx setup              # scans .spinxconfig/, generates .spinxconfig/config.kamal.yaml + env files
spinx deploy --env prod  # build, push, deploy app + accessories
spinx logs               # stream app logs
spinx remove             # tear down the deployment
```

Spinx reads `.spinxconfig/config.kamal.yaml`, generates a `deploy.yml` into
the **user home config directory** (`~/config/spinx/kamal/<project>/deploy.yml`),
and invokes Kamal from there. The generated file is never written to the project
directory — all Kamal-specific infra files are kept in the user home config dir.

The generated `deploy.yml` looks like:

```yaml
# Generated by Spinx — stored in ~/config/spinx/kamal/<project>/deploy.yml
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

Secret values are **never** written to `deploy.yml` — only the key names appear.
SecretResolver injects the actual values into the Kamal subprocess environment
at deploy time (see §11).

---

## 9. Cloud providers — accessories map to managed services

On cloud platforms you use managed services (RDS, Cloud SQL, ElastiCache, etc.)
instead of running database containers. The `accessories` block in
`.spinxconfig/config.aws.yaml` / `.spinxconfig/config.gcp.yaml` / `.spinxconfig/config.azure.yaml`
is identical to the Kamal version — the only difference is the `DATABASE_URL`
value in `~/config/spinx/secrets/secrets.prod.json`.

### Same config, different secret values

```
.spinxconfig/config.kamal.yaml  ─┐
.spinxconfig/config.aws.yaml    ─┤  accessories block is identical
.spinxconfig/config.gcp.yaml    ─┤  environmentFile points to the same base files
.spinxconfig/config.azure.yaml  ─┘

~/config/spinx/secrets/secrets.prod.json (Kamal):
  "DATABASE_URL": "postgresql://myapp:pass@10.0.0.1:5432/myapp_prod"        ← local container

~/config/spinx/secrets/secrets.prod.json (AWS):
  "DATABASE_URL": "postgresql://myapp:pass@myapp.xyz.rds.amazonaws.com:5432/myapp_prod"  ← RDS

~/config/spinx/secrets/secrets.prod.json (GCP):
  "DATABASE_URL": "postgresql://myapp:pass@/myapp?host=/cloudsql/proj:region:instance"   ← Cloud SQL

~/config/spinx/secrets/secrets.prod.json (Azure):
  "DATABASE_URL": "postgresql://myapp:pass@myapp-db.postgres.database.azure.com:5432/myapp"  ← Azure DB
```

The app code and the provider config files are **identical** across providers.
You run `spinx deploy --env prod` from the same project directory — Spinx reads
every file in `.spinxconfig/` and deploys to all configured providers. Swapping
the service endpoint only requires updating one value in the local JSON secrets
file (or a CI secret variable).

### Accessory to managed service mapping

| Accessory | AWS Fargate | GCP Cloud Run | Azure Container Apps |
|-----------|------------|---------------|----------------------|
| `postgres` | Amazon RDS | Cloud SQL | Azure Database for PostgreSQL |
| `redis` | Amazon ElastiCache | Cloud Memorystore | Azure Cache for Redis |
| `queue` | Amazon SQS* | Cloud Pub/Sub* | Azure Service Bus* |

\* Queue services (SQS, Pub/Sub, Service Bus) are HTTP-based — pass their URLs
via `environmentVariables` or SecretResolver the same way. AWS SQS credentials
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
| secrets (via SecretResolver) | Kubernetes `Secret` (mounted via `envFrom`) |
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
├── app-secret.yaml          ← secrets from SecretResolver (base64)
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

## 11. SecretResolver — how secrets are loaded

Spinx never stores secrets in the project directory. Instead, a **SecretResolver**
decides at runtime where to load secret values from, based on the environment
it is running in.

### Resolution order

```
1. CI environment  (auto-detected)
   → reads secret values from CI environment variables (GitHub Actions, GitLab CI, etc.)
   → if needed, writes a temp secrets file for use during the run, then deletes it

2. Local development  (default)
   → reads ~/config/spinx/secrets/secrets.<env>.json

3. Cloud secret vault  (proposed — future)
   → reads from AWS Secrets Manager / GCP Secret Manager / Azure Key Vault
   → configured via secretsVault: in the provider config file
```

### Local development — `~/config/spinx/`

All secrets live in the user's home config directory. The directory structure
mirrors the project's environment names:

```
~/config/spinx/
├── secrets/
│   ├── secrets.dev.json    ← dev secrets  (your local machine only)
│   ├── secrets.qa.json     ← qa secrets   (your local machine only)
│   └── secrets.prod.json   ← prod secrets (your local machine only)
└── kamal/
    └── <project-name>/
        ├── deploy.yml           ← generated Kamal config (re-generated on deploy)
        └── deploy.<env>.yml     ← per-env variant (if needed)
```

The secrets files and generated Kamal configs are **outside the project
repository**. There is nothing to add to `.gitignore` — the resolver finds
them by convention.

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

### CI pipelines — GitHub Actions

In a CI pipeline SecretResolver detects the CI environment (e.g. the
`CI=true` env var set by GitHub Actions) and reads secret values directly
from the runner's environment variables. Those variables are configured as
**repository secrets** in the GitHub repo settings — they are never in source
control:

```yaml
# .github/workflows/deploy.yml  — excerpt
- name: Deploy to prod
  env:
    DATABASE_URL: ${{ secrets.DATABASE_URL }}
    POSTGRES_PASSWORD: ${{ secrets.POSTGRES_PASSWORD }}
    KAMAL_REGISTRY_PASSWORD: ${{ secrets.KAMAL_REGISTRY_PASSWORD }}
  run: spinx deploy --env prod
```

SecretResolver reads those env vars, optionally writes a short-lived temp
file for the subprocess (e.g. Kamal), and deletes it immediately after the
deploy completes. No secrets are ever persisted to the runner's disk.

### Cloud secret vaults (proposed)

A future `secretsVault:` field in the provider config would let SecretResolver
pull secrets from a cloud-native store instead of the local JSON file:

```yaml
# Proposed — not implemented yet
secretsVault:
  provider: "aws-secrets-manager"   # or gcp-secret-manager / azure-key-vault
  secretId: "myapp/prod"
  region: "us-east-1"               # AWS only
```

This is a drop-in replacement — the rest of the config, including the
accessories block, is unchanged.

---

## 12. Recommended evolution path

```
Stage 1 — VPS with Kamal  (works today)
────────────────────────────────────────────────────────────────────
  spinx setup  (choose kamal)     → generates .spinxconfig/config.kamal.yaml +
                                    application.env + application.<env>.env +
                                    ~/config/spinx/secrets/secrets.<env>.json
  spinx deploy --env prod
  └── reads .spinxconfig/config.kamal.yaml
  └── SecretResolver reads ~/config/spinx/secrets/secrets.prod.json
  └── accessories: postgres, redis, queue run as containers on VPS
  └── generated deploy.yml stored in ~/config/spinx/kamal/<project>/

Stage 2 — Cloud (managed services)  (works today)
────────────────────────────────────────────────────────────────────
  spinx setup  (choose aws / gcp / azure)
                                  → generates .spinxconfig/config.aws.yaml +
                                    application.env + application.<env>.env +
                                    ~/config/spinx/secrets/secrets.<env>.json
  spinx deploy --env prod
  └── reads .spinxconfig/config.aws.yaml
  └── same accessories block — only DATABASE_URL value in secrets.prod.json
      changes to point at RDS / ElastiCache / Cloud SQL etc.

  In CI (GitHub Actions):
  └── SecretResolver reads DATABASE_URL, etc. from runner env vars
  └── no secrets files on disk — all values come from CI secret variables

Stage 3 — Cloud secret vaults  (proposed)
────────────────────────────────────────────────────────────────────
  Add secretsVault: to the provider config under .spinxconfig/
  └── SecretResolver fetches secrets from AWS Secrets Manager / GCP Secret
      Manager / Azure Key Vault at deploy time (no local JSON file needed)

Stage 4 — Kubernetes  (proposed)
────────────────────────────────────────────────────────────────────
  spinx setup  (choose k8s)       → generates .spinxconfig/config.k8s.yaml +
                                    application.env + application.<env>.env +
                                    ~/config/spinx/secrets/secrets.<env>.json
  spinx deploy --env prod
  └── reads .spinxconfig/config.k8s.yaml
  └── same accessories block → Spinx generates Deployment + Service +
      PVC manifests
  └── SecretResolver / secretsVault → Spinx generates Kubernetes Secret manifests
```

### What needs to change in Spinx to reach each stage

| Stage | Config change | Code change |
|-------|--------------|-------------|
| **Kamal full stack** | ✅ works today | ✅ none |
| **Cloud managed services** | ✅ works today — update secret values in `~/config/spinx/secrets/` | ✅ none |
| **`spinx setup` with interactive prompts** | ✅ none | Extend `SetupCommand` — prompts + file generator, write to `.spinxconfig/` and `~/config/spinx/` |
| **`spinx setup` generates env + secrets per profile** | ✅ none | Part of `SetupCommand`; secrets template written to `~/config/spinx/secrets/` |
| **SecretResolver — local** | ✅ none | New `SecretResolver` — reads `~/config/spinx/secrets/secrets.<env>.json` |
| **SecretResolver — CI** | ✅ none | `SecretResolver` auto-detects CI env, reads from runner env vars |
| **`--env` profile flag on `spinx deploy`** | ✅ none | Load + merge `application.<env>.env`; pass `--env` to SecretResolver |
| **`spinx remove` command** | ✅ none | Alias / rename of existing `destroy` action |
| **Kamal config in home dir** | ✅ none | Generate `deploy.yml` to `~/config/spinx/kamal/<project>/` |
| **Cloud secret vaults** | Add `secretsVault:` field | `SecretResolver` vault backend per provider |
| **Kubernetes provider** | Add `namespace`, `storageClass`, `ingressClass` | New `KubernetesDeployer` + manifest generators |
