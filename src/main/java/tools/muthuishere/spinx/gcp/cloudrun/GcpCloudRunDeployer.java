package tools.muthuishere.spinx.gcp.cloudrun;

import com.google.auth.oauth2.GoogleCredentials;


import com.google.protobuf.Duration;
import io.grpc.StatusRuntimeException;
import tools.muthuishere.spinx.CloudDeployer;
import tools.muthuishere.spinx.Runner;
import tools.muthuishere.spinx.EnvironmentParser;
import org.yaml.snakeyaml.Yaml;

import java.io.FileInputStream;
import java.util.*;
import java.util.concurrent.TimeUnit;

import com.google.cloud.run.v2.Container;
import com.google.cloud.run.v2.ContainerPort;
import com.google.cloud.run.v2.EnvVar;

import com.google.cloud.run.v2.IngressTraffic;
import com.google.cloud.run.v2.RevisionScaling;
import com.google.cloud.run.v2.RevisionTemplate;
import com.google.cloud.run.v2.ResourceRequirements;
import com.google.cloud.run.v2.Service;

import com.google.cloud.run.v2.ServicesClient;
import com.google.cloud.run.v2.ServicesSettings;
import com.google.cloud.run.v2.TrafficTarget;
import com.google.cloud.run.v2.TrafficTargetAllocationType;
import com.google.cloud.run.v2.UpdateServiceRequest;
import com.google.cloud.run.v2.CreateServiceRequest;
import com.google.cloud.run.v2.DeleteServiceRequest;
import com.google.cloud.run.v2.RevisionsClient;
import com.google.cloud.run.v2.RevisionsSettings;
import com.google.devtools.artifactregistry.v1.ArtifactRegistryClient;
import com.google.devtools.artifactregistry.v1.ArtifactRegistrySettings;
import com.google.devtools.artifactregistry.v1.CreateRepositoryRequest;
import com.google.devtools.artifactregistry.v1.DeleteRepositoryRequest;
import com.google.devtools.artifactregistry.v1.Repository;

public class GcpCloudRunDeployer implements CloudDeployer {

    private GcpCloudRunConfig config;
    private String configFilename;
    private ServicesClient servicesClient;
    private RevisionsClient revisionsClient;
    private ArtifactRegistryClient artifactRegistryClient;

