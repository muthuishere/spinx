package tools.muthuishere.spinx.aws.fargate;

import com.fasterxml.jackson.annotation.JsonIgnore;
import tools.muthuishere.spinx.SpinxBaseConfig;

public class FargateConfig extends SpinxBaseConfig {
    
    // AWS-specific Configuration
    private String region;
    
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
        return getServiceName() + "-cluster";
    }
    
    @JsonIgnore
    public String getEcsServiceName() {
        return getServiceName() + "-service";
    }
    
    @JsonIgnore
    public String getTaskDefinitionFamily() {
        return getServiceName() + "-task";
    }
    
    @JsonIgnore
    public String getEcrRepository() {
        return getServiceName().toLowerCase().replace("_", "-");
    }
    
    @JsonIgnore
    public String getLoadBalancerName() {
        return getServiceName() + "-alb";
    }
    
    @JsonIgnore
    public String getTargetGroupName() {
        return getServiceName() + "-tg";
    }
    
    @JsonIgnore
    public String getExecutionRoleName() {
        return getServiceName() + "-execution-role";
    }
    
    @JsonIgnore
    public String getTaskRoleName() {
        return getServiceName() + "-task-role";
    }
    
    @JsonIgnore
    public String getLogGroupName() {
        return "/ecs/" + getServiceName();
    }
    
    @JsonIgnore
    public String getSecurityGroupName() {
        return getServiceName() + "-sg";
    }
    
    @JsonIgnore
    public String getLoadBalancerSecurityGroupName() {
        return getServiceName() + "-alb-sg";
    }
    
    // Getters and Setters
    public String getRegion() {
        return region;
    }
    
    public void setRegion(String region) {
        this.region = region;
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
                ", serviceName='" + getServiceName() + '\'' +
                ", dockerfilePath='" + getDockerfilePath() + '\'' +
                ", environmentFile='" + getEnvironmentFile() + '\'' +
                ", containerPort=" + getContainerPort() +
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