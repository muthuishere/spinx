package tools.muthuishere.spinx.azure.containerapps;

import com.fasterxml.jackson.annotation.JsonIgnore;

public class AzureContainerAppsConfig {
    
    // Core Azure configuration
    private String subscriptionId;
    private String resourceGroupName;
    private String location = "East US";
    private String serviceName;
    
    // Container configuration
    private String dockerfilePath = "Dockerfile";
    private String environmentFile = ".env";
    private int containerPort = 8080;
    private java.util.Map<String, String> environmentVariables = new java.util.HashMap<>();
    
    // Container Apps configuration (computed from serviceName)
    
    // Resource configuration
    private String cpu = "0.25";
    private String memory = "0.5Gi";
    private int minReplicas = 0;
    private int maxReplicas = 10;
    
    // Health check configuration
    private String healthCheckPath = "/api/health";
    private int healthCheckIntervalSeconds = 30;
    private int deploymentTimeoutMinutes = 10;
    
    // Constructors
    public AzureContainerAppsConfig() {}
    
    // Getters and Setters
    public String getSubscriptionId() { return subscriptionId; }
    public void setSubscriptionId(String subscriptionId) { this.subscriptionId = subscriptionId; }
    
    public String getResourceGroupName() { 
        return resourceGroupName != null ? resourceGroupName : (serviceName != null ? serviceName + "-rg" : null);
    }
    public void setResourceGroupName(String resourceGroupName) { this.resourceGroupName = resourceGroupName; }
    
    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }
    
    public String getServiceName() { return serviceName; }
    public void setServiceName(String serviceName) { this.serviceName = serviceName; }
    
    public String getDockerfilePath() { return dockerfilePath; }
    public void setDockerfilePath(String dockerfilePath) { this.dockerfilePath = dockerfilePath; }
    
    public String getEnvironmentFile() { return environmentFile; }
    public void setEnvironmentFile(String environmentFile) { this.environmentFile = environmentFile; }
    
    public int getContainerPort() { return containerPort; }
    public void setContainerPort(int containerPort) { this.containerPort = containerPort; }
    
    public java.util.Map<String, String> getEnvironmentVariables() { return environmentVariables; }
    public void setEnvironmentVariables(java.util.Map<String, String> environmentVariables) { 
        this.environmentVariables = environmentVariables != null ? environmentVariables : new java.util.HashMap<>(); 
    }
    
    public String getCpu() { return cpu; }
    public void setCpu(String cpu) { this.cpu = cpu; }
    
    public String getMemory() { return memory; }
    public void setMemory(String memory) { this.memory = memory; }
    
    public int getMinReplicas() { return minReplicas; }
    public void setMinReplicas(int minReplicas) { this.minReplicas = minReplicas; }
    
    public int getMaxReplicas() { return maxReplicas; }
    public void setMaxReplicas(int maxReplicas) { this.maxReplicas = maxReplicas; }
    
    public String getHealthCheckPath() { return healthCheckPath; }
    public void setHealthCheckPath(String healthCheckPath) { this.healthCheckPath = healthCheckPath; }
    
    public int getHealthCheckIntervalSeconds() { return healthCheckIntervalSeconds; }
    public void setHealthCheckIntervalSeconds(int healthCheckIntervalSeconds) { this.healthCheckIntervalSeconds = healthCheckIntervalSeconds; }
    
    public int getDeploymentTimeoutMinutes() { return deploymentTimeoutMinutes; }
    public void setDeploymentTimeoutMinutes(int deploymentTimeoutMinutes) { this.deploymentTimeoutMinutes = deploymentTimeoutMinutes; }
    
    // Always return true for external ingress (public access)
    public boolean isExternalIngress() { return true; }
    
    // Always use containerPort for target port
    public int getTargetPort() { return containerPort; }
    
    // Computed getters (derived from serviceName)
    @JsonIgnore
    public String getEnvironmentName() {
        return serviceName != null ? serviceName + "-env" : null;
    }
    
    @JsonIgnore
    public String getContainerAppName() {
        return serviceName != null ? serviceName + "-app" : null;
    }
    
    @JsonIgnore
    public String getRegistryName() {
        // Registry names must be globally unique and alphanumeric only
        return serviceName != null ? serviceName.replaceAll("[^a-zA-Z0-9]", "") + "registry" : null;
    }
    
    @JsonIgnore
    public String getWorkspaceName() {
        return serviceName != null ? serviceName + "-workspace" : null;
    }
    
    @JsonIgnore
    public String getImageName() {
        return serviceName != null ? serviceName : null;
    }
    
    @JsonIgnore
    public String getFullImageName() {
        return getRegistryName() + ".azurecr.io/" + getImageName() + ":latest";
    }
    
    @Override
    public String toString() {
        return "AzureContainerAppsConfig{" +
                "subscriptionId='" + subscriptionId + '\'' +
                ", resourceGroupName='" + resourceGroupName + '\'' +
                ", location='" + location + '\'' +
                ", serviceName='" + serviceName + '\'' +
                ", dockerfilePath='" + dockerfilePath + '\'' +
                ", environmentFile='" + environmentFile + '\'' +
                ", containerPort=" + containerPort +
                ", environmentName='" + getEnvironmentName() + '\'' +
                ", containerAppName='" + getContainerAppName() + '\'' +
                ", registryName='" + getRegistryName() + '\'' +
                ", workspaceName='" + getWorkspaceName() + '\'' +
                ", cpu='" + cpu + '\'' +
                ", memory='" + memory + '\'' +
                ", minReplicas=" + minReplicas +
                ", maxReplicas=" + maxReplicas +
                ", healthCheckPath='" + healthCheckPath + '\'' +
                ", healthCheckIntervalSeconds=" + healthCheckIntervalSeconds +
                ", deploymentTimeoutMinutes=" + deploymentTimeoutMinutes +
                ", externalIngress=" + isExternalIngress() +
                ", targetPort=" + getTargetPort() +
                '}';
    }
}