    @Override
    public void init(String configFilename) {
        System.out.println("GCP Cloud Run: Initializing with config file: " + configFilename);
        this.configFilename = configFilename;

        try {
            // Load config using SnakeYAML
            Yaml yaml = new Yaml();
            config = yaml.loadAs(new FileInputStream(configFilename), GcpCloudRunConfig.class);
            System.out.println("Loaded config: " + config);

            // Load environment variables from .env file
            loadEnvironmentVariables();

            // Set project configuration automatically
            System.out.println("- Setting gcloud project to: " + config.getProjectId());
            setGcloudProject();

            // Initialize Google Cloud credentials
            GoogleCredentials credentials = GoogleCredentials.getApplicationDefault();

            // Initialize Cloud Run clients
            ServicesSettings servicesSettings = ServicesSettings.newBuilder()
                    .setCredentialsProvider(() -> credentials)
                    .build();
            this.servicesClient = ServicesClient.create(servicesSettings);

            RevisionsSettings revisionsSettings = RevisionsSettings.newBuilder()
                    .setCredentialsProvider(() -> credentials)
                    .build();
            this.revisionsClient = RevisionsClient.create(revisionsSettings);

            // Initialize Artifact Registry client
            ArtifactRegistrySettings artifactRegistrySettings = ArtifactRegistrySettings.newBuilder()
                    .setCredentialsProvider(() -> credentials)
                    .build();
            this.artifactRegistryClient = ArtifactRegistryClient.create(artifactRegistrySettings);

            System.out.println("✅ GCP Cloud Run deployer initialized successfully");

        } catch (Exception e) {
            System.out.println("Failed to initialize GCP Cloud Run deployer: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("Failed to initialize GCP Cloud Run deployer", e);
        }
    }

    private void loadEnvironmentVariables() {
        // Get config file directory for relative path resolution
        String configFileDirectory = null;
        if (configFilename != null) {
            java.io.File configFile = new java.io.File(configFilename);
            configFileDirectory = configFile.getParent();
        }
        
        // Use EnvironmentParser to handle all environment variable parsing
        EnvironmentParser.EnvironmentResult result = EnvironmentParser.parseEnvironmentVariables(
            config.getEnvironmentVariables(),  // YAML environment variables (takes precedence)
            config.getEnvironmentFile(),       // .env file path
            configFileDirectory                // Config file directory for relative path resolution
        );
        
        // Update config with final parsed environment variables
        config.setEnvironmentVariables(result.getEnvironmentVariables());
        
        // Log summary
        System.out.println("� Environment parsing complete:");
        System.out.println("   📝 Source: " + result.getSource());
        System.out.println("   📊 Total variables: " + result.getTotalCount());
        if (result.getYamlCount() > 0) {
            System.out.println("   � From YAML: " + result.getYamlCount());
        }
        if (result.getEnvFileCount() > 0) {
            System.out.println("   📄 From .env file: " + result.getEnvFileCount());
        }
    }

    @Override
    public void setup() {
        System.out.println("GCP Cloud Run: Setting up infrastructure for " + config.getServiceName());

        try {
            System.out.println("- Enabling required GCP services...");
            enableRequiredServices();

            System.out.println("- Creating Artifact Registry repository: " + config.getArtifactRegistryRepository());
            createArtifactRegistry();

            System.out.println("✅ GCP Cloud Run infrastructure setup completed!");

        } catch (Exception e) {
            System.out.println("Setup failed: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("GCP Cloud Run setup failed", e);
        }
    }

    @Override
    public void deploy() {
        System.out.println("GCP Cloud Run: Deploying " + config.getServiceName());

        try {
            // 0. Validate that setup has been run
            System.out.println("- Validating infrastructure setup...");
            validateSetupHasBeenRun();
            
            // Build and push Docker image with unique tag
            String uniqueTag = generateUniqueImageTag();
            String imageUri = config.getImageUri() + ":" + uniqueTag;
            System.out.println("- Building and pushing Docker image: " + imageUri);
            buildAndPushImage(imageUri);

            System.out.println("- Deploying to Cloud Run");
            String serviceUrl = deployCloudRunService(imageUri);

            System.out.println("\n=== GCP Cloud Run Deployment Complete ===");
            System.out.println("Service Name: " + config.getServiceName());
            System.out.println("Project ID: " + config.getProjectId());
            System.out.println("Region: " + config.getRegion());
            System.out.println("Service URL: " + serviceUrl);
            System.out.println("\nMCP Configuration:");
            printMcpConfig(serviceUrl);

        } catch (Exception e) {
            System.out.println("Deployment failed: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("GCP Cloud Run deployment failed", e);
        }
    }

    @Override
    public void destroy() {
        System.out.println("GCP Cloud Run: Starting aggressive cleanup for " + config.getServiceName());

        try {
            System.out.println("- Deleting Cloud Run service");
            deleteCloudRunService();

            System.out.println("- Deleting Artifact Registry repository");
            deleteArtifactRegistry();

            System.out.println("✅ GCP Cloud Run cleanup completed successfully!");

        } catch (Exception e) {
            System.out.println("Cleanup failed: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("GCP Cloud Run cleanup failed", e);
        }
    }

    private void validateSetupHasBeenRun() {
        try {
            System.out.println("    Checking GCP project: " + config.getProjectId());
            
            // Check if gcloud is authenticated and project is set
            try {
                runGcloudCommand("config", "get-value", "project");
            } catch (Exception e) {
                System.err.println("\n❌ Setup validation failed!");
                System.err.println("GCP project not configured or gcloud not authenticated.");
                System.err.println("\nPlease run the setup command first:");
                System.err.println("  spinx gcp-cloudrun setup");
                System.err.println("\nOr configure manually:");
                System.err.println("  gcloud auth login");
                System.err.println("  gcloud config set project " + config.getProjectId());
                throw new RuntimeException("Infrastructure setup required. GCP project not configured.", e);
            }
            
            System.out.println("    Checking Artifact Registry repository: " + config.getArtifactRegistryRepository());
            
            // Check if Artifact Registry repository exists
            try {
                String repositoryName = String.format("projects/%s/locations/%s/repositories/%s",
                    config.getProjectId(), config.getRegion(), config.getArtifactRegistryRepository());
                
                artifactRegistryClient.getRepository(repositoryName);
            } catch (Exception e) {
                System.err.println("\n❌ Setup validation failed!");
                System.err.println("Artifact Registry repository '" + config.getArtifactRegistryRepository() + "' does not exist in project '" + config.getProjectId() + "'.");
                System.err.println("\nPlease run the setup command first:");
                System.err.println("  spinx gcp-cloudrun setup");
                throw new RuntimeException("Infrastructure setup required. Artifact Registry repository '" + config.getArtifactRegistryRepository() + "' not found.", e);
            }
            
            System.out.println("    Checking required APIs are enabled...");
            
            // Check if required APIs are enabled (using gcloud for simplicity)
            try {
                runGcloudCommand("services", "list", "--enabled", "--filter=name:artifactregistry.googleapis.com", "--format=value(name)");
                runGcloudCommand("services", "list", "--enabled", "--filter=name:run.googleapis.com", "--format=value(name)");
            } catch (Exception e) {
                System.err.println("\n❌ Setup validation failed!");
                System.err.println("Required GCP APIs may not be enabled (artifactregistry.googleapis.com, run.googleapis.com).");
                System.err.println("\nPlease run the setup command first:");
                System.err.println("  spinx gcp-cloudrun setup");
                System.err.println("\nOr enable manually:");
                System.err.println("  gcloud services enable artifactregistry.googleapis.com run.googleapis.com");
                throw new RuntimeException("Infrastructure setup required. Required APIs not enabled.", e);
            }
            
            System.out.println("    ✅ Infrastructure validation completed successfully");
            
        } catch (RuntimeException e) {
            throw e; // Re-throw runtime exceptions with our custom messages
        } catch (Exception e) {
            System.err.println("\n❌ Setup validation failed!");
            System.err.println("Error validating infrastructure: " + e.getMessage());
            System.err.println("\nPlease run the setup command first:");
            System.err.println("  spinx gcp-cloudrun setup");
            throw new RuntimeException("Infrastructure validation failed", e);
        }
    }
    
    private void createArtifactRegistry() {
        try {
            String repositoryName = String.format("projects/%s/locations/%s/repositories/%s",
                config.getProjectId(), config.getRegion(), config.getArtifactRegistryRepository());

            // Check if repository already exists
            logCommand("Check if Artifact Registry repository exists", "gcloud artifacts repositories describe " + config.getArtifactRegistryRepository() + " --location=" + config.getRegion() + " --project=" + config.getProjectId());
            try {
                Repository existingRepo = artifactRegistryClient.getRepository(repositoryName);
                System.out.println("Artifact Registry repository already exists: " + existingRepo.getName());
                return;
            } catch (com.google.api.gax.rpc.NotFoundException e) {
                // Repository doesn't exist, create it
                System.out.println("Repository doesn't exist, creating new one...");
            } catch (StatusRuntimeException e) {
                if (e.getStatus().getCode() == io.grpc.Status.Code.UNAUTHENTICATED) {
                    System.err.println("❌ Authentication failed. Please ensure you have:");
                    System.err.println("   1. Run 'gcloud auth login' and 'gcloud auth application-default login'");
                    System.err.println("   2. Set project with 'gcloud config set project YOUR_PROJECT_ID'");
                    System.err.println("   3. Enabled required APIs: artifactregistry.googleapis.com, run.googleapis.com");
                    System.err.println("   4. Have proper IAM permissions for Artifact Registry and Cloud Run");
                    throw new RuntimeException("GCP Authentication failed. Please check credentials and permissions.", e);
                }
                if (e.getStatus().getCode() == io.grpc.Status.Code.NOT_FOUND) {
                    // Repository doesn't exist, create it
                    System.out.println("Repository doesn't exist, creating new one...");
                } else {
                    throw e;
                }
            }

            // Create repository
            String parent = String.format("projects/%s/locations/%s", config.getProjectId(), config.getRegion());
            Repository repository = Repository.newBuilder()
                    .setFormat(Repository.Format.DOCKER)
                    .setDescription("Repository for " + config.getServiceName())
                    .build();

            CreateRepositoryRequest request = CreateRepositoryRequest.newBuilder()
                    .setParent(parent)
                    .setRepositoryId(config.getArtifactRegistryRepository())
                    .setRepository(repository)
                    .build();

            logCommand("Create Artifact Registry repository", "gcloud artifacts repositories create " + config.getArtifactRegistryRepository() + " --location=" + config.getRegion() + " --repository-format=docker --project=" + config.getProjectId());
            Repository createdRepo = artifactRegistryClient.createRepositoryAsync(request).get(5, TimeUnit.MINUTES);
            System.out.println("Artifact Registry repository created successfully: " + createdRepo.getName());

        } catch (Exception e) {
            if (e.getMessage().contains("UNAUTHENTICATED") || e.getMessage().contains("authentication")) {
                System.err.println("❌ Authentication error detected. Please run:");
                System.err.println("   gcloud auth login");
                System.err.println("   gcloud auth application-default login");
                System.err.println("   gcloud config set project " + config.getProjectId());
            }
            throw new RuntimeException("Failed to create Artifact Registry repository", e);
        }
    }

    private void setGcloudProject() {
        try {
            System.out.println("  Setting gcloud default project...");
            runGcloudCommand("config", "set", "project", config.getProjectId());
            
            System.out.println("  Setting quota project for ADC...");
            runGcloudCommand("auth", "application-default", "set-quota-project", config.getProjectId());
            
            System.out.println("  ✅ Project configuration updated");
        } catch (Exception e) {
            System.err.println("  ⚠️  Warning: Failed to set project configuration automatically.");
            System.err.println("     Please run manually:");
            System.err.println("     gcloud config set project " + config.getProjectId());
            System.err.println("     gcloud auth application-default set-quota-project " + config.getProjectId());
            // Don't fail initialization for this - user can set manually
        }
    }

    private void enableRequiredServices() {
        try {
            System.out.println("  Enabling artifactregistry.googleapis.com...");
            runGcloudCommand("services", "enable", "artifactregistry.googleapis.com");
            
            System.out.println("  Enabling run.googleapis.com...");
            runGcloudCommand("services", "enable", "run.googleapis.com");
            
            System.out.println("  ✅ Required services enabled");
        } catch (Exception e) {
            System.err.println("  ⚠️  Warning: Failed to enable services automatically. Please run manually:");
            System.err.println("     gcloud services enable artifactregistry.googleapis.com run.googleapis.com");
            // Don't fail the setup for this - user can enable manually
        }
    }

    private void runGcloudCommand(String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("gcloud");
        command.addAll(Arrays.asList(args));
        
        logCommand("Running gcloud command", command);
        
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        
        // Read output
        java.io.BufferedReader reader = new java.io.BufferedReader(
            new java.io.InputStreamReader(process.getInputStream()));
        String line;
        while ((line = reader.readLine()) != null) {
            System.out.println("    " + line);
        }
        
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("gcloud command failed with exit code: " + exitCode);
        }
    }

    private void buildAndPushImage(String imageUri) {
        try {
            System.out.println("  Building Docker image from " + config.getDockerfilePath());
            
            // Build the image for linux/amd64 platform (required for Cloud Run) with ultra-fast buildx
            logCommand("Building Docker image for Cloud Run", "docker buildx build --platform linux/amd64 --provenance=false --cache-from=type=local,src=/tmp/.buildx-cache --cache-to=type=local,dest=/tmp/.buildx-cache -t " + imageUri + " -f " + config.getDockerfilePath() + " .");
            Runner.runDockerBuildxFast(imageUri, config.getDockerfilePath(), ".");
            
            // Configure Docker to use gcloud for authentication
            System.out.println("  Configuring Docker authentication for Artifact Registry");
            String registryUrl = config.getRegion() + "-docker.pkg.dev";
            runGcloudCommand("auth", "configure-docker", registryUrl, "--quiet");
            
            // Push the image to Artifact Registry
            System.out.println("  Pushing image to Artifact Registry: " + imageUri);
            logCommand("Pushing Docker image to Artifact Registry", "docker push " + imageUri);
            Runner.runDocker("push", imageUri);
            
            System.out.println("  ✅ Docker image built and pushed successfully");
            
            // Wait for image to be fully available in the registry with verification
            System.out.println("  Waiting for image to be available in registry...");
            waitForImageAvailability(imageUri);
            
        } catch (Exception e) {
            System.err.println("Failed to build and push Docker image: " + e.getMessage());
            throw new RuntimeException("Docker build and push failed", e);
        }
    }

    private void logCommand(String description, List<String> command) {
        System.out.println("🔧 " + description);
        System.out.println("📋 Manual command: " + String.join(" ", command));
    }

    private void logCommand(String description, String command) {
        System.out.println("🔧 " + description);
        System.out.println("📋 Manual command: " + command);
    }

    private String generateUniqueImageTag() {
        // Try to get git commit hash first
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "rev-parse", "--short", "HEAD");
            Process process = pb.start();
            java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(process.getInputStream()));
            String gitHash = reader.readLine();
            int exitCode = process.waitFor();
            
            if (exitCode == 0 && gitHash != null && !gitHash.trim().isEmpty()) {
                String timestamp = String.valueOf(System.currentTimeMillis() / 1000); // Unix timestamp
                String tag = gitHash.trim() + "-" + timestamp;
                System.out.println("📌 Using git-based image tag: " + tag);
                return tag;
            }
        } catch (Exception e) {
            System.out.println("⚠️  Could not get git hash: " + e.getMessage());
        }
        
        // Fallback to timestamp-based tag
        String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
        String tag = "build-" + timestamp;
        System.out.println("📌 Using timestamp-based image tag: " + tag);
        return tag;
    }

