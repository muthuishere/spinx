# Prerequisites for Spinx

This guide covers the prerequisites for using Spinx via NPM installation.

## 📦 Installation

```bash
npm install -g @muthuishere/spinx
```

---

## 🛠️ Required CLI Tools

### Core Tools (Required for All Deployments)

#### Docker
- **Purpose**: Building and pushing container images
- **Required Version**: Latest stable version with BuildKit support
- **Installation**: 
  - macOS: `brew install --cask docker`
  - Linux: Follow [Docker installation guide](https://docs.docker.com/engine/install/)
  - Windows: Download [Docker Desktop](https://www.docker.com/products/docker-desktop)
- **Verification**: `docker --version && docker buildx version`
- **Configuration**: 
  ```bash
  export DOCKER_BUILDKIT=1
  export DOCKER_CLI_EXPERIMENTAL=enabled
  ```

#### Git
- **Purpose**: Generating unique image tags based on commit hashes
- **Installation**: 
  - macOS: `brew install git` (or pre-installed)
  - Linux: `sudo apt install git` (Ubuntu) / `sudo yum install git` (RHEL)
  - Windows: [Git for Windows](https://git-scm.com/download/win)
- **Verification**: `git --version`

---

## ☁️ Cloud Provider CLI Tools

### Google Cloud Platform (GCP) - for `gcp-cloudrun`

#### Google Cloud CLI (gcloud)
- **Installation**: 
  - macOS: `brew install --cask google-cloud-sdk`
  - Other platforms: [Official installation guide](https://cloud.google.com/sdk/docs/install)
- **Verification**: `gcloud --version`
- **Authentication**:
  ```bash
  gcloud auth login
  gcloud auth application-default login
  gcloud config set project YOUR_PROJECT_ID
  ```

### Amazon Web Services (AWS) - for `aws-fargate`

#### AWS CLI v2
- **Installation**:
  - macOS: `brew install awscli`
  - Other platforms: [AWS CLI installation guide](https://docs.aws.amazon.com/cli/latest/userguide/getting-started-install.html)
- **Verification**: `aws --version` (should show v2.x.x)
- **Authentication**:
  ```bash
  aws configure
  # OR
  export AWS_ACCESS_KEY_ID=your_access_key
  export AWS_SECRET_ACCESS_KEY=your_secret_key
  export AWS_DEFAULT_REGION=your_region
  ```

### Microsoft Azure - for `azure-container-apps`

#### Azure CLI (az)
- **Installation**:
  - macOS: `brew install azure-cli`
  - Linux: [Azure CLI installation guide](https://docs.microsoft.com/en-us/cli/azure/install-azure-cli-linux)
  - Windows: [Azure CLI installer](https://docs.microsoft.com/en-us/cli/azure/install-azure-cli-windows)
- **Verification**: `az --version`
- **Authentication**:
  ```bash
  az login
  az account set --subscription "your-subscription-id"  # optional
  ```

---

## ☁️ Cloud Account Requirements

### Google Cloud Platform (GCP)

#### Essential Requirements
1. **GCP Project**: Create at [Google Cloud Console](https://console.cloud.google.com/)
2. **⚠️ Billing Enabled**: **REQUIRED** - Cloud Run needs active billing
3. **Project ID**: Note your Project ID (different from project name)

#### Required Permissions
- `Cloud Run Admin`
- `Artifact Registry Admin` 
- `Service Usage Admin`
- `Project Editor` (or equivalent permissions)

✅ **Spinx automatically handles**: API enabling, repository creation, Docker authentication

### Amazon Web Services (AWS)

#### Essential Requirements
1. **AWS Account**: Create at [AWS Console](https://aws.amazon.com/)
2. **Programmatic Access**: Create IAM user with API access

#### Required Permissions
- `AmazonECS_FullAccess`
- `AmazonEC2ContainerRegistryFullAccess`
- `AmazonVPCFullAccess`
- `ElasticLoadBalancingFullAccess`
- `IAMFullAccess`
- `CloudWatchLogsFullAccess`

✅ **Spinx automatically handles**: ECR repos, VPC/networking, ECS clusters, load balancers, IAM roles

### Microsoft Azure

#### Essential Requirements
1. **Azure Account**: Create at [Azure Portal](https://portal.azure.com/)
2. **Active Subscription**: Required for resource creation

#### Required Permissions
- `Contributor` role (recommended)
- OR specific roles: `Container Apps Contributor`, `Azure Container Registry Contributor`, `Log Analytics Contributor`

✅ **Spinx automatically handles**: Resource groups, ACR registries, workspaces, environments, resource provider registration

---

## 🚀 Quick Start Verification

Run these commands to verify your setup:

```bash
# Verify Spinx installation
spinx --version

# Verify core tools
docker --version
git --version

# Verify cloud CLI (choose your platform)
gcloud --version && gcloud auth list          # GCP
aws --version && aws sts get-caller-identity   # AWS
az --version && az account show                # Azure
```

All commands should execute successfully before proceeding with deployments.

---

## 📁 Project Structure

### Required Configuration File

Create a YAML configuration file for your chosen platform:

#### AWS Fargate Example
```yaml
# config/fargateconfig.yaml
region: "us-east-1"
serviceName: "my-app"
dockerfilePath: "./Dockerfile"
environmentFile: "./.env"
containerPort: 8080
cpu: 512
memory: 1024
healthCheckPath: "/health"
```

#### GCP Cloud Run Example
```yaml
# config/cloudrunconfig.yaml
projectId: "my-gcp-project"
region: "us-central1"
serviceName: "my-app"
dockerfilePath: "./Dockerfile"
environmentFile: "./.env"
containerPort: 8080
allowUnauthenticated: true
```

#### Azure Container Apps Example
```yaml
# config/azureconfig.yaml
location: "East US"
serviceName: "my-app"
dockerfilePath: "./Dockerfile"
environmentFile: "./.env"
containerPort: 8080
cpu: "0.25"
memory: "0.5Gi"
```

### Optional Files
- **`.env`**: Environment variables (dotenv format)
- **`Dockerfile`**: Container definition

---

## ⚡ First Deployment

```bash
# 1. Setup infrastructure (first time only)
spinx aws-fargate setup -c ./config/fargateconfig.yaml

# 2. Deploy your application
spinx aws-fargate deploy -c ./config/fargateconfig.yaml

# 3. View logs
spinx aws-fargate logs -c ./config/fargateconfig.yaml

# 4. Clean up (when done)
spinx aws-fargate destroy -c ./config/fargateconfig.yaml
```

---

## 🚨 Common Issues

### "Command not found: spinx"
```bash
# Reinstall globally
npm install -g @muthuishere/spinx

# Check if npm global bin is in PATH
npm config get prefix
```

### Docker Build Failures
```bash
# Ensure Docker is running
docker info

# Enable BuildKit
export DOCKER_BUILDKIT=1
```

### Cloud Authentication Issues
```bash
# Re-authenticate with your cloud provider
gcloud auth login              # GCP
aws configure                  # AWS  
az login                       # Azure
```

### Permission Denied Errors
- Verify your cloud account has the required IAM roles/permissions
- For GCP: Ensure billing is enabled ⚠️
- For AWS: Check IAM policies are attached to your user/role
- For Azure: Verify Contributor role or specific Container Apps permissions

---

## 📚 Additional Resources

- 🌟 [Complete Documentation](https://github.com/muthuishere/spinx)
- 📋 [Detailed Prerequisites](https://github.com/muthuishere/spinx/blob/main/prerequisites.md)
- 🐛 [Report Issues](https://github.com/muthuishere/spinx/issues)
- 💬 [Discussions](https://github.com/muthuishere/spinx/discussions)

---

## 🧑‍💻 Need Help?

1. **Check the logs**: Spinx provides detailed error messages
2. **Verify prerequisites**: Ensure all CLI tools are installed and authenticated
3. **Run setup first**: Always run `spinx <provider> setup` before deploy
4. **Check cloud quotas**: Ensure your cloud account has sufficient quotas
5. **File an issue**: [GitHub Issues](https://github.com/muthuishere/spinx/issues) for bugs or feature requests