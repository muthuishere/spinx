package tools.muthuishere.spinx.azure.containerapps;

import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.core.management.AzureEnvironment;
import com.azure.core.management.profile.AzureProfile;
import com.azure.core.management.exception.ManagementException;
import com.azure.resourcemanager.AzureResourceManager;
import com.azure.resourcemanager.resources.models.ResourceGroup;
import com.azure.resourcemanager.containerregistry.models.Registry;
import com.azure.resourcemanager.loganalytics.LogAnalyticsManager;
import com.azure.resourcemanager.loganalytics.models.Workspace;
import com.azure.resourcemanager.appcontainers.ContainerAppsApiManager;
import com.azure.resourcemanager.appcontainers.models.ManagedEnvironment;
import com.azure.resourcemanager.appcontainers.models.AppLogsConfiguration;
import com.azure.resourcemanager.appcontainers.models.LogAnalyticsConfiguration;
import com.azure.resourcemanager.appcontainers.models.ContainerApp;
import com.azure.resourcemanager.appcontainers.models.Container;
import com.azure.resourcemanager.appcontainers.models.Configuration;
import com.azure.resourcemanager.appcontainers.models.Template;
import com.azure.resourcemanager.appcontainers.models.Ingress;
import com.azure.resourcemanager.appcontainers.models.Scale;
import com.azure.resourcemanager.appcontainers.models.ScaleRule;
import com.azure.resourcemanager.appcontainers.models.RegistryCredentials;
import tools.muthuishere.spinx.CloudDeployer;
import tools.muthuishere.spinx.Runner;
import tools.muthuishere.spinx.EnvironmentParser;
import org.yaml.snakeyaml.Yaml;

import java.io.FileInputStream;
import java.nio.file.Paths;


public class AzureContainerAppsDeployer implements CloudDeployer {
    
    private AzureContainerAppsConfig config;
    private String configFilename;
    private AzureResourceManager azure;
    private LogAnalyticsManager logAnalyticsManager;
    private ContainerAppsApiManager containerAppsManager;
    
    // Resource classes for tracking created resources
    public static class ContainerAppResources {
        private String appUrl;
        private String appId;
        private String environmentId;
        private String registryId;
        private String workspaceId;
        
        // Getters and setters
        public String getAppUrl() { return appUrl; }
        public void setAppUrl(String appUrl) { this.appUrl = appUrl; }
        
        public String getAppId() { return appId; }
        public void setAppId(String appId) { this.appId = appId; }
        
        public String getEnvironmentId() { return environmentId; }
        public void setEnvironmentId(String environmentId) { this.environmentId = environmentId; }
        
        public String getRegistryId() { return registryId; }
        public void setRegistryId(String registryId) { this.registryId = registryId; }
        
