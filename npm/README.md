# Spinx  
**Deploy once. Run anywhere.**  
Multi-cloud deployment CLI for AWS Fargate, GCP Cloud Run, and Azure Container Apps — all with a single command.

> "Spinx is to container infrastructure what `kamal` is to vps — a simple, unified interface that helps you setup, deploy, view logs and terminate your apps across cloud environments."

---

## 📦 NPM Installation

```bash
npm install -g @muthuishere/spinx
```

After installation, you can run `spinx` from anywhere:

```bash
spinx aws-fargate deploy -c ./config/fargateconfig.yaml
spinx gcp-cloudrun setup -c ./config/cloudrunconfig.yaml
spinx azure-container-apps logs -c ./config/azureconfig.yaml
```

---

## ✨ Features

- 🟢 **Unified syntax** across AWS, Azure, and GCP  
- ⚙️ **YAML-based configuration** (simple and declarative)  
- 🔐 **Environment file support** (`.env`)  
- 🧱 **Complete lifecycle management**: setup, deploy, destroy, and logs  
- 🔒 **Secure by default**: Automatic masking of sensitive data in logs
- 📋 **Infrastructure validation**: Ensures setup is complete before deployment
- 🚀 **Optimized Docker builds** with BuildKit and multi-stage caching

---

## 🚀 Quick Start

### 1️⃣ Install Prerequisites

⚠️ **IMPORTANT**: Before using Spinx, install the required CLI tools and set up cloud accounts:

**Required for all deployments:**
- **Docker** (with BuildKit support)
- **Git** (for image tagging)

**Cloud-specific tools:**
- **AWS CLI v2** (`aws`) - for AWS Fargate
- **Google Cloud CLI** (`gcloud`) - for GCP Cloud Run  
- **Azure CLI** (`az`) - for Azure Container Apps

