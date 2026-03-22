package tools.muthuishere.spinx;

import java.util.HashMap;
import java.util.Map;

/**
 * Base configuration shared uniformly across all Spinx providers.
 * Every provider config extends this class so the core YAML fields
 * are consistent regardless of deployment target.
 */
public class SpinxBaseConfig {

    // Required: human-friendly name of the service
    private String serviceName;

    // Container build
    private String dockerfilePath = "Dockerfile";

    // Environment
    private String environmentFile = ".env";
    private int containerPort = 8080;
    private Map<String, String> environmentVariables = new HashMap<>();

    // Getters and Setters
    public String getServiceName() { return serviceName; }
    public void setServiceName(String serviceName) { this.serviceName = serviceName; }

    public String getDockerfilePath() { return dockerfilePath; }
    public void setDockerfilePath(String dockerfilePath) { this.dockerfilePath = dockerfilePath; }

    public String getEnvironmentFile() { return environmentFile; }
    public void setEnvironmentFile(String environmentFile) { this.environmentFile = environmentFile; }

    public int getContainerPort() { return containerPort; }
    public void setContainerPort(int containerPort) { this.containerPort = containerPort; }

    public Map<String, String> getEnvironmentVariables() { return environmentVariables; }
    public void setEnvironmentVariables(Map<String, String> environmentVariables) {
        this.environmentVariables = environmentVariables != null ? environmentVariables : new HashMap<>();
    }
}
