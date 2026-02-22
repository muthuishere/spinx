
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

### 2️⃣ Install via npm

**Option A – One-off usage with `npx` (no install, no admin needed):**

```bash
npx @muthuishere/spinx aws-fargate deploy -c ./examples/fargateconfig.yaml
```

**Option B – Global install (requires admin/sudo):**

```bash
npm install -g @muthuishere/spinx
spinx aws-fargate deploy -c ./examples/fargateconfig.yaml
```

---

### 3️⃣ Standalone install (no npm, no admin required)

Download the pre-built zip from [GitHub Releases](https://github.com/muthuishere/spinx/releases), extract it, and add the folder to your `PATH`:

**Unix / macOS:**
```bash
curl -LO https://github.com/muthuishere/spinx/releases/latest/download/spinx.zip
unzip spinx.zip -d ~/.spinx
# Add to ~/.bashrc or ~/.zshrc:
export PATH="$PATH:$HOME/.spinx"
```

**Windows:**
```bat
:: Download spinx.zip from GitHub Releases, then:
mkdir %USERPROFILE%\.spinx
tar -xf spinx.zip -C %USERPROFILE%\.spinx
:: Add %USERPROFILE%\.spinx to user PATH via System Settings → Environment Variables
```

Open a new terminal and run:

```bash
spinx --version
```

---

### 4️⃣ Build from source and install locally

```bash
git clone https://github.com/muthuishere/spinx.git
cd spinx
task local-install
```

This builds the fat JAR and creates a `dist/` directory with `spinx.jar` and wrapper scripts. Add `dist/` to your `PATH` to use `spinx` globally.

---

### 5️⃣ Manual Build and Run (without global install)

Clone, build, and add the `dist/` folder to your PATH — **no admin/sudo required**.

**Step 1 – Clone and build:**

```bash
git clone https://github.com/muthuishere/spinx.git
cd spinx
./gradlew createLocalDist
```

This creates a `dist/` directory with three files:

| File | Platform | How it works |
|---|---|---|
| `spinx.jar` | All | Fat JAR with all dependencies bundled |
| `spinx` | Unix / macOS | Shell script — calls `java -jar spinx.jar` from the same folder |
| `spinx.cmd` | Windows | CMD script — calls `java -jar spinx.jar` from the same folder (`%~dp0` resolves to the script's own directory, so the JAR is always found automatically) |

The build prints the exact path to add — copy it from the terminal output.

**Step 2 – Add `dist/` to your PATH:**

**Unix / macOS** – add to `~/.bashrc` or `~/.zshrc`, then reload:
```bash
export PATH="$PATH:/path/to/spinx/dist"
source ~/.bashrc   # or ~/.zshrc
```

**Windows** – open **Start → Edit the system environment variables → Environment Variables**, select **Path** under *User variables*, click **Edit**, and add the full path to the `dist\` folder, e.g.:
```
C:\path\to\spinx\dist
```
Open a new Command Prompt or PowerShell window after saving.

**Step 3 – Verify:**
```bash
spinx --version
```

**Or skip PATH setup and run directly (no PATH change needed):**

Unix/macOS:
```bash
./gradlew shadowJar
java -jar build/libs/spinx-<version>-all.jar <provider> <action> -c <config.yaml>
# or
./gradlew run --args="aws-fargate deploy -c ./examples/fargateconfig.yaml"
```

Windows:
```bat
gradlew shadowJar
java -jar build\libs\spinx-<version>-all.jar <provider> <action> -c <config.yaml>
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
   git tag v0.2.0
   git push origin v0.2.0
   ```
2. Run the release task

   ```bash
   ./gradlew jreleaserFullRelease
   ```
3. Your GitHub Release page will include:

    * `spinx-0.2.0-all.jar`
    * `spinx-0.2.0.zip` (OS-friendly scripts)

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