    private String buildGcloudRunDeployCommand(String imageUri) {
        StringBuilder cmd = new StringBuilder();
        cmd.append("gcloud run deploy ").append(config.getServiceName());
        cmd.append(" --image=").append(imageUri);
        cmd.append(" --region=").append(config.getRegion());
        cmd.append(" --project=").append(config.getProjectId());
        cmd.append(" --platform=managed");
        cmd.append(" --cpu=").append(config.getCpu());
        cmd.append(" --memory=").append(config.getMemory());
        cmd.append(" --port=").append(config.getContainerPort());
        cmd.append(" --min-instances=").append(config.getMinInstances());
        cmd.append(" --max-instances=").append(config.getMaxInstances());
        cmd.append(" --timeout=").append(config.getTimeout());
        
        if (config.isAllowUnauthenticated()) {
            cmd.append(" --allow-unauthenticated");
        }
        
        // Add environment variables
        for (Map.Entry<String, String> entry : config.getEnvironmentVariables().entrySet()) {
            cmd.append(" --set-env-vars=").append(entry.getKey()).append("=").append(entry.getValue());
        }
        
        return cmd.toString();
    }

    private void waitForImageAvailability(String imageUri) {
        System.out.println("    Performing quick image verification...");
        
        try {           
            // Simple check - just verify the image exists using gcloud describe (same as working shell script)
            List<String> command = Arrays.asList(
                "gcloud", "container", "images", "describe", imageUri,
                "--format=value(name)"
            );
            logCommand("Verifying image exists in Artifact Registry", command);
            
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            
            // Read the output to see what we get
            java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(process.getInputStream()));
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
            
            int exitCode = process.waitFor();
            String outputStr = output.toString().trim();
            
            System.out.println("    Debug: gcloud command exit code: " + exitCode);
            System.out.println("    Debug: gcloud command output: '" + outputStr + "'");
            
            if (exitCode == 0 && !outputStr.isEmpty()) {
                System.out.println("    ✅ Image verified as available in registry");
                return;
            }
            
            // If first check fails, wait a bit and try once more
            System.out.println("    Image not immediately available, waiting 10 seconds...");
            Thread.sleep(10000);
            
            process = pb.start();
            reader = new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream()));
            output = new StringBuilder();
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
            exitCode = process.waitFor();
            outputStr = output.toString().trim();
            
            System.out.println("    Debug retry: gcloud command exit code: " + exitCode);
            System.out.println("    Debug retry: gcloud command output: '" + outputStr + "'");
            
            if (exitCode == 0 && !outputStr.isEmpty()) {
                System.out.println("    ✅ Image verified as available in registry (after retry)");
                return;
            }
            
            // If still failing, just proceed - the image is likely there
            System.out.println("    ⚠️  Image verification inconclusive, proceeding with deployment...");
            
        } catch (Exception e) {
            System.out.println("    ⚠️  Image verification failed: " + e.getMessage());
            System.out.println("    Proceeding with deployment...");
        }
    }

    private String deployCloudRunService(String imageUri) {
        return retryDeploymentWithImageWait(imageUri);
    }

    private String retryDeploymentWithImageWait(String imageUri) {
        int maxRetries = 3;
        int currentRetry = 0;
        Exception lastException = null;
        
        while (currentRetry < maxRetries) {
            try {
                System.out.println("Attempting Cloud Run deployment (attempt " + (currentRetry + 1) + "/" + maxRetries + ")...");
                return performDeployment(imageUri);
                
            } catch (Exception e) {
                lastException = e;
                
                // Check if this is an image not found error
                boolean isImageNotFound = e.getMessage() != null && 
                    (e.getMessage().contains("Image") && e.getMessage().contains("not found"));
                
                currentRetry++;
                
                if (isImageNotFound && currentRetry < maxRetries) {
                    int waitTime = 30 + (currentRetry * 30); // 30s, 60s, 90s
                    System.out.println("    Image not found error detected. Waiting " + waitTime + " seconds for image propagation...");
                    try {
                        Thread.sleep(waitTime * 1000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Deployment retry interrupted", ie);
                    }
                } else if (currentRetry < maxRetries) {
                    System.out.println("    Deployment failed with error: " + e.getMessage());
                    if (e.getCause() != null) {
                        System.out.println("    Root cause: " + e.getCause().getMessage());
                    }
                    System.out.println("    Full stack trace:");
                    e.printStackTrace(); // Print full stack trace for debugging
                    System.out.println("    Retrying in 15 seconds...");
                    try {
                        Thread.sleep(15000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Deployment retry interrupted", ie);
                    }
                } else {
                    System.out.println("    Maximum retry attempts reached");
                    break;
                }
            }
        }
        
        throw new RuntimeException("Failed to deploy Cloud Run service after " + maxRetries + " attempts", lastException);
    }

    private String performDeployment(String imageUri) {
        try {
            String serviceName = String.format("projects/%s/locations/%s/services/%s",
                    config.getProjectId(), config.getRegion(), config.getServiceName());
            
            System.out.println("    Debug: Starting deployment with image: " + imageUri);
            System.out.println("    Debug: Service name: " + serviceName);

            // Build container spec
            Container.Builder containerBuilder = Container.newBuilder()
                    .setImage(imageUri)
                    .addPorts(ContainerPort.newBuilder()
                            .setName("http1")
                            .setContainerPort(config.getContainerPort())
                            .build())
                    .setResources(ResourceRequirements.newBuilder()
                            .putLimits("cpu", config.getCpu())
                            .putLimits("memory", config.getMemory())
                            .build());

            // Add environment variables
            System.out.println("    Debug: Adding " + config.getEnvironmentVariables().size() + " environment variables to container:");
            for (Map.Entry<String, String> entry : config.getEnvironmentVariables().entrySet()) {
                String value = entry.getValue();
                String displayValue = value.length() > 20 ? value.substring(0, 20) + "..." : value;
                System.out.println("      🔑 " + entry.getKey() + "=" + displayValue);
                containerBuilder.addEnv(EnvVar.newBuilder()
                        .setName(entry.getKey())
                        .setValue(entry.getValue())
                        .build());
            }

            // Build revision template - use the Builder methods directly
            RevisionTemplate.Builder revisionTemplateBuilder = RevisionTemplate.newBuilder()
                    .setScaling(RevisionScaling.newBuilder()
                            .setMinInstanceCount(config.getMinInstances())
                            .setMaxInstanceCount(config.getMaxInstances())
                            .build())
                    .setTimeout(Duration.newBuilder()
                            .setSeconds(config.getTimeout())
                            .build())
                    .addContainers(containerBuilder.build());

            // Build service configuration
            Service.Builder serviceBuilder = Service.newBuilder()
                    .setTemplate(revisionTemplateBuilder.build())
                    .addTraffic(TrafficTarget.newBuilder()
                            .setPercent(100)
                            .setType(TrafficTargetAllocationType.TRAFFIC_TARGET_ALLOCATION_TYPE_LATEST)
                            .build());

            // Set ingress to allow all traffic if unauthenticated access is enabled
            if (config.isAllowUnauthenticated()) {
                serviceBuilder.setIngress(IngressTraffic.INGRESS_TRAFFIC_ALL);
            }

            Service service = serviceBuilder.build();

            // Check if service exists and update or create
            try {
                System.out.println("    Debug: Checking if service exists: " + serviceName);
                logCommand("Check if Cloud Run service exists", "gcloud run services describe " + config.getServiceName() + " --region=" + config.getRegion() + " --project=" + config.getProjectId());
                Service existingService = servicesClient.getService(serviceName);
                System.out.println("Service exists, updating...");

                Service updatedService = existingService.toBuilder()
                        .setTemplate(service.getTemplate())
                        .clearTraffic()
                        .addAllTraffic(service.getTrafficList())
                        .build();

                UpdateServiceRequest updateRequest = UpdateServiceRequest.newBuilder()
                        .setService(updatedService)
                        .build();

                System.out.println("    Debug: Calling updateServiceAsync...");
                logCommand("Update Cloud Run service (equivalent command)", buildGcloudRunDeployCommand(imageUri));
                Service result = servicesClient.updateServiceAsync(updateRequest).get(10, TimeUnit.MINUTES);
                System.out.println("Service updated successfully");
                
                // Set IAM policy for unauthenticated access if configured
                if (config.isAllowUnauthenticated()) {
                    setIamPolicyForUnauthenticatedAccess();
                }
                
                return result.getUri();

            } catch (Exception e) {
                // Handle both StatusRuntimeException and NotFoundException for NOT_FOUND
                boolean isNotFound = false;
                if (e instanceof StatusRuntimeException) {
                    StatusRuntimeException sre = (StatusRuntimeException) e;
                    isNotFound = sre.getStatus().getCode() == io.grpc.Status.Code.NOT_FOUND;
                } else if (e instanceof com.google.api.gax.rpc.NotFoundException) {
                    isNotFound = true;
                }
                
                if (isNotFound) {
                    System.out.println("Service not found, creating new service...");

                    String parent = String.format("projects/%s/locations/%s", config.getProjectId(), config.getRegion());
                    CreateServiceRequest createRequest = CreateServiceRequest.newBuilder()
                            .setParent(parent)
                            .setService(service)
                            .setServiceId(config.getServiceName())
                            .build();

                    System.out.println("    Debug: Calling createServiceAsync...");
                    logCommand("Create Cloud Run service (equivalent command)", buildGcloudRunDeployCommand(imageUri));
                    Service result = servicesClient.createServiceAsync(createRequest).get(10, TimeUnit.MINUTES);
                    System.out.println("Service created successfully");
                    
                    // Set IAM policy for unauthenticated access if configured
                    if (config.isAllowUnauthenticated()) {
                        setIamPolicyForUnauthenticatedAccess();
                    }
                    
                    return result.getUri();
                } else {
                    throw e;
                }
            }

        } catch (Exception e) {
            throw new RuntimeException("Failed to deploy Cloud Run service", e);
        }
    }
    private void deleteCloudRunService() {
        try {
            String serviceName = String.format("projects/%s/locations/%s/services/%s",
                config.getProjectId(), config.getRegion(), config.getServiceName());

            try {
                servicesClient.getService(serviceName);

                DeleteServiceRequest deleteRequest = DeleteServiceRequest.newBuilder()
                        .setName(serviceName)
                        .build();

                servicesClient.deleteServiceAsync(deleteRequest).get(5, TimeUnit.MINUTES);
                System.out.println("Cloud Run service deleted successfully");

            } catch (StatusRuntimeException e) {
                if (e.getStatus().getCode() == io.grpc.Status.Code.NOT_FOUND) {
                    System.out.println("Cloud Run service not found, skipping deletion");
                } else {
                    throw e;
                }
            }

        } catch (Exception e) {
            System.out.println("Warning: Failed to delete Cloud Run service: " + e.getMessage());
        }
    }

    private void deleteArtifactRegistry() {
        try {
            String repositoryName = String.format("projects/%s/locations/%s/repositories/%s",
                config.getProjectId(), config.getRegion(), config.getArtifactRegistryRepository());

            try {
                artifactRegistryClient.getRepository(repositoryName);

                DeleteRepositoryRequest deleteRequest = DeleteRepositoryRequest.newBuilder()
                        .setName(repositoryName)
                        .build();

                artifactRegistryClient.deleteRepositoryAsync(deleteRequest).get(5, TimeUnit.MINUTES);
                System.out.println("Artifact Registry repository deleted successfully");

            } catch (StatusRuntimeException e) {
                if (e.getStatus().getCode() == io.grpc.Status.Code.NOT_FOUND) {
                    System.out.println("Artifact Registry repository not found, skipping deletion");
                } else {
                    throw e;
                }
            }

        } catch (Exception e) {
            System.out.println("Warning: Failed to delete Artifact Registry repository: " + e.getMessage());
        }
    }

    private void setIamPolicyForUnauthenticatedAccess() {
        try {
            // Use gcloud command to set IAM policy for unauthenticated access
            // This is simpler and more reliable than using the Java SDK for IAM operations
            String command = String.format(
                "gcloud run services add-iam-policy-binding %s " +
                "--region=%s " +
                "--member=\"allUsers\" " +
                "--role=\"roles/run.invoker\" " +
                "--project=%s",
                config.getServiceName(),
                config.getRegion(),
                config.getProjectId()
            );

            logCommand("Set IAM policy for unauthenticated access", command);
            Runner.runCommand(command);
            System.out.println("IAM policy set for unauthenticated access");

        } catch (Exception e) {
            System.out.println("Warning: Failed to set IAM policy for unauthenticated access: " + e.getMessage());
            System.out.println("You may need to run manually: gcloud run services add-iam-policy-binding " + 
                config.getServiceName() + " --region=" + config.getRegion() + 
                " --member=\"allUsers\" --role=\"roles/run.invoker\" --project=" + config.getProjectId());
        }
    }

    private void printMcpConfig(String serviceUrl) {

        System.out.println("\nMCP Configuration:");
        System.out.println("Add this to your MCP client config:");
        System.out.println("\"" + config.getServiceName()  + "\": {");
        System.out.println("  \"type\": \"http\",");
        System.out.println("  \"url\": \"" + serviceUrl + "/mcp\"");
        System.out.println("}");

        
    }

    @Override
    public void showLogs() {
        try {
            System.out.println("=== Showing logs for Cloud Run service: " + config.getServiceName() + " ===");
            System.out.println("Project: " + config.getProjectId());
            System.out.println("Region: " + config.getRegion());
            System.out.println("Press Ctrl+C to stop...\n");
            
            // Show recent logs first
            System.out.println("📜 Fetching recent logs...");
            String recentLogsCommand = String.format(
                "gcloud beta run services logs read %s " +
                "--project=%s " +
                "--region=%s " +
                "--limit=50",
                config.getServiceName(),
                config.getProjectId(),
                config.getRegion()
            );
            
            Runner.runCommand(recentLogsCommand);
            
            System.out.println("\n🔄 Polling for new logs every 10 seconds... (Press Ctrl+C to stop)");
            
            // Simple polling without timestamp filtering
            while (true) {
                try {
                    Thread.sleep(10000); // Wait 10 seconds
                    
                    System.out.println("\n--- Checking for new logs ---");
                    String pollCommand = String.format(
                        "gcloud beta run services logs read %s " +
                        "--project=%s " +
                        "--region=%s " +
                        "--limit=10",
                        config.getServiceName(),
                        config.getProjectId(),
                        config.getRegion()
                    );
                    
                    Runner.runCommand(pollCommand);
                    
                } catch (InterruptedException e) {
                    System.out.println("\n� Log polling stopped by user");
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    System.err.println("⚠️  Error polling logs: " + e.getMessage());
                    Thread.sleep(5000); // Wait before retrying
                }
            }

        } catch (Exception e) {
            System.err.println("Failed to show logs: " + e.getMessage());
            System.err.println("\nYou can manually run:");
            System.err.println("gcloud beta run services logs read " + config.getServiceName() +
                " --project=" + config.getProjectId() + " --region=" + config.getRegion() + " --limit=50");
        }
    }
}