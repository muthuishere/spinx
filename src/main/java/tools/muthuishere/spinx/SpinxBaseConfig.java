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

    /**
     * Path to a secrets file (relative or absolute) whose key=value pairs are
     * treated as sensitive secrets.  Values are <strong>never</strong> printed
     * or logged anywhere; only key names are emitted for diagnostic purposes.
     *
     * <p>Naming convention: {@code .env.secrets}, {@code .env.secrets.dev},
     * {@code .env.secrets.prod}, etc.  Add the file to {@code .gitignore} to
     * prevent it from being committed.
     *
     * <p>Behaviour per provider:
     * <ul>
     *   <li><b>Kamal</b> – key names are added to the {@code secrets:} section
     *       of {@code deploy.yml}; values are injected as environment variables
     *       into the kamal subprocess so Kamal can pass them through to the
     *       containers.</li>
     *   <li><b>AWS Fargate / GCP Cloud Run / Azure Container Apps</b> – values
     *       are treated as secret environment variables for the container (never
     *       written to any generated config file in plain text).</li>
     * </ul>
     */
    private String secretsFile;

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

    public String getSecretsFile() { return secretsFile; }
    public void setSecretsFile(String secretsFile) { this.secretsFile = secretsFile; }
}
