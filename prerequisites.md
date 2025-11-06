# Prerequisites

This document outlines all the required tools, CLI utilities, and account setup needed to use Spinx for multi-cloud deployments.

## 🛠️ Required CLI Tools

### Core Tools (Required for All Deployments)

#### Docker
- **Purpose**: Building and pushing container images
- **Required Version**: Latest stable version with BuildKit support
- **Installation**: 
  - macOS: `brew install --cask docker` or download from [Docker Desktop](https://www.docker.com/products/docker-desktop)
  - Linux: Follow [Docker official installation guide](https://docs.docker.com/engine/install/)
  - Windows: Download from [Docker Desktop](https://www.docker.com/products/docker-desktop)
- **Verification**: `docker --version && docker buildx version`
- **Configuration**: 
  - Enable BuildKit: `export DOCKER_BUILDKIT=1`
  - Enable experimental features: `export DOCKER_CLI_EXPERIMENTAL=enabled`

#### Git
- **Purpose**: Generating unique image tags based on commit hashes
- **Installation**: 
  - macOS: `brew install git` or pre-installed
  - Linux: `sudo apt install git` (Ubuntu/Debian) or `sudo yum install git` (RHEL/CentOS)
  - Windows: Download from [Git for Windows](https://git-scm.com/download/win)
- **Verification**: `git --version`

### Cloud Provider Specific Tools

#### Google Cloud Platform (GCP) - Required for `gcp-cloudrun`

##### Google Cloud CLI (gcloud)
- **Purpose**: Managing GCP resources, authentication, and Docker registry authentication
- **Installation**: 
  - macOS: `brew install --cask google-cloud-sdk`
  - Linux/Windows: Follow [official installation guide](https://cloud.google.com/sdk/docs/install)
- **Verification**: `gcloud --version`
- **Required Components**:
  ```bash
  gcloud components install docker-credential-gcr
  ```

#### Amazon Web Services (AWS) - Required for `aws-fargate`

##### AWS CLI v2
- **Purpose**: Managing AWS credentials and authentication
- **Installation**:
  - macOS: `brew install awscli`
  - Linux/Windows: Follow [AWS CLI installation guide](https://docs.aws.amazon.com/cli/latest/userguide/getting-started-install.html)
- **Verification**: `aws --version` (should show v2.x.x)

#### Microsoft Azure - Required for `azure-container-apps`

##### Azure CLI (az)
- **Purpose**: Managing Azure resources and authentication
- **Installation**:
  - macOS: `brew install azure-cli`
  - Linux: Follow [Azure CLI installation guide](https://docs.microsoft.com/en-us/cli/azure/install-azure-cli-linux)
  - Windows: Download from [Azure CLI installer](https://docs.microsoft.com/en-us/cli/azure/install-azure-cli-windows)
- **Verification**: `az --version`

## ☁️ Cloud Account Setup

### Google Cloud Platform (GCP)

#### Project Setup
1. **Create a GCP Project**:
   - Visit [Google Cloud Console](https://console.cloud.google.com/)
   - Create a new project or select an existing one
   - Note your Project ID (not the project name)

2. **Enable Billing** ⚠️ **REQUIRED**:
   - **Billing must be enabled** for your GCP project
   - Cloud Run and Artifact Registry require a billing account
   - Go to [Billing Console](https://console.cloud.google.com/billing)
   - Link your project to a valid billing account

3. **Required APIs** (✅ **Automatically enabled by Spinx**):
   - Spinx will automatically enable `artifactregistry.googleapis.com` and `run.googleapis.com` during setup
   - Manual command only needed if automatic enabling fails:
   ```bash
   gcloud services enable artifactregistry.googleapis.com run.googleapis.com
   ```

#### Authentication
```bash
# Login to GCP
gcloud auth login

# Set application default credentials
gcloud auth application-default login

# Set your project
gcloud config set project YOUR_PROJECT_ID

# Set quota project for ADC
gcloud auth application-default set-quota-project YOUR_PROJECT_ID

# Configure Docker authentication
gcloud auth configure-docker REGION-docker.pkg.dev
```

#### Required Permissions
Your account needs the following IAM roles:
- `Cloud Run Admin` (for deploying Cloud Run services)
- `Artifact Registry Admin` (Spinx automatically creates repositories)
- `Service Usage Admin` (Spinx automatically enables required APIs)
- `Project Editor` (or specific permissions for creating resources)### Amazon Web Services (AWS)

#### Account Setup
1. **Create AWS Account**: Visit [AWS Console](https://aws.amazon.com/)
2. **Create IAM User** (recommended over root account usage):
   - Go to IAM Console
   - Create user with programmatic access
   - Attach required policies (see below)

#### Authentication
```bash
# Configure AWS credentials
aws configure

# Or set environment variables
export AWS_ACCESS_KEY_ID=your_access_key
export AWS_SECRET_ACCESS_KEY=your_secret_key
export AWS_DEFAULT_REGION=your_preferred_region
```

#### Required Permissions
Your IAM user/role needs these policies (Spinx automatically creates all infrastructure):
- `AmazonECS_FullAccess` (Spinx creates ECS clusters, services, task definitions)  
- `AmazonEC2ContainerRegistryFullAccess` (Spinx creates ECR repositories)
- `AmazonVPCFullAccess` (Spinx creates VPC, subnets, security groups if needed)
- `ElasticLoadBalancingFullAccess` (Spinx creates Application Load Balancers)
- `IAMFullAccess` (Spinx creates ECS task execution and task roles)
- `CloudWatchLogsFullAccess` (Spinx creates log groups)
- `AmazonECSTaskExecutionRolePolicy` (for ECS tasks to pull images and write logs)

### Microsoft Azure

#### Account Setup
1. **Create Azure Account**: Visit [Azure Portal](https://portal.azure.com/)
2. **Create Subscription** (if not already available)

#### Authentication
```bash
# Login to Azure
az login

# Set default subscription (optional)
az account set --subscription "your-subscription-id"
```

#### Required Permissions
Your account needs these roles (Spinx automatically creates all infrastructure):
- `Contributor` (Spinx creates resource groups, registries, workspaces, environments)
- `User Access Administrator` (for managing resource permissions)
- Or specific roles:
  - `Container Apps Contributor` (Spinx creates Container Apps and environments)
  - `Azure Container Registry Contributor` (Spinx creates ACR repositories)
  - `Log Analytics Contributor` (Spinx creates Log Analytics workspaces)
  
Note: Spinx automatically registers required resource providers (`Microsoft.App`, `Microsoft.OperationalInsights`)

## ✨ What Spinx Automatically Handles

Spinx is designed to minimize manual setup by automatically creating and configuring cloud infrastructure during the `setup` command:

### Google Cloud Platform (GCP)
✅ **Automatically Created/Configured:**
- Enables required APIs (`artifactregistry.googleapis.com`, `run.googleapis.com`)
- Creates Artifact Registry repositories
- Configures Docker authentication with Artifact Registry

### Amazon Web Services (AWS)  
✅ **Automatically Created/Configured:**
- Creates ECR repositories
- Creates VPC and networking (subnets, internet gateways, route tables) if none exist
- Creates IAM roles (ECS task execution role, task role, service-linked roles)
- Creates ECS clusters with Fargate capacity providers
- Creates Application Load Balancers with target groups and listeners
- Creates security groups for load balancer and ECS service
- Creates CloudWatch log groups

### Microsoft Azure
✅ **Automatically Created/Configured:**
- Registers required resource providers (`Microsoft.App`, `Microsoft.OperationalInsights`)
- Creates resource groups
- Creates Azure Container Registry (ACR) 
- Creates Log Analytics workspaces
- Creates Container Apps environments
- Creates and updates Container Apps

### What You Still Need to Do Manually
❌ **Manual Requirements:**
- Install CLI tools (Docker, gcloud/aws/az CLI)
- Set up cloud account authentication (login commands)
- Ensure proper IAM permissions/roles
- Enable billing (GCP) / have valid subscription (Azure) / have AWS account

## 🔧 Development Environment

### Java Runtime
- **Required**: Java 11 or higher
- **Purpose**: Running the Spinx CLI tool
- **Installation**:
  - macOS: `brew install openjdk@21`
  - Linux: `sudo apt install openjdk-21-jdk`
  - Windows: Download from [Oracle](https://www.oracle.com/java/technologies/downloads/) or [OpenJDK](https://openjdk.org/)

### Build Tools (for building Spinx from source)
- **Gradle**: For building the project
- **Installation**: `brew install gradle` (macOS) or follow [Gradle installation guide](https://gradle.org/install/)

## 📁 Project Structure Requirements

### Required Files
Each deployment requires a configuration file (YAML) with the following structure:

#### GCP Cloud Run
```yaml
serviceName: "my-service"
projectId: "your-gcp-project-id"
region: "us-central1"
artifactRegistryRepository: "my-repo"
imageUri: "us-central1-docker.pkg.dev/your-project/my-repo/my-service"
dockerfilePath: "./Dockerfile"
environmentFile: "./.env"  # Optional
environmentVariables:      # Optional
  SPRING_PROFILES_ACTIVE: "production"
```

#### AWS Fargate
```yaml
serviceName: "my-service"
region: "us-east-1"
dockerfilePath: "./Dockerfile"
environmentFile: "./.env"  # Optional
environmentVariables:      # Optional
  SPRING_PROFILES_ACTIVE: "production"
```

#### Azure Container Apps
```yaml
serviceName: "my-service"
subscriptionId: "your-azure-subscription-id"
resourceGroupName: "my-resource-group"
region: "East US"
dockerfilePath: "./Dockerfile"
environmentFile: "./.env"  # Optional
environmentVariables:      # Optional
  SPRING_PROFILES_ACTIVE: "production"
```

### Optional Files
- **`.env`**: Environment variables file (dotenv format)
- **`Dockerfile`**: Container definition (path specified in config)

## 🚨 Common Issues and Troubleshooting

### Docker Issues
- **BuildKit not enabled**: Set `DOCKER_BUILDKIT=1` environment variable
- **Permission denied**: Ensure Docker daemon is running and user has permissions

### GCP Issues
- **Authentication failed**: Run `gcloud auth application-default login`
- **Project not set**: Run `gcloud config set project YOUR_PROJECT_ID`
- **Billing not enabled**: ⚠️ **Most common issue** - ensure billing is enabled for your project
- **APIs not enabled**: Spinx automatically enables APIs, but may fail if permissions insufficient

### AWS Issues
- **Credentials not configured**: Run `aws configure` or set environment variables
- **Region not set**: Specify region in config or set `AWS_DEFAULT_REGION`
- **Insufficient permissions**: Check IAM policies - Spinx creates extensive infrastructure automatically
- **VPC limit reached**: AWS has VPC limits per region; Spinx will create default VPC if none exists

### Azure Issues
- **Not logged in**: Run `az login`
- **Wrong subscription**: Run `az account set --subscription "subscription-id"`
- **Resource provider registration failed**: Spinx automatically registers providers, but may fail if permissions insufficient
- **Resource group conflicts**: Spinx creates resource groups automatically with unique names

## 📋 Verification Checklist

Before using Spinx, verify all prerequisites:

```bash
# Core tools
docker --version
git --version

# GCP (if using gcp-cloudrun)
gcloud --version
gcloud auth list
gcloud config get-value project

# AWS (if using aws-fargate)
aws --version
aws sts get-caller-identity

# Azure (if using azure-container-apps)
az --version
az account show

# Java runtime
java --version
```

All commands should execute successfully without errors before proceeding with Spinx deployments.