📋 **[Complete Prerequisites Guide](https://github.com/muthuishere/spinx/blob/main/prerequisites.md)**

### 2️⃣ Create Configuration

Create a YAML configuration file for your target platform:

#### AWS Fargate Configuration
```yaml
# fargateconfig.yaml
region: "us-east-1"
serviceName: "my-service"
dockerfilePath: "./Dockerfile"
environmentFile: "./.env"
containerPort: 8080
cpu: 512
memory: 1024
desiredCount: 1
healthCheckPath: "/api/health"
enableHttps: true
```

#### GCP Cloud Run Configuration
```yaml
# cloudrunconfig.yaml
projectId: "my-gcp-project"
region: "us-central1"
serviceName: "my-service"
dockerfilePath: "./Dockerfile"
environmentFile: "./.env"
containerPort: 8080
cpu: "1"
memory: "512Mi"
minInstances: 0
maxInstances: 10
allowUnauthenticated: true
```

#### Azure Container Apps Configuration
```yaml
# azureconfig.yaml
location: "East US"
serviceName: "my-service"
dockerfilePath: "./Dockerfile"
environmentFile: "./.env"
containerPort: 8080
cpu: "0.25"
memory: "0.5Gi"
minReplicas: 0
maxReplicas: 10
```

### 3️⃣ Deploy Your Application

```bash
# Setup infrastructure (first time only)
spinx aws-fargate setup -c ./fargateconfig.yaml

# Deploy your application
spinx aws-fargate deploy -c ./fargateconfig.yaml

# View logs
spinx aws-fargate logs -c ./fargateconfig.yaml

# Clean up resources
spinx aws-fargate destroy -c ./fargateconfig.yaml
```

---

## ⚙️ Usage

```
spinx <provider> <action> -c <config-file>
```

### Providers

| Provider               | Description                               |
| ---------------------- | ----------------------------------------- |
| `aws-fargate`          | Deploy containers to AWS Fargate          |
| `gcp-cloudrun`         | Deploy containers to Google Cloud Run     |
| `azure-container-apps` | Deploy containers to Azure Container Apps |

### Actions

| Action    | Description                            |
| --------- | -------------------------------------- |
| `setup`   | Initialize cloud infrastructure        |
| `deploy`  | Deploy your containerized application  |
| `destroy` | Clean up all cloud resources          |
| `logs`    | Stream logs from deployed service      |

---

## 🔐 Security Features

Spinx includes several security enhancements:

- **🔒 Sensitive data masking**: Passwords, API keys, and tokens are automatically masked in logs
- **✅ Infrastructure validation**: Verifies setup is complete before deployment
- **🛡️ Secure command logging**: Docker login and other sensitive commands are logged securely
- **🔍 Pattern detection**: Automatically detects and masks various types of secrets

Example of secure logging:
```
🔑 FIREBASE_API_KEY=AIzaSyBw0epb7XpDetB-...
🔑 DATABASE_PASSWORD=mypa***word
  Password: KXsH***Jvc9
```

---

## 🌟 What Spinx Automatically Handles

### AWS Fargate
✅ **Automatically created:**
- ECR repositories
- VPC and networking (if none exist)
- IAM roles and policies
- ECS clusters with Fargate
- Application Load Balancers
- Security groups
- CloudWatch log groups

### GCP Cloud Run
✅ **Automatically created:**
- Artifact Registry repositories
- Enables required APIs
- Configures Docker authentication
- Creates Cloud Run services

### Azure Container Apps
✅ **Automatically created:**
- Resource groups
- Azure Container Registry
- Log Analytics workspaces
- Container Apps environments
- Registers resource providers

---

## 🛠️ Environment Variables

Spinx supports environment variables through:

1. **`.env` files** (dotenv format)
2. **YAML configuration** (`environmentVariables` section)
3. **System environment variables**

Environment variables are loaded with the following precedence:
1. YAML configuration (highest priority)
2. .env file
3. System environment variables (filtered out by default)

---

## 🔧 Advanced Configuration

### Docker Build Optimization

Spinx uses optimized Docker builds with:
- **BuildKit** for faster builds
- **Multi-stage caching** for layer reuse
- **Platform targeting** (`linux/amd64`)
- **Buildx** with custom builders for enhanced performance

### HTTPS Support

- **AWS Fargate**: Configurable HTTPS with custom domains and ACM certificates
- **GCP Cloud Run**: Automatic HTTPS with managed certificates
- **Azure Container Apps**: Built-in HTTPS support

### Health Checks

Configure health checks for your services:
```yaml
healthCheckPath: "/api/health"
healthCheckIntervalSeconds: 30
healthCheckTimeoutSeconds: 5
healthCheckRetries: 3
```

---

## 📋 Troubleshooting

### Common Issues

1. **Authentication errors**: Ensure you're logged in to the respective cloud CLI
   ```bash
   aws configure  # or aws sso login
   gcloud auth login
   az login
   ```

2. **Docker build failures**: Ensure Docker daemon is running and BuildKit is enabled
   ```bash
   export DOCKER_BUILDKIT=1
   ```

3. **Permission errors**: Check that your cloud account has the necessary permissions
   - AWS: ECS, ECR, VPC, IAM, ELB permissions
   - GCP: Cloud Run Admin, Artifact Registry Admin
   - Azure: Contributor role or specific Container Apps permissions

4. **Infrastructure validation failures**: Run setup before deploy
   ```bash
   spinx <provider> setup -c <config-file>
   ```

### Getting Help

- 📖 [Complete Prerequisites](https://github.com/muthuishere/spinx/blob/main/prerequisites.md)
- 🐛 [Report Issues](https://github.com/muthuishere/spinx/issues)
- 📚 [Full Documentation](https://github.com/muthuishere/spinx)

---

## 🧠 Philosophy

> "The best software is written to solve problems the author actually has." — DHH

Cloud deployment shouldn't feel like vendor lock-in. Spinx unifies the messy realities of AWS, Azure, and GCP into one human-friendly command.

---

## 🧑‍💻 Author

**Muthukumaran Navaneethakrishnan**  
[GitHub](https://github.com/muthuishere) • [LinkedIn](https://linkedin.com/in/muthuishere) • [Medium](https://medium.com/@muthuishere)

---

## 🪪 License

[Apache 2.0](https://github.com/muthuishere/spinx/blob/main/LICENSE)

---

## 🔗 Links

- 📦 [NPM Package](https://www.npmjs.com/package/@muthuishere/spinx)
- 🐙 [GitHub Repository](https://github.com/muthuishere/spinx)
- 📋 [Prerequisites Guide](https://github.com/muthuishere/spinx/blob/main/prerequisites.md)
- 🐛 [Issue Tracker](https://github.com/muthuishere/spinx/issues)