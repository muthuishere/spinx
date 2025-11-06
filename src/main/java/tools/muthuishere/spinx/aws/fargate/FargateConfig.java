package tools.muthuishere.spinx.aws.fargate;

import com.fasterxml.jackson.annotation.JsonIgnore;

public class FargateConfig {
    
    // Basic Configuration
    private String region;
    private String serviceName;
    
    // Container Configuration
    private String dockerfilePath;
    private String environmentFile;
    private int containerPort;
    private java.util.Map<String, String> environmentVariables = new java.util.HashMap<>();
    
    // Resource Configuration
    private int cpu;
    private int memory;
    private int desiredCount;
    
    // Load Balancer Configuration
    private String healthCheckPath;
    private int healthCheckIntervalSeconds;
    
        // HTTPS Configuration - simple automatic HTTPS
    private boolean enableHttps = true; // Always try to enable HTTPS alongside HTTP
    
    // Deployment Settings
    private int deploymentTimeoutMinutes;
    
    // Computed getters for derived names
    @JsonIgnore
    public String getClusterName() {
        return serviceName + "-cluster";
    }
    
    @JsonIgnore
    public String getEcsServiceName() {
        return serviceName + "-service";
    }
    
    @JsonIgnore
    public String getTaskDefinitionFamily() {
        return serviceName + "-task";
    }
    
    @JsonIgnore
    public String getEcrRepository() {
        return serviceName.toLowerCase().replace("_", "-");
    }
    
    @JsonIgnore
    public String getLoadBalancerName() {
        return serviceName + "-alb";
    }
    
    @JsonIgnore
    public String getTargetGroupName() {
        return serviceName + "-tg";
    }
    
    @JsonIgnore
    public String getExecutionRoleName() {
        return serviceName + "-execution-role";
    }
    
    @JsonIgnore
    public String getTaskRoleName() {
        return serviceName + "-task-role";
    }
    
    @JsonIgnore
    public String getLogGroupName() {
        return "/ecs/" + serviceName;
    }
    
    @JsonIgnore
    public String getSecurityGroupName() {
        return serviceName + "-sg";
    }
    
    @JsonIgnore
    public String getLoadBalancerSecurityGroupName() {
        return serviceName + "-alb-sg";
    }
    
    // Getters and Setters
    public String getRegion() {
        return region;
    }
    
    public void setRegion(String region) {
        this.region = region;
    }
    
    public String getServiceName() {
        return serviceName;
    }
    
    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }
    
    public String getDockerfilePath() {
        return dockerfilePath;
    }
    
    public void setDockerfilePath(String dockerfilePath) {
        this.dockerfilePath = dockerfilePath;
    }
    
    public String getEnvironmentFile() {
        return environmentFile;
    }
    
    public void setEnvironmentFile(String environmentFile) {
        this.environmentFile = environmentFile;
    }
    
    public int getContainerPort() {
        return containerPort;
    }
    
    public void setContainerPort(int containerPort) {
        this.containerPort = containerPort;
    }
    
    public java.util.Map<String, String> getEnvironmentVariables() {
        return environmentVariables;
    }
    
    public void setEnvironmentVariables(java.util.Map<String, String> environmentVariables) {
        this.environmentVariables = environmentVariables != null ? environmentVariables : new java.util.HashMap<>();
    }
    
    public int getCpu() {
        return cpu;
    }
    
    public void setCpu(int cpu) {
        this.cpu = cpu;
    }
    
    public int getMemory() {
        return memory;
    }
    
    public void setMemory(int memory) {
        this.memory = memory;
    }
    
    public int getDesiredCount() {
        return desiredCount;
    }
    
    public void setDesiredCount(int desiredCount) {
        this.desiredCount = desiredCount;
    }
    
    public String getHealthCheckPath() {
        return healthCheckPath;
    }
    
    public void setHealthCheckPath(String healthCheckPath) {
        this.healthCheckPath = healthCheckPath;
    }
    
    public int getHealthCheckIntervalSeconds() {
        return healthCheckIntervalSeconds;
    }
    
    public void setHealthCheckIntervalSeconds(int healthCheckIntervalSeconds) {
        this.healthCheckIntervalSeconds = healthCheckIntervalSeconds;
    }
    
    public int getDeploymentTimeoutMinutes() {
        return deploymentTimeoutMinutes;
    }
    
    public void setDeploymentTimeoutMinutes(int deploymentTimeoutMinutes) {
        this.deploymentTimeoutMinutes = deploymentTimeoutMinutes;
    }
    
    public boolean isEnableHttps() {
        return enableHttps;
    }

    public void setEnableHttps(boolean enableHttps) {
        this.enableHttps = enableHttps;
    }    @Override
    public String toString() {
        return "FargateConfig{" +
                "region='" + region + '\'' +
                ", serviceName='" + serviceName + '\'' +
                ", dockerfilePath='" + dockerfilePath + '\'' +
                ", environmentFile='" + environmentFile + '\'' +
                ", containerPort=" + containerPort +
                ", cpu=" + cpu +
                ", memory=" + memory +
                ", desiredCount=" + desiredCount +
                ", healthCheckPath='" + healthCheckPath + '\'' +
                ", healthCheckIntervalSeconds=" + healthCheckIntervalSeconds +
                ", deploymentTimeoutMinutes=" + deploymentTimeoutMinutes +
                ", enableHttps=" + enableHttps +
                '}';
    }
}