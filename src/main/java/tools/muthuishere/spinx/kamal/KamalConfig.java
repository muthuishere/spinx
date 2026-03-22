package tools.muthuishere.spinx.kamal;

import tools.muthuishere.spinx.SpinxBaseConfig;

import java.util.List;

/**
 * Configuration for Kamal deployments (VPS / any server via SSH).
 *
 * Minimal example (kamalconfig.yaml):
 * <pre>
 * serviceName: "myapp"
 * image: "user/myapp"
 * dockerfilePath: "Dockerfile"
 * environmentFile: ".env"
 * containerPort: 80
 * servers:
 *   - "192.168.1.1"
 * registry:
 *   username: "myuser"
 * </pre>
 */
public class KamalConfig extends SpinxBaseConfig {

    /**
     * Docker image name used for push/pull (e.g. "myuser/myapp" or
     * "ghcr.io/myorg/myapp").  Defaults to serviceName when omitted.
     */
    private String image;

    /** SSH-reachable host names or IPs that Kamal should deploy to. */
    private List<String> servers;

    /** Container registry configuration. */
    private RegistryConfig registry = new RegistryConfig();

    /** SSH user on the target servers (default: root). */
    private String sshUser = "root";

    // -----------------------------------------------------------------------
    // Nested: RegistryConfig
    // -----------------------------------------------------------------------

    public static class RegistryConfig {
        /**
         * Registry hostname, e.g. "ghcr.io" or "registry.digitalocean.com".
         * Leave empty for Docker Hub.
         */
        private String server;

        /** Registry username. */
        private String username;

        /**
         * Name of the environment variable that holds the registry password /
         * personal-access token.  Defaults to KAMAL_REGISTRY_PASSWORD.
         */
        private String passwordEnvVar = "KAMAL_REGISTRY_PASSWORD";

        public String getServer() { return server; }
        public void setServer(String server) { this.server = server; }

        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }

        public String getPasswordEnvVar() { return passwordEnvVar; }
        public void setPasswordEnvVar(String passwordEnvVar) { this.passwordEnvVar = passwordEnvVar; }
    }

    // -----------------------------------------------------------------------
    // Getters & Setters
    // -----------------------------------------------------------------------

    public String getImage() {
        return image != null ? image : getServiceName();
    }

    public void setImage(String image) { this.image = image; }

    public List<String> getServers() { return servers; }
    public void setServers(List<String> servers) { this.servers = servers; }

    public RegistryConfig getRegistry() { return registry; }
    public void setRegistry(RegistryConfig registry) {
        this.registry = registry != null ? registry : new RegistryConfig();
    }

    public String getSshUser() { return sshUser; }
    public void setSshUser(String sshUser) { this.sshUser = sshUser; }

    @Override
    public String toString() {
        return "KamalConfig{" +
                "serviceName='" + getServiceName() + '\'' +
                ", image='" + getImage() + '\'' +
                ", servers=" + servers +
                ", dockerfilePath='" + getDockerfilePath() + '\'' +
                ", environmentFile='" + getEnvironmentFile() + '\'' +
                ", containerPort=" + getContainerPort() +
                ", sshUser='" + sshUser + '\'' +
                '}';
    }
}
