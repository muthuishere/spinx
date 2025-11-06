
# Spinx  
**Deploy once. Run anywhere.**  
Multi-cloud deployment CLI for AWS Fargate, AWS Lambda, GCP Cloud Run, and Azure Container Apps — all with a single command.

> “Spinx is to container infrastructure what `kamal` is to vps — a simple, unified interface that helps your to setup ,deploy , view logs and terminate your apps across cloud environments.”

---

## ✨ Features

- 🟢 Unified syntax across AWS, Azure, and GCP  
- ⚙️ YAML-based configuration (simple and declarative)  
- 🔐 Environment file support (`.env`)  
- 🧱 Handles setup, deploy, destroy, and logs  
- 🧩 Works on Java 21+  
- 💡 Integrates with [**LNB**](https://github.com/muthuishere/lnb) for global CLI access

---

## 📦 Installation

### 1️⃣ Install Prerequisites

⚠️ **IMPORTANT**: Before using Spinx, please review the comprehensive prerequisites:

📋 **[View Complete Prerequisites Guide](prerequisites.md)**

**Quick Summary:**
- **Java 21+** - Runtime for Spinx CLI
- **Docker** - Container building and pushing (with BuildKit support)
- **Git** - For generating unique image tags
- **Cloud CLI Tools** (depending on your target platform):
  - **Google Cloud CLI** (`gcloud`) - for GCP Cloud Run deployments
  - **AWS CLI v2** (`aws`) - for AWS Fargate deployments  
  - **Azure CLI** (`az`) - for Azure Container Apps deployments
- **Task** (for building Spinx) - Install from [taskfile.dev](https://taskfile.dev/installation/)

**Cloud Account Requirements:**
- **GCP**: Project with **billing enabled** ⚠️ (required for Cloud Run)
- **AWS**: Account with programmatic access configured
- **Azure**: Account with active subscription

---

### 2️⃣ Clone and Install Globally

```bash
git clone https://github.com/muthuishere/spinx.git
cd spinx
task local-install
```

This will:
- Build the JAR library 
- Install spinx globally via npm

Now you can run `spinx` from anywhere:

```bash
spinx aws-fargate deploy -c ./examples/fargateconfig.yaml
```

---

### 3️⃣ Manual Build and Run (without global install)

```bash
./gradlew shadowJar
java -jar build/libs/spinx-0.1.0-all.jar <provider> <action> -c <config.yaml>
```

Example:

```bash
java -jar build/libs/spinx-0.1.0-all.jar gcp-cloudrun deploy -c ./examples/cloudrunconfig.yaml
```

---

## ⚙️ Usage

```
spinx <provider> <action> -c <config-file>
```

| Provider               | Description                               |
| ---------------------- | ----------------------------------------- |
| `aws-fargate`          | Deploy containers to AWS Fargate          |
| `gcp-cloudrun`         | Deploy Docker images to Google Cloud Run  |
| `azure-container-apps` | Deploy containers to Azure Container Apps |

| Action    | Description                            |
| --------- | -------------------------------------- |
| `setup`   | Initialize resources, IAM, or projects |
| `deploy`  | Deploy service or container            |
| `destroy` | Tear down resources                    |
| `logs`    | Stream logs from deployed service      |

---

## 🧱 Example Configurations

### AWS Fargate – `examples/fargateconfig.yaml`

```yaml
region: "us-east-1"
serviceName: "todo-mcp-server"
dockerfilePath: "../Dockerfile"
environmentFile: "../.env"
containerPort: 8080
cpu: 512
memory: 1024
desiredCount: 1
healthCheckPath: "/api/health"
healthCheckIntervalSeconds: 30
deploymentTimeoutMinutes: 10
```

Run:

```bash
spinx aws-fargate deploy -c ./examples/fargateconfig.yaml
```

---

### GCP Cloud Run – `examples/cloudrunconfig.yaml`

```yaml
projectId: "myspringai-test"
region: "us-central1"
serviceName: "todoapp-cloudrun"
dockerfilePath: "Dockerfile"
environmentFile: ".env"
containerPort: 8080
cpu: "1"
memory: "512Mi"
minInstances: 0
maxInstances: 10
concurrency: 80
timeout: 300
allowUnauthenticated: true
environmentVariables:
  SPRING_PROFILES_ACTIVE: "streamable"
```

Run:

```bash
spinx gcp-cloudrun deploy -c ./examples/cloudrunconfig.yaml
```

---

### Azure Container Apps – `examples/azurecontainerappsconfig.yaml`

```yaml
location: "East US"
serviceName: "todo-mcp-server"
dockerfilePath: "../Dockerfile"
environmentFile: "../.env"
containerPort: 8080
cpu: "0.25"
memory: "0.5Gi"
minReplicas: 0
maxReplicas: 1
healthCheckPath: "/api/health"
healthCheckIntervalSeconds: 30
deploymentTimeoutMinutes: 10
```

Run:

```bash
spinx azure-container-apps deploy -c ./examples/azurecontainerappsconfig.yaml
```

---

## 🧩 Development

For local runs during development:

```bash
./gradlew run --args="aws-fargate deploy -c ./examples/fargateconfig.yaml"
```

Or run a dry run:

```bash
spinx aws-lambda deploy -c ./lambda.yaml --dry-run
```

### Optional – quick install via Gradle helper

```bash
./gradlew lnbInstall
```

---

## 🧱 Project Build Commands

| Command                          | Description               |
| -------------------------------- | ------------------------- |
| `./gradlew shadowJar`            | Build fat JAR             |
| `./gradlew installDist`          | Build OS launcher scripts |
| `./gradlew run --args="..."`     | Run directly              |
| `./gradlew lnbInstall`           | Register with LNB         |
| `./gradlew jreleaserFullRelease` | Publish GitHub release    |

---

## 🧾 Release Flow (via JReleaser)

1. Tag your release

   ```bash
   git tag v0.1.0
   git push origin v0.1.0
   ```
2. Run the release task

   ```bash
   ./gradlew jreleaserFullRelease
   ```
3. Your GitHub Release page will include:

    * `spinx-0.1.0-all.jar`
    * `spinx-0.1.0.zip` (OS-friendly scripts)

---

## 🧠 Philosophy

> “The best software is written to solve problems the author actually has.” — DHH

Cloud deployment shouldn’t feel like vendor lock-in.
Spinx unifies the messy realities of AWS, Azure, and GCP into one human-friendly command.

---

## 🧑‍💻 Author

**Muthukumaran Navaneethakrishnan**
[GitHub](https://github.com/muthuishere) • [LinkedIn](https://linkedin.com/in/muthuishere) • [Medium](https://medium.com/@muthuishere)

---

## 🪪 License

[Apache 2.0](LICENSE)

---

## ❤️ Credits

* [Picocli](https://picocli.info) – rock-solid CLI foundation
* [JReleaser](https://jreleaser.org) – elegant multi-channel release manager
* [LNB](https://github.com/muthuishere/lnb) – cross-platform alias magic