        public String getWorkspaceId() { return workspaceId; }
        public void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }
    }

    @Override
    public void init(String configFile) {
        System.out.println("Azure Container Apps: Initializing with config file: " + configFile);
        this.configFilename = configFile;

        try {
            if (!Paths.get(configFile).toFile().exists()) {
                throw new RuntimeException("Config file not found: " + configFile);
            }
            
            System.out.println("Config file found: " + configFile);
            
            Yaml yaml = new Yaml();
            try (FileInputStream fis = new FileInputStream(configFile)) {
                this.config = yaml.loadAs(fis, AzureContainerAppsConfig.class);
            }
            
            // Convert relative paths to absolute paths based on config file location
            String configDir = Paths.get(configFile).getParent().toString();
            
            // Resolve dockerfilePath relative to config directory
            if (!Paths.get(config.getDockerfilePath()).isAbsolute()) {
                String absoluteDockerPath = Paths.get(configDir, config.getDockerfilePath()).normalize().toString();
                config.setDockerfilePath(absoluteDockerPath);
            }
            
            // Resolve environmentFile relative to config directory
            if (!Paths.get(config.getEnvironmentFile()).isAbsolute()) {
                String absoluteEnvPath = Paths.get(configDir, config.getEnvironmentFile()).normalize().toString();
                config.setEnvironmentFile(absoluteEnvPath);
            }
            
            // Auto-fetch subscription ID from Azure CLI if not provided or placeholder
            if (config.getSubscriptionId() == null || 
                config.getSubscriptionId().equals("your-subscription-id") || 
                config.getSubscriptionId().trim().isEmpty()) {
                String subscriptionId = getDefaultSubscriptionId();
                config.setSubscriptionId(subscriptionId);
                System.out.println("Using default Azure subscription: " + subscriptionId);
            }
            
            System.out.println("Loaded config: " + config);
            
            // Load environment variables using EnvironmentParser
            loadEnvironmentVariables();
            
            // Initialize Azure Resource Manager
            initializeAzureClient();
                
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize Azure Container Apps deployer", e);
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
        System.out.println("📊 Environment parsing complete:");
        System.out.println("   📝 Source: " + result.getSource());
        System.out.println("   📊 Total variables: " + result.getTotalCount());
        if (result.getYamlCount() > 0) {
            System.out.println("   📋 From YAML: " + result.getYamlCount());
        }
        if (result.getEnvFileCount() > 0) {
            System.out.println("   📄 From .env file: " + result.getEnvFileCount());
        }
    }
    
    private void initializeAzureClient() {
        try {
            AzureProfile profile = new AzureProfile(null, config.getSubscriptionId(), AzureEnvironment.AZURE);
            var credential = new DefaultAzureCredentialBuilder()
                .authorityHost(profile.getEnvironment().getActiveDirectoryEndpoint())
                .build();
            
            this.azure = AzureResourceManager
                .authenticate(credential, profile)
                .withSubscription(config.getSubscriptionId());
            
            this.logAnalyticsManager = LogAnalyticsManager
                .authenticate(credential, profile);
            
            this.containerAppsManager = ContainerAppsApiManager
                .authenticate(credential, profile);
            
            System.out.println("✅ Azure Resource Manager initialized for subscription: " + config.getSubscriptionId());
            
        } catch (Exception e) {
            System.err.println("Failed to initialize Azure Resource Manager: " + e.getMessage());
            System.err.println("Please ensure you're logged in with 'az login' or have appropriate credentials configured.");
            throw new RuntimeException("Failed to initialize Azure client", e);
        }
    }

    @Override
    public void setup() {
        System.out.println("Azure Container Apps: Setting up infrastructure for " + config.getContainerAppName());
        
        try {
            // 1. Register required resource providers
            System.out.println("- Registering required Azure resource providers");
            registerResourceProviders();
            
            // 2. Create or verify resource group
            System.out.println("- Creating resource group: " + config.getResourceGroupName());
            createResourceGroup();
            
            // 3. Create Container Registry
            System.out.println("- Creating Azure Container Registry: " + config.getRegistryName());
            createContainerRegistry();
            
            // 4. Create Log Analytics Workspace
            System.out.println("- Creating Log Analytics Workspace: " + config.getWorkspaceName());
            createLogAnalyticsWorkspace();
            
            // 5. Create Container Apps Environment
            System.out.println("- Creating Container Apps Environment: " + config.getEnvironmentName());
            createContainerAppsEnvironment();
            
            System.out.println("✅ Azure Container Apps infrastructure setup completed!");
            
        } catch (Exception e) {
            System.err.println("Setup failed: " + e.getMessage());
            throw new RuntimeException("Failed to setup Azure Container Apps infrastructure", e);
        }
    }

    @Override
    public void deploy() {
        System.out.println("Azure Container Apps: Deploying " + config.getContainerAppName());
        
        try {
            // 0. Validate that setup has been run
            System.out.println("- Validating infrastructure setup...");
            validateSetupHasBeenRun();
            
            // 1. Build and push Docker image
            System.out.println("- Building Docker image from " + config.getDockerfilePath());
            buildAndPushImage();
            
            // 2. Create or update Container App
            System.out.println("- Creating/updating Container App");
            String appUrl = retryContainerAppDeployment();
            
            System.out.println("\n=== Azure Container Apps Deployment Complete ===");
            System.out.println("App Name: " + config.getContainerAppName());
            System.out.println("Resource Group: " + config.getResourceGroupName());
            System.out.println("App URL: " + appUrl);
            System.out.println("Location: " + config.getLocation());
            System.out.println("\nMCP Configuration:");
            System.out.println("Add this to your MCP client config:");
            System.out.println("\"" + config.getContainerAppName() + "\": {");
            System.out.println("  \"type\": \"http\",");
            System.out.println("  \"url\": \"" + appUrl + "/mcp\"");
            System.out.println("}");
            System.out.println("\nNote: It may take a few minutes for the service to be fully available");
            
        } catch (Exception e) {
            System.err.println("Deployment failed: " + e.getMessage());
            throw new RuntimeException("Deployment failed", e);
        }
    }

    @Override
    public void destroy() {
        System.out.println("Azure Container Apps: Destroying ALL infrastructure for " + config.getContainerAppName());
        System.out.println("⚠️  This will delete ALL resources to avoid any billing charges!");
        
        try {
            // Initialize Azure client to ensure we can perform deletions
            initializeAzureClient();
            
            // 1. Delete Container App
            System.out.println("- Deleting Container App");
            deleteContainerApp();
            
            // 2. Delete Container Apps Environment
            System.out.println("- Deleting Container Apps Environment");
            deleteContainerAppsEnvironment();
            
            // 3. Delete Log Analytics Workspace (ALWAYS delete - no sharing)
            System.out.println("- Deleting Log Analytics Workspace");
            deleteLogAnalyticsWorkspace();
            
            // 4. Delete Container Registry (ALWAYS delete - no sharing)
            System.out.println("- Deleting Container Registry");
            deleteContainerRegistry();
            
            // 5. List any remaining resources before deleting resource group
            System.out.println("- Checking for any remaining resources");
            listRemainingResources();
            
            // 6. ALWAYS delete resource group - we created it, we delete it
            System.out.println("- Deleting Resource Group");
            deleteResourceGroup();
            
            System.out.println("✅ Azure Container Apps infrastructure COMPLETELY cleaned up - no billing charges!");
            System.out.println("💰 All resources deleted to prevent any unexpected Azure costs!");
            
        } catch (Exception e) {
            System.err.println("Cleanup failed: " + e.getMessage());
            System.err.println("⚠️  CRITICAL: Please check Azure portal manually to ensure all resources are deleted!");
            System.err.println("⚠️  Resource Group: " + config.getResourceGroupName());
            System.err.println("⚠️  Subscription: " + config.getSubscriptionId());
        }
    }
    
    // Infrastructure setup methods
    private void validateSetupHasBeenRun() {
        try {
            String resourceGroupName = config.getResourceGroupName();
            String registryName = config.getRegistryName();
            String environmentName = config.getEnvironmentName();
            
            System.out.println("    Checking resource group: " + resourceGroupName);
            
            // Check if resource group exists
            try {
                String result = Runner.runAzureCliWithOutput("group", "show", 
                    "--name", resourceGroupName, "--query", "name", "--output", "tsv");
                if (result.trim().isEmpty()) {
                    throw new RuntimeException("Resource group not found");
                }
            } catch (Exception e) {
                System.err.println("\n❌ Setup validation failed!");
                System.err.println("Resource group '" + resourceGroupName + "' does not exist.");
                System.err.println("\nPlease run the setup command first:");
                System.err.println("  spinx setup azure");
                System.err.println("\nOr create the resource group manually:");
                System.err.println("  az group create --name " + resourceGroupName + " --location \"" + config.getLocation() + "\"");
                throw new RuntimeException("Infrastructure setup required. Resource group '" + resourceGroupName + "' not found.", e);
            }
            
            System.out.println("    Checking container registry: " + registryName);
            
            // Check if container registry exists
            try {
                String result = Runner.runAzureCliWithOutput("acr", "show", 
                    "--name", registryName, "--resource-group", resourceGroupName, 
                    "--query", "name", "--output", "tsv");
                if (result.trim().isEmpty()) {
                    throw new RuntimeException("Container registry not found");
                }
            } catch (Exception e) {
                System.err.println("\n❌ Setup validation failed!");
                System.err.println("Container Registry '" + registryName + "' does not exist in resource group '" + resourceGroupName + "'.");
                System.err.println("\nPlease run the setup command first:");
                System.err.println("  spinx setup azure");
                throw new RuntimeException("Infrastructure setup required. Container Registry '" + registryName + "' not found.", e);
            }
            
            System.out.println("    Checking Container Apps environment: " + environmentName);
            
            // Check if Container Apps environment exists
            try {
                String result = Runner.runAzureCliWithOutput("containerapp", "env", "show", 
                    "--name", environmentName, "--resource-group", resourceGroupName, 
                    "--query", "name", "--output", "tsv");
                if (result.trim().isEmpty()) {
                    throw new RuntimeException("Container Apps environment not found");
                }
            } catch (Exception e) {
                System.err.println("\n❌ Setup validation failed!");
                System.err.println("Container Apps Environment '" + environmentName + "' does not exist in resource group '" + resourceGroupName + "'.");
                System.err.println("\nPlease run the setup command first:");
                System.err.println("  spinx setup azure");
                throw new RuntimeException("Infrastructure setup required. Container Apps Environment '" + environmentName + "' not found.", e);
            }
            
            System.out.println("    ✅ Infrastructure validation completed successfully");
            
        } catch (RuntimeException e) {
            throw e; // Re-throw runtime exceptions with our custom messages
        } catch (Exception e) {
            System.err.println("\n❌ Setup validation failed!");
            System.err.println("Error validating infrastructure: " + e.getMessage());
            System.err.println("\nPlease run the setup command first:");
            System.err.println("  spinx setup azure");
            throw new RuntimeException("Infrastructure validation failed", e);
        }
    }
    
    private String getDefaultSubscriptionId() {
        try {
            System.out.println("Fetching default Azure subscription ID...");
            String subscriptionId = Runner.runAzureCliWithOutput("account", "show", 
                "--query", "id", "--output", "tsv").trim();
            
            if (subscriptionId.isEmpty()) {
                throw new RuntimeException("No default subscription found");
            }
            
            return subscriptionId;
        } catch (Exception e) {
            System.err.println("Failed to get default subscription ID: " + e.getMessage());
            System.err.println("Please ensure you're logged in with 'az login' and have a default subscription set.");
            throw new RuntimeException("Failed to get subscription ID", e);
        }
    }
    
    private void registerResourceProviders() {
        try {
            System.out.println("Registering Microsoft.App resource provider...");
            azure.providers().register("Microsoft.App");
            System.out.println("✅ Microsoft.App resource provider registered");
            
            System.out.println("Registering Microsoft.OperationalInsights resource provider...");
            azure.providers().register("Microsoft.OperationalInsights");
            System.out.println("✅ Microsoft.OperationalInsights resource provider registered");
            
        } catch (Exception e) {
            System.err.println("Failed to register resource providers: " + e.getMessage());
            System.err.println("You may need to register these manually:");
            System.err.println("  az provider register --namespace Microsoft.App --wait");
            System.err.println("  az provider register --namespace Microsoft.OperationalInsights --wait");
            throw new RuntimeException("Failed to register resource providers", e);
        }
    }
    
    private void createResourceGroup() {
        try {
            String resourceGroupName = config.getResourceGroupName();
            
            // Check if resource group exists
            ResourceGroup existingRg = null;
            try {
                existingRg = azure.resourceGroups().getByName(resourceGroupName);
            } catch (Exception e) {
                // Resource group doesn't exist, which is expected
            }
            
            if (existingRg != null) {
                System.out.println("Resource group already exists: " + resourceGroupName);
                return;
            }
            
            // Create resource group
            System.out.println("Creating resource group: " + resourceGroupName);
            ResourceGroup resourceGroup = azure.resourceGroups()
                .define(resourceGroupName)
                .withRegion(config.getLocation())
                .create();
            
            System.out.println("✅ Created resource group: " + resourceGroup.name());
            
        } catch (Exception e) {
            System.err.println("Failed to create resource group: " + e.getMessage());
            throw new RuntimeException("Failed to create resource group", e);
        }
    }
    
    private void createContainerRegistry() {
        try {
            String registryName = config.getRegistryName();
            String resourceGroupName = config.getResourceGroupName();
            
            // Check if registry exists
            Registry existingRegistry = null;
            try {
                existingRegistry = azure.containerRegistries().getByResourceGroup(resourceGroupName, registryName);
            } catch (Exception e) {
                // Registry doesn't exist, which is expected
            }
            
            if (existingRegistry != null) {
                System.out.println("Container Registry already exists: " + registryName);
                return;
            }
            
            // Create container registry
            System.out.println("Creating Container Registry: " + registryName);
            Registry registry = azure.containerRegistries()
                .define(registryName)
                .withRegion(config.getLocation())
                .withExistingResourceGroup(resourceGroupName)
                .withBasicSku()
                .withRegistryNameAsAdminUser()
                .create();
            
            System.out.println("✅ Created Container Registry: " + registry.name());
            
        } catch (Exception e) {
            System.err.println("Failed to create Container Registry: " + e.getMessage());
            throw new RuntimeException("Failed to create Container Registry", e);
        }
    }
    
    private void createLogAnalyticsWorkspace() {
        try {
            String workspaceName = config.getWorkspaceName();
            String resourceGroupName = config.getResourceGroupName();
            
            // Check if workspace exists
            try {
                Workspace existingWorkspace = logAnalyticsManager.workspaces()
                    .getByResourceGroup(resourceGroupName, workspaceName);
                if (existingWorkspace != null) {
                    System.out.println("Log Analytics Workspace already exists: " + workspaceName);
                    return;
                }
            } catch (ManagementException e) {
                if (e.getResponse().getStatusCode() == 404) {
                    // Workspace doesn't exist, we'll create it
                    System.out.println("Log Analytics Workspace not found, creating: " + workspaceName);
                } else {
                    throw e;
                }
            }
            
            // Create Log Analytics Workspace
            System.out.println("Creating Log Analytics Workspace: " + workspaceName);
            Workspace workspace = logAnalyticsManager.workspaces()
                .define(workspaceName)
                .withRegion(config.getLocation())
                .withExistingResourceGroup(resourceGroupName)
                .create();
            
            System.out.println("✅ Created Log Analytics Workspace: " + workspace.name());
            
        } catch (Exception e) {
            System.err.println("Failed to create Log Analytics Workspace: " + e.getMessage());
            throw new RuntimeException("Failed to create Log Analytics Workspace", e);
        }
    }
    
    private void createContainerAppsEnvironment() {
        try {
            String environmentName = config.getEnvironmentName();
            String resourceGroupName = config.getResourceGroupName();

            // Check if environment exists
            try {
                ManagedEnvironment existingEnvironment = containerAppsManager.managedEnvironments()
                    .getByResourceGroup(resourceGroupName, environmentName);
                if (existingEnvironment != null) {
                    System.out.println("Container Apps Environment already exists: " + environmentName);
                    return;
                }
            } catch (ManagementException e) {
                if (e.getResponse().getStatusCode() == 404) {
                    // Environment doesn't exist, we'll create it
                    System.out.println("Container Apps Environment not found, creating: " + environmentName);
                } else {
                    throw e;
                }
            }
            
            // Get workspace ID for the environment
            String workspaceId = getLogAnalyticsWorkspaceId();
            String customerId = extractWorkspaceId(workspaceId);
            String sharedKey = getLogAnalyticsWorkspaceKey(workspaceId);
            
            // Create Log Analytics configuration
            LogAnalyticsConfiguration logAnalyticsConfig = new LogAnalyticsConfiguration()
                .withCustomerId(customerId)
                .withSharedKey(sharedKey);
            
            // Create App Logs configuration
            AppLogsConfiguration appLogsConfig = new AppLogsConfiguration()
                .withDestination("log-analytics")
                .withLogAnalyticsConfiguration(logAnalyticsConfig);
            
            System.out.println("Creating Container Apps Environment: " + environmentName);
            ManagedEnvironment environment = containerAppsManager.managedEnvironments()
                .define(environmentName)
                .withRegion(config.getLocation())
                .withExistingResourceGroup(resourceGroupName)
                .withAppLogsConfiguration(appLogsConfig)
                .create();
            
            System.out.println("✅ Created Container Apps Environment: " + environment.name());
            
        } catch (Exception e) {
            System.err.println("Failed to create Container Apps Environment: " + e.getMessage());
            throw new RuntimeException("Failed to create Container Apps Environment", e);
        }
    }
    
    private String getLogAnalyticsWorkspaceId() {
        try {
            String workspaceName = config.getWorkspaceName();
            String resourceGroupName = config.getResourceGroupName();
            
            Workspace workspace = logAnalyticsManager.workspaces()
                .getByResourceGroup(resourceGroupName, workspaceName);
            if (workspace == null) {
                throw new RuntimeException("Log Analytics Workspace not found: " + workspaceName);
            }
            
            return workspace.id();
            
        } catch (Exception e) {
            throw new RuntimeException("Failed to get Log Analytics Workspace ID", e);
        }
    }
    
    private String extractWorkspaceId(String fullWorkspaceId) {
        // Extract the customer ID (GUID) from the full workspace ID
        // Full ID format: /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.OperationalInsights/workspaces/{name}
        try {
            String workspaceName = config.getWorkspaceName();
            String resourceGroupName = config.getResourceGroupName();
            
            Workspace workspace = logAnalyticsManager.workspaces()
                .getByResourceGroup(resourceGroupName, workspaceName);
            if (workspace == null) {
                throw new RuntimeException("Log Analytics Workspace not found: " + workspaceName);
            }
            
            return workspace.customerId();
            
        } catch (Exception e) {
            throw new RuntimeException("Failed to get Log Analytics Workspace customer ID", e);
        }
    }
    
    private String getLogAnalyticsWorkspaceKey(String workspaceId) {
        try {
            String workspaceName = config.getWorkspaceName();
            String resourceGroupName = config.getResourceGroupName();
         
            var sharedKeys = logAnalyticsManager.sharedKeysOperations()
                .getSharedKeys(resourceGroupName, workspaceName);
            
            return sharedKeys.primarySharedKey();
            
        } catch (Exception e) {
            throw new RuntimeException("Failed to get Log Analytics Workspace shared key", e);
        }
    }
    
    // Deployment methods
    private void buildAndPushImage() {
        try {
            String fullImageName = config.getFullImageName();
            String registryName = config.getRegistryName();
            String resourceGroupName = config.getResourceGroupName();
            
            // Build Docker image with ultra-fast buildx optimizations
            System.out.println("- Building Docker image: " + fullImageName);
            Runner.runDockerBuildxFast(fullImageName, config.getDockerfilePath(), ".");
            
            // Get ACR credentials using Azure SDK
            System.out.println("- Getting ACR credentials");
            Registry registry = azure.containerRegistries().getByResourceGroup(resourceGroupName, registryName);
            if (registry == null) {
                throw new RuntimeException("Container Registry not found: " + registryName);
            }
            
            var credentials = registry.getCredentials();
            String username = credentials.username();
            String password = credentials.accessKeys().get(com.azure.resourcemanager.containerregistry.models.AccessKeyType.PRIMARY);
            
            // Login to ACR using Docker
            System.out.println("- Logging into Azure Container Registry");
            System.out.println("  Registry: " + registry.loginServerUrl());
            System.out.println("  Username: " + username);
            System.out.println("  Password: " + Runner.maskSensitiveData(password));
            Runner.runSecureCommand("echo " + password + " | docker login " + registry.loginServerUrl() + " --username " + username + " --password-stdin",
                "docker login " + registry.loginServerUrl() + " --username " + username + " --password-stdin");
            
            // Push to ACR
            System.out.println("- Pushing image to ACR: " + fullImageName);
            Runner.runDocker("push", fullImageName);
            
            // Wait for image to be fully available in ACR
            System.out.println("- Waiting for image to be available in ACR...");
            waitForImageAvailabilityInACR(fullImageName, registry.loginServerUrl());
            
        } catch (Exception e) {
            System.err.println("Failed to build and push image: " + e.getMessage());
            throw new RuntimeException("Failed to build and push image", e);
        }
    }

    private void waitForImageAvailabilityInACR(String fullImageName, String registryUrl) {
        int maxRetries = 6;
        int currentRetry = 0;
        int baseDelaySeconds = 15;
        
        String registryName = config.getRegistryName();
        String[] imageParts = fullImageName.split(":");
        String tag = imageParts.length > 1 ? imageParts[1] : "latest";
        String repository = config.getImageName(); // Use getImageName() instead of getContainerAppName()
        
        while (currentRetry < maxRetries) {
            System.out.println("    Verifying image availability in ACR (attempt " + (currentRetry + 1) + "/" + maxRetries + ")...");
            
            try {
                // Use Azure CLI directly - simple and reliable
                String result = Runner.runAzureCliWithOutput(
                    "acr", "repository", "show",
                    "--name", registryName,
                    "--image", repository + ":" + tag,
                    "--query", "digest",
                    "--output", "tsv"
                );
                
                if (result != null && result.trim().startsWith("sha256:")) {
                    System.out.println("    ✅ Image verified as available in ACR: " + result.trim().substring(0, Math.min(20, result.trim().length())) + "...");
                    return;
                }
                
            } catch (Exception e) {
                String errorMessage = e.getMessage();
                if (errorMessage != null && (errorMessage.contains("not found") || errorMessage.contains("does not exist"))) {
                    // Image not found yet, continue retrying
                } else {
                    System.out.println("    Error checking image: " + errorMessage);
                }
            }
            
            currentRetry++;
            if (currentRetry < maxRetries) {
                int delaySeconds = baseDelaySeconds + (currentRetry * 5); // Linear backoff
                System.out.println("    Image not yet available in ACR, waiting " + delaySeconds + " seconds before retry...");
                try {
                    Thread.sleep(delaySeconds * 1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    System.out.println("    ⚠️  Image verification interrupted. Proceeding with deployment...");
                    return;
                }
            }
        }
        
        System.out.println("    ⚠️  Could not verify image availability in ACR after " + maxRetries + " attempts");
        System.out.println("    Proceeding with deployment - Container Apps will pull the image when ready.");
    }

    private String retryContainerAppDeployment() {
        int maxRetries = 3;
        int currentRetry = 0;
        Exception lastException = null;
        
        while (currentRetry < maxRetries) {
            try {
                System.out.println("Attempting Container App deployment (attempt " + (currentRetry + 1) + "/" + maxRetries + ")...");
                return createOrUpdateContainerApp();
                
            } catch (Exception e) {
                lastException = e;
                
                // Check if this is an image not found error
                boolean isImageNotFound = e.getMessage() != null && 
                    (e.getMessage().contains("image") && 
                     (e.getMessage().contains("not found") || e.getMessage().contains("pull") || e.getMessage().contains("manifest")));
                
                currentRetry++;
                
                if (isImageNotFound && currentRetry < maxRetries) {
                    int waitTime = 30 + (currentRetry * 30); // 30s, 60s, 90s
                    System.out.println("    Image not found error detected. Waiting " + waitTime + " seconds for image propagation...");
                    try {
                        Thread.sleep(waitTime * 1000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Container App deployment retry interrupted", ie);
                    }
                } else if (currentRetry < maxRetries) {
                    System.out.println("    Container App deployment failed with error: " + e.getMessage());
                    System.out.println("    Retrying in 15 seconds...");
                    try {
                        Thread.sleep(15000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Container App deployment retry interrupted", ie);
                    }
                } else {
                    System.out.println("    Maximum retry attempts reached");
                    break;
                }
            }
        }
        
        throw new RuntimeException("Failed to deploy Container App after " + maxRetries + " attempts", lastException);
    }
    
    private String createOrUpdateContainerApp() {
        try {
            String containerAppName = config.getContainerAppName();
            String resourceGroupName = config.getResourceGroupName();
            String fullImageName = config.getFullImageName();
            
            // Check if Container App exists using Azure SDK
            ContainerApp existingApp = null;
            try {
                existingApp = containerAppsManager.containerApps()
                    .getByResourceGroup(resourceGroupName, containerAppName);
                System.out.println("Container App found, updating: " + containerAppName);
            } catch (ManagementException e) {
                if (e.getResponse().getStatusCode() == 404) {
                    System.out.println("Container App not found, creating: " + containerAppName);
                } else {
                    throw e;
                }
            }
            
            // Get ACR credentials
            String registryName = config.getRegistryName();
            Registry registry = azure.containerRegistries().getByResourceGroup(resourceGroupName, registryName);
            var credentials = registry.getCredentials();
            String registryServer = registry.loginServerUrl();
            String username = credentials.username();
            String password = credentials.accessKeys().get(com.azure.resourcemanager.containerregistry.models.AccessKeyType.PRIMARY);
            
            // Get managed environment
            ManagedEnvironment environment = containerAppsManager.managedEnvironments()
                .getByResourceGroup(resourceGroupName, config.getEnvironmentName());
            
            if (existingApp != null) {
                // Update existing app
                System.out.println("Updating existing Container App...");
                ContainerApp updatedApp = existingApp.update()
                    .withTemplate(createContainerTemplate(fullImageName, registryServer, username, password))
                    .apply();
                
                String appUrl = getContainerAppUrlFromApp(updatedApp);
                System.out.println("✅ Updated Container App: " + containerAppName);
                return appUrl;
            } else {
                // Create new app
                System.out.println("Creating new Container App...");
                ContainerApp containerApp = containerAppsManager.containerApps()
                    .define(containerAppName)
                    .withRegion(config.getLocation())
                    .withExistingResourceGroup(resourceGroupName)
                    .withManagedEnvironmentId(environment.id())
                    .withConfiguration(createContainerConfiguration(registryServer, username, password))
                    .withTemplate(createContainerTemplate(fullImageName, registryServer, username, password))
                    .create();
                
                String appUrl = getContainerAppUrlFromApp(containerApp);
                System.out.println("✅ Created Container App: " + containerAppName);
                return appUrl;
            }
            
        } catch (Exception e) {
            System.err.println("Failed to create/update Container App: " + e.getMessage());
            throw new RuntimeException("Failed to create/update Container App", e);
        }
    }
    
    private Configuration createContainerConfiguration(String registryServer, String username, String password) {
        try {
            // Create registry credentials
            RegistryCredentials registryCredentials = new RegistryCredentials()
                .withServer(registryServer)
                .withUsername(username)
                .withPasswordSecretRef("registry-password");
            
            // Create ingress configuration
            Ingress ingress = new Ingress()
                .withExternal(config.isExternalIngress())
                .withTargetPort(config.getTargetPort());
            
            // Create configuration
            Configuration configuration = new Configuration()
                .withRegistries(java.util.List.of(registryCredentials))
                .withSecrets(java.util.List.of(
                    new com.azure.resourcemanager.appcontainers.models.Secret()
                        .withName("registry-password")
                        .withValue(password)
                ))
                .withIngress(ingress);
            
            return configuration;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create container configuration", e);
        }
    }
    
    private Template createContainerTemplate(String fullImageName, String registryServer, String username, String password) {
        try {
            System.out.println("📋 Adding " + config.getEnvironmentVariables().size() + " environment variables to container:");
            for (java.util.Map.Entry<String, String> entry : config.getEnvironmentVariables().entrySet()) {
                String value = entry.getValue();
                String displayValue = value.length() > 20 ? value.substring(0, 20) + "..." : value;
                System.out.println("   🔑 " + entry.getKey() + "=" + displayValue);
            }
            
            // Create environment variables list for Azure Container
            java.util.List<com.azure.resourcemanager.appcontainers.models.EnvironmentVar> envVars = 
                config.getEnvironmentVariables().entrySet().stream()
                    .map(entry -> new com.azure.resourcemanager.appcontainers.models.EnvironmentVar()
                        .withName(entry.getKey())
                        .withValue(entry.getValue()))
                    .collect(java.util.stream.Collectors.toList());
            
            // Create container
            Container container = new Container()
                .withName(config.getContainerAppName())
                .withImage(fullImageName)
                .withEnv(envVars)
                .withResources(new com.azure.resourcemanager.appcontainers.models.ContainerResources()
                    .withCpu(Double.parseDouble(config.getCpu()))
                    .withMemory(config.getMemory()));
            
            // Create scale rules
            ScaleRule scaleRule = new ScaleRule()
                .withName("default-scale-rule")
                .withHttp(new com.azure.resourcemanager.appcontainers.models.HttpScaleRule()
                    .withMetadata(java.util.Map.of("concurrentRequests", "10")));
            
            // Create scale configuration
            Scale scale = new Scale()
                .withMinReplicas(config.getMinReplicas())
                .withMaxReplicas(config.getMaxReplicas())
                .withRules(java.util.List.of(scaleRule));
            
            // Create template
            Template template = new Template()
                .withContainers(java.util.List.of(container))
                .withScale(scale);
            
            return template;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create container template", e);
        }
    }
    
    private String getContainerAppUrlFromApp(ContainerApp containerApp) {
        try {
            if (containerApp.configuration() != null && 
                containerApp.configuration().ingress() != null && 
                containerApp.configuration().ingress().fqdn() != null) {
                return "https://" + containerApp.configuration().ingress().fqdn();
            }
            return "URL not available";
        } catch (Exception e) {
            System.err.println("Failed to get Container App URL from app: " + e.getMessage());
            return "URL not available";
        }
    }
    
    // Cleanup methods
    private void deleteContainerApp() {
        try {
            String containerAppName = config.getContainerAppName();
            String resourceGroupName = config.getResourceGroupName();
            
            try {
                var containerApp = containerAppsManager.containerApps()
                    .getByResourceGroup(resourceGroupName, containerAppName);
                if (containerApp != null) {
                    containerAppsManager.containerApps().deleteById(containerApp.id());
                    System.out.println("✅ Deleted Container App: " + containerAppName);
                } else {
                    System.out.println("Container App not found or already deleted: " + containerAppName);
                }
            } catch (ManagementException e) {
                if (e.getResponse().getStatusCode() == 404) {
                    System.out.println("Container App not found or already deleted: " + containerAppName);
                } else {
                    throw e;
                }
            }
        } catch (Exception e) {
            System.err.println("Failed to delete Container App: " + e.getMessage());
        }
    }
    
    private void deleteContainerAppsEnvironment() {
        try {
            String environmentName = config.getEnvironmentName();
            String resourceGroupName = config.getResourceGroupName();
            
            try {
                ManagedEnvironment environment = containerAppsManager.managedEnvironments()
                    .getByResourceGroup(resourceGroupName, environmentName);
                if (environment != null) {
                    containerAppsManager.managedEnvironments().deleteById(environment.id());
                    System.out.println("✅ Deleted Container Apps Environment: " + environmentName);
                } else {
                    System.out.println("Container Apps Environment not found or already deleted: " + environmentName);
                }
            } catch (ManagementException e) {
                if (e.getResponse().getStatusCode() == 404) {
                    System.out.println("Container Apps Environment not found or already deleted: " + environmentName);
                } else {
                    throw e;
                }
            }
        } catch (Exception e) {
            System.err.println("Failed to delete Container Apps Environment: " + e.getMessage());
        }
    }
    
    private void deleteLogAnalyticsWorkspace() {
        try {
            String workspaceName = config.getWorkspaceName();
            String resourceGroupName = config.getResourceGroupName();
            
            try {
                Workspace workspace = logAnalyticsManager.workspaces()
                    .getByResourceGroup(resourceGroupName, workspaceName);
                if (workspace != null) {
                    logAnalyticsManager.workspaces().deleteById(workspace.id());
                    System.out.println("✅ Deleted Log Analytics Workspace: " + workspaceName);
                } else {
                    System.out.println("Log Analytics Workspace not found or already deleted: " + workspaceName);
                }
            } catch (ManagementException e) {
                if (e.getResponse().getStatusCode() == 404) {
                    System.out.println("Log Analytics Workspace not found or already deleted: " + workspaceName);
                } else {
                    throw e;
                }
            }
        } catch (Exception e) {
            System.err.println("Failed to delete Log Analytics Workspace: " + e.getMessage());
        }
    }
    
    private void deleteContainerRegistry() {
        try {
            String registryName = config.getRegistryName();
            String resourceGroupName = config.getResourceGroupName();
            
            try {
                Registry registry = azure.containerRegistries().getByResourceGroup(resourceGroupName, registryName);
                if (registry != null) {
                    azure.containerRegistries().deleteById(registry.id());
                    System.out.println("✅ Deleted Container Registry: " + registryName);
                } else {
                    System.out.println("Container Registry not found or already deleted: " + registryName);
                }
            } catch (ManagementException e) {
                if (e.getResponse().getStatusCode() == 404) {
                    System.out.println("Container Registry not found or already deleted: " + registryName);
                } else {
                    throw e;
                }
            }
        } catch (Exception e) {
            System.err.println("Failed to delete Container Registry: " + e.getMessage());
        }
    }
    
    private void deleteResourceGroup() {
        try {
            String resourceGroupName = config.getResourceGroupName();
            
            try {
                ResourceGroup resourceGroup = azure.resourceGroups().getByName(resourceGroupName);
                if (resourceGroup != null) {
                    System.out.println("🗑️  Force deleting resource group and ALL its contents: " + resourceGroupName);
                    azure.resourceGroups().deleteByName(resourceGroupName);
                    System.out.println("✅ Deleted resource group: " + resourceGroupName);
                } else {
                    System.out.println("Resource group not found or already deleted: " + resourceGroupName);
                }
            } catch (ManagementException e) {
                if (e.getResponse().getStatusCode() == 404) {
                    System.out.println("Resource group not found or already deleted: " + resourceGroupName);
                } else {
                    throw e;
                }
            }
        } catch (Exception e) {
            System.err.println("Failed to delete resource group: " + e.getMessage());
            System.err.println("⚠️  IMPORTANT: Please manually delete resource group '" + config.getResourceGroupName() + "' in Azure portal to avoid charges!");
        }
    }
    
    private void listRemainingResources() {
        try {
            String resourceGroupName = config.getResourceGroupName();
            
            var resources = azure.genericResources().listByResourceGroup(resourceGroupName);
            var resourceList = resources.stream().toList();
            
            if (resourceList.isEmpty()) {
                System.out.println("✅ No remaining resources found in resource group");
            } else {
                System.out.println("⚠️  Found " + resourceList.size() + " remaining resources:");
                for (var resource : resourceList) {
                    System.out.println("   - " + resource.type() + ": " + resource.name());
                }
                System.out.println("🗑️  These will be deleted with the resource group");
            }
        } catch (Exception e) {
            System.out.println("Could not list remaining resources: " + e.getMessage());
        }
    }
    
    @Override
    public void showLogs() {
        System.out.println("Azure Container Apps: Streaming logs for " + config.getContainerAppName());
        System.out.println("================================================================================");
        
        try {
            // Use a process builder to capture output line by line
            ProcessBuilder pb = new ProcessBuilder(
                "az", "containerapp", "logs", "show",
                "--name", config.getContainerAppName(),
                "--resource-group", config.getResourceGroupName(),
                "--follow"
            );
            
            Process process = pb.start();
            
            // Read output line by line and parse JSON
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream()))) {
                
                String line;
                while ((line = reader.readLine()) != null) {
                    parseAndDisplayLogLine(line);
                }
            }
                
        } catch (Exception e) {
            System.err.println("Failed to stream logs: " + e.getMessage());
            System.err.println("Falling back to basic log streaming...");
            
            // Fallback to basic CLI streaming
            try {
                Runner.runAzureCli("containerapp", "logs", "show",
                    "--name", config.getContainerAppName(),
                    "--resource-group", config.getResourceGroupName(),
                    "--follow");
            } catch (Exception fallbackError) {
                System.err.println("Fallback also failed: " + fallbackError.getMessage());
            }
        }
    }
    
    private void parseAndDisplayLogLine(String jsonLine) {
        try {
            if (jsonLine.trim().isEmpty()) return;
            
            // Simple JSON parsing for the log format
            if (jsonLine.contains("\"TimeStamp\"") && jsonLine.contains("\"Log\"")) {
                // Extract timestamp
                String timestamp = extractJsonField(jsonLine, "TimeStamp");
                String logMessage = extractJsonField(jsonLine, "Log");
                
                // Clean up the timestamp (remove microseconds for readability)
                if (timestamp.contains(".")) {
                    timestamp = timestamp.substring(0, timestamp.indexOf('.')) + "Z";
                }
                
                // Skip empty or null log messages
                if (logMessage.equals("null") || logMessage.trim().isEmpty()) {
                    return;
                }
                
                // Format: [timestamp] log_message
                System.out.printf("[%s] %s%n", timestamp.replace("T", " ").replace("Z", ""), logMessage);
            } else {
                // If it's not JSON format, just print as-is
                System.out.println(jsonLine);
            }
        } catch (Exception e) {
            // If parsing fails, just print the raw line
            System.out.println(jsonLine);
        }
    }
    
    private String extractJsonField(String jsonLine, String fieldName) {
        try {
            String searchStr = "\"" + fieldName + "\": \"";
            int startIndex = jsonLine.indexOf(searchStr);
            if (startIndex == -1) return "";
            
            startIndex += searchStr.length();
            int endIndex = jsonLine.indexOf("\"", startIndex);
            if (endIndex == -1) return "";
            
            return jsonLine.substring(startIndex, endIndex);
        } catch (Exception e) {
            return "";
        }
    }
}