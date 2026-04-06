# Adapting Spinx for Stateful Services
## Databases · Redis · Queues · Secret Vaults · Kamal · Kubernetes

This guide explains how to evolve your **existing** Spinx YAML configuration to
support the most common production infrastructure needs: a relational database
(PostgreSQL), Redis (cache / sessions), a message queue, and a secret vault —
across all four Spinx deployment targets.

---

## Table of Contents

1. [How Spinx config works today](#1-how-spinx-config-works-today)
2. [Secret Management — works right now](#2-secret-management--works-right-now)
3. [Kamal (VPS) — full stack on your own server](#3-kamal-vps--full-stack-on-your-own-server)
   - 3.1 PostgreSQL
   - 3.2 Redis (cache + sessions)
   - 3.3 Messaging queue (Redis-backed)
   - 3.4 Putting it all together
4. [AWS Fargate — managed cloud services](#4-aws-fargate--managed-cloud-services)
5. [GCP Cloud Run — managed cloud services](#5-gcp-cloud-run--managed-cloud-services)
6. [Azure Container Apps — managed cloud services](#6-azure-container-apps--managed-cloud-services)
7. [Kubernetes — where everything maps to](#7-kubernetes--where-everything-maps-to)
8. [Recommended evolution path](#8-recommended-evolution-path)

---

## 1. How Spinx config works today

Every Spinx deployment target shares the same base fields:

```yaml
serviceName: "myapp"
dockerfilePath: "Dockerfile"
environmentFile: ".env"        # plain env vars loaded from a file
containerPort: 8080
secretsFile: ".env.secrets.prod"   # sensitive values — NEVER logged
environmentVariables:          # inline env vars (visible in generated config)
  APP_ENV: "production"
```

**The `environmentVariables` map and the `.env` file are how your app learns about
every external service** (database URLs, Redis addresses, queue endpoints).
The `secretsFile` is where credentials live.

---

## 2. Secret Management — works right now

All four providers already support `secretsFile`.  You don't need a separate
secrets tool for the basics:

**.env.secrets.prod** (add to `.gitignore` — never commit this):
```
DATABASE_URL=postgres://myapp:s3cr3t@db.internal:5432/myapp_prod
REDIS_URL=redis://:cachepass@redis.internal:6379/0
QUEUE_REDIS_URL=redis://:queuepass@redis.internal:6380/0
SMTP_PASSWORD=mailpassword
```

**kamalconfig.yaml / fargateconfig.yaml / etc.:**
```yaml
secretsFile: ".env.secrets.prod"
```

What happens:

| Provider | Behaviour |
|----------|-----------|
| **Kamal** | Key *names* written to `deploy.yml` `secrets:` section; values injected into the Kamal subprocess environment so Kamal can pass them securely to containers over SSH |
| **AWS Fargate** | Values stored as secret environment variables on the ECS task — never written to task-definition JSON in plain text |
| **GCP Cloud Run** | Values passed as secret environment variables to the Cloud Run revision |
| **Azure Container Apps** | Values passed as secret environment variables to the container app revision |

> **Vault integration (future):** When you outgrow `.env.secrets`, the next step
> is a dedicated vault.  Each cloud provider has a native option:
> AWS Secrets Manager, GCP Secret Manager, Azure Key Vault.
> The Spinx config field (`secretsFile`) would gain a companion field such as
> `secretsVault:` that pulls secrets at deploy time instead of reading a local
> file — the YAML surface stays exactly the same.

---

## 3. Kamal (VPS) — full stack on your own server

Kamal's **`accessories`** block lets you run sidecar containers (PostgreSQL,
Redis, queue workers) on the same VPS alongside your app, managed by Kamal
itself.  **This already works with the current Spinx config format.**

### 3.1 PostgreSQL

```yaml
accessories:
  postgres:
    image: "postgres:16"
    host: "YOUR_VPS_IP"          # can be same host as app, or separate DB server
    port: 5432
    secrets:
      - POSTGRES_PASSWORD        # value comes from secretsFile
    env:
      POSTGRES_USER: "myapp"
      POSTGRES_DB: "myapp_prod"
    volumes:
      - "/var/lib/postgresql/data:/var/lib/postgresql/data"
```

Your app container then receives the connection string via the secret:

**.env.secrets.prod:**
```
POSTGRES_PASSWORD=strongpassword
DATABASE_URL=postgres://myapp:strongpassword@YOUR_VPS_IP:5432/myapp_prod
```

### 3.2 Redis (cache + sessions)

```yaml
accessories:
  redis:
    image: "redis:7.2-alpine"
    host: "YOUR_VPS_IP"
    port: 6379
    volumes:
      - "/var/lib/redis:/data"
```

App env var (in `environmentVariables` or `.env`):
```yaml
environmentVariables:
  REDIS_URL: "redis://YOUR_VPS_IP:6379/0"
```

### 3.3 Messaging queue (Redis-backed)

Running a **second Redis instance** dedicated to job queues (Sidekiq, BullMQ,
Celery, etc.) is the simplest option and works without any extra infrastructure:

```yaml
accessories:
  queue:
    image: "redis:7.2-alpine"
    host: "YOUR_VPS_IP"
    port: 6380             # different port to isolate queue from cache
    volumes:
      - "/var/lib/queue:/data"
    env:
      MAXMEMORY_POLICY: "noeviction"  # queues must not evict unprocessed jobs
```

App env var:
```yaml
environmentVariables:
  QUEUE_URL: "redis://YOUR_VPS_IP:6380/0"
```

> **Alternative queues:** If you prefer a dedicated queue system, Kamal can run
> RabbitMQ (`rabbitmq:3-management`, port 5672) or even a lightweight broker
> like NATS (`nats:2`) as an accessory in exactly the same way.  Change the
> image and port; your app's env var (`RABBITMQ_URL`, `NATS_URL`) changes too.

### 3.4 Putting it all together

```yaml
# examples/kamalconfig.yaml — full production VPS stack

serviceName: "myapp"
image: "ghcr.io/myorg/myapp"
dockerfilePath: "Dockerfile"
environmentFile: ".env"
containerPort: 3000
secretsFile: ".env.secrets.prod"   # DATABASE_URL, POSTGRES_PASSWORD, etc.

servers:
  - "10.0.0.1"

registry:
  server: "ghcr.io"
  username: "myorg"
  passwordEnvVar: "KAMAL_REGISTRY_PASSWORD"

sshUser: "deploy"

secrets:                    # key names only — values come from secretsFile
  - DATABASE_URL
  - POSTGRES_PASSWORD

environmentVariables:
  RAILS_ENV: "production"
  REDIS_URL: "redis://10.0.0.1:6379/0"
  QUEUE_URL: "redis://10.0.0.1:6380/0"

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

  queue:
    image: "redis:7.2-alpine"
    host: "10.0.0.1"
    port: 6380
    volumes:
      - "/var/lib/queue:/data"
    env:
      MAXMEMORY_POLICY: "noeviction"
```

**Everything above works today** with `spinx kamal setup/deploy`.

---

## 4. AWS Fargate — managed cloud services

On Fargate, stateful services run outside your task definition — you use **AWS
managed services** and pass connection details to your container via env vars
and secrets.  The Spinx config gains no new structure; it's all env vars.

### PostgreSQL → Amazon RDS (PostgreSQL)

Provision RDS outside Spinx (one-time, using the AWS console or Terraform).
Then wire it in:

```yaml
# fargateconfig.yaml

region: "us-east-1"
serviceName: "myapp"
dockerfilePath: "Dockerfile"
containerPort: 8080
secretsFile: ".env.secrets.prod"
```

**.env.secrets.prod:**
```
DATABASE_URL=postgres://myapp:password@myapp.xyz.us-east-1.rds.amazonaws.com:5432/myapp
```

### Redis → Amazon ElastiCache (Redis)

Same pattern — provision ElastiCache once, then:

**.env.secrets.prod:**
```
REDIS_URL=redis://myapp-cache.xyz.cache.amazonaws.com:6379/0
```

### Message queues → Amazon SQS

SQS is HTTP-based; no persistent connection needed.  Pass the queue URL as a
plain env var (it is not a secret):

```yaml
environmentVariables:
  JOB_QUEUE_URL: "https://sqs.us-east-1.amazonaws.com/123456789/myapp-jobs"
```

AWS credentials for SQS come from the ECS task IAM role — no secret needed in
the config.

### Secret Vault → AWS Secrets Manager (future)

```yaml
# Proposed future field — not implemented yet
secretsVault:
  provider: "aws-secrets-manager"
  secretId: "myapp/prod"
  region: "us-east-1"
```

---

## 5. GCP Cloud Run — managed cloud services

### PostgreSQL → Cloud SQL (PostgreSQL)

Cloud Run connects to Cloud SQL via the **Cloud SQL proxy** (runs as a sidecar
automatically when you set the `INSTANCE_CONNECTION_NAME` env var).

```yaml
# cloudrunconfig.yaml

projectId: "my-gcp-project"
region: "us-central1"
serviceName: "myapp"
containerPort: 8080
secretsFile: ".env.secrets.prod"
environmentVariables:
  INSTANCE_CONNECTION_NAME: "my-gcp-project:us-central1:myapp-db"
  DB_NAME: "myapp"
  DB_USER: "myapp"
```

**.env.secrets.prod:**
```
DATABASE_URL=postgres://myapp:password@/myapp?host=/cloudsql/my-gcp-project:us-central1:myapp-db
```

### Redis → Memorystore (Redis)

Memorystore is VPC-only.  Cloud Run connects via **VPC connector**
(configured once in GCP).  Then:

```yaml
environmentVariables:
  REDIS_URL: "redis://10.0.0.3:6379"
```

### Message queues → Cloud Pub/Sub

Pub/Sub is HTTP-based.  Pass the topic/subscription name as an env var:

```yaml
environmentVariables:
  PUBSUB_TOPIC: "projects/my-gcp-project/topics/myapp-jobs"
```

### Secret Vault → GCP Secret Manager (future)

```yaml
# Proposed future field — not implemented yet
secretsVault:
  provider: "gcp-secret-manager"
  projectId: "my-gcp-project"
  secretId: "myapp-prod-secrets"
```

---

## 6. Azure Container Apps — managed cloud services

### PostgreSQL → Azure Database for PostgreSQL (Flexible Server)

```yaml
# azurecontainerappsconfig.yaml

location: "East US"
serviceName: "myapp"
containerPort: 8080
secretsFile: ".env.secrets.prod"
```

**.env.secrets.prod:**
```
DATABASE_URL=postgresql://myapp@myapp-db:password@myapp-db.postgres.database.azure.com:5432/myapp
```

### Redis → Azure Cache for Redis

```yaml
environmentVariables:
  REDIS_HOST: "myapp-cache.redis.cache.windows.net"
  REDIS_PORT: "6380"
```

**.env.secrets.prod:**
```
REDIS_PASSWORD=primaryaccesskey
```

### Message queues → Azure Service Bus

```yaml
environmentVariables:
  SERVICE_BUS_NAMESPACE: "myapp-bus.servicebus.windows.net"
  SERVICE_BUS_QUEUE: "jobs"
```

**.env.secrets.prod:**
```
SERVICE_BUS_CONNECTION_STRING=Endpoint=sb://myapp-bus...
```

### Secret Vault → Azure Key Vault (future)

```yaml
# Proposed future field — not implemented yet
secretsVault:
  provider: "azure-key-vault"
  vaultName: "myapp-vault"
```

---

## 7. Kubernetes — where everything maps to

If you eventually move to Kubernetes (or want to run on a K8s cluster with
Kamal-style simplicity), the Spinx config concepts map cleanly:

| Spinx concept | Kubernetes equivalent |
|--------------|----------------------|
| `serviceName` | `Deployment` name + `Service` name |
| `containerPort` | `containerPort` in the Pod spec; `Service.spec.ports` |
| `environmentVariables` | `env:` in the container spec, or a `ConfigMap` |
| `secretsFile` / credentials | `Secret` resource; mounted as env via `envFrom` |
| Kamal `accessories` (postgres, redis) | Separate `Deployment` + `Service` + `PersistentVolumeClaim` |
| Kamal `servers` | Kubernetes nodes (managed by the cluster) |
| Kamal `registry` | `imagePullSecret` on the pod |
| Health check | `livenessProbe` / `readinessProbe` |
| Scaling (`minReplicas`/`maxReplicas`) | `HorizontalPodAutoscaler` |

### What a Kubernetes deployment for the same app would look like

```
k8s/
├── app-deployment.yaml        ← your container (mirrors Spinx config)
├── app-service.yaml           ← exposes containerPort
├── app-secrets.yaml           ← mirrors secretsFile (base64-encoded)
├── app-configmap.yaml         ← mirrors environmentVariables
│
├── postgres-deployment.yaml   ← mirrors Kamal postgres accessory
├── postgres-service.yaml
├── postgres-pvc.yaml          ← mirrors volumes
│
├── redis-deployment.yaml      ← mirrors Kamal redis accessory
├── redis-service.yaml
├── redis-pvc.yaml
│
└── queue-deployment.yaml      ← mirrors Kamal queue accessory
    queue-service.yaml
    queue-pvc.yaml
```

### App deployment (mirrors Spinx base config)

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: myapp           # ← serviceName
spec:
  replicas: 2           # ← desiredCount / minReplicas
  template:
    spec:
      containers:
        - name: myapp
          image: ghcr.io/myorg/myapp:latest
          ports:
            - containerPort: 3000   # ← containerPort
          envFrom:
            - configMapRef:
                name: myapp-config   # ← environmentVariables
            - secretRef:
                name: myapp-secrets  # ← secretsFile
```

### PostgreSQL (mirrors Kamal accessories.postgres)

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: postgres
spec:
  template:
    spec:
      containers:
        - name: postgres
          image: postgres:16
          env:
            - name: POSTGRES_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: myapp-secrets
                  key: POSTGRES_PASSWORD
          volumeMounts:
            - mountPath: /var/lib/postgresql/data
              name: postgres-data
      volumes:
        - name: postgres-data
          persistentVolumeClaim:
            claimName: postgres-pvc   # ← volumes
```

### What Spinx would need to gain for Kubernetes

To support `spinx kubernetes deploy`, the config could be extended with a new
provider section.  The **base fields stay unchanged**; only a new
`KubernetesConfig` class would add Kubernetes-specific fields:

```yaml
# Proposed — not implemented yet
namespace: "production"
storageClass: "standard"
ingressClass: "nginx"

# accessories block identical to Kamal — same YAML, different renderer
accessories:
  postgres:
    image: "postgres:16"
    port: 5432
    secrets: [POSTGRES_PASSWORD]
    env:
      POSTGRES_USER: myapp
    volumes:
      - "/var/lib/postgresql/data:/var/lib/postgresql/data"
```

The same `accessories` map that Kamal uses for sidecars would generate
`Deployment + Service + PVC` manifests under Kubernetes — one YAML to rule
both targets.

---

## 8. Recommended evolution path

```
Stage 1 — Local / small VPS  (works today)
─────────────────────────────────────────
  spinx kamal deploy
  └── accessories: postgres, redis, queue

Stage 2 — Managed cloud services  (works today via env vars + secretsFile)
──────────────────────────────────────────────────────────────────────────
  spinx aws-fargate | gcp-cloudrun | azure-container-apps deploy
  └── environmentVariables: DB URL, Redis URL, Queue URL
  └── secretsFile: credentials

Stage 3 — Cloud secret vaults  (proposed)
──────────────────────────────────────────
  secretsVault:
    provider: aws-secrets-manager | gcp-secret-manager | azure-key-vault
    secretId: "myapp/prod"

Stage 4 — Kubernetes  (proposed)
──────────────────────────────────
  spinx kubernetes deploy
  └── accessories: same YAML → generates Deployment + Service + PVC
  └── secretsFile / secretsVault → generates Secret manifests
```

### What needs to change in the code (not the config format)

| Feature | Config changes | Code changes |
|---------|---------------|--------------|
| **PostgreSQL accessory (Kamal)** | ✅ none — works today | ✅ none |
| **Redis cache/queue (Kamal)** | ✅ none — works today | ✅ none |
| **Managed DB/Redis (cloud)** | ✅ none — use `secretsFile` + `environmentVariables` | ✅ none |
| **Cloud secret vault** | Add `secretsVault:` top-level field | New `SecretVaultLoader` per provider |
| **Kubernetes provider** | Add `namespace`, `storageClass`, `ingressClass` | New `KubernetesDeployer` + manifest generators |
| **RabbitMQ / NATS queue (Kamal)** | ✅ none — change `accessories` image + port | ✅ none |
| **AWS SQS / GCP Pub/Sub / Azure Service Bus** | ✅ none — plain `environmentVariables` | ✅ none |
