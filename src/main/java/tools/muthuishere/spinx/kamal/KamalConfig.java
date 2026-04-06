package tools.muthuishere.spinx.kamal;

import tools.muthuishere.spinx.SpinxBaseConfig;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 * secrets:
 *   - SECRET_KEY_BASE
 *   - DATABASE_PASSWORD
 * accessories:
 *   db:
 *     image: "mysql:8.0"
 *     host: "192.168.1.1"
 *     port: 3306
 *     env:
 *       secret:
 *         - MYSQL_ROOT_PASSWORD
 *       clear:
 *         MYSQL_DATABASE: myapp
 *     volumes:
 *       - "/var/lib/mysql:/var/lib/mysql"
 *   redis:
 *     image: "redis:7.0"
 *     host: "192.168.1.1"
 *     port: 6379
 *     volumes:
 *       - "/var/lib/redis:/data"
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

    /**
     * Names of environment variables whose values are secrets and should NOT
     * be committed to the deploy.yml in plain text.  Kamal will read them
     * from the environment at deploy time.
     *
     * <p>Example: {@code [SECRET_KEY_BASE, DATABASE_PASSWORD]}
     */
    private List<String> secrets;

    /**
     * Named accessory services (e.g. databases, caches) that Kamal manages
     * alongside the main app container.  Keys are the accessory names.
     */
    private Map<String, AccessoryConfig> accessories;

    /**
     * Base directory on the VPS for accessory data volumes.
     * When an accessory has no explicit {@code volumes} list, Spinx automatically
     * generates a volume mapping of the form:
     * <pre>
     *   &lt;storagePath&gt;/&lt;accessoryName&gt;:&lt;containerDataPath&gt;
     * </pre>
     * For example, with {@code storagePath: "/data/myapp"} and an accessory named
     * {@code postgres}, the generated volume is
     * {@code /data/myapp/postgres:/var/lib/postgresql/data}.
     *
     * <p>Default: {@code /var/lib}
     */
    private String storagePath = "/var/lib";

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
    // Nested: AccessoryConfig
    // -----------------------------------------------------------------------

    /**
     * Configuration for a single Kamal accessory (e.g. a MySQL or Redis
     * sidecar service managed by Kamal on the same or a different server).
     */
    public static class AccessoryConfig {
        /** Docker image for this accessory, e.g. "mysql:8.0". */
        private String image;

        /** Host (IP or hostname) on which to run this accessory. */
        private String host;

        /** Host port to expose. */
        private Integer port;

        /**
         * Secret environment variable names whose values come from the
         * deploy-time environment (not stored in the config file).
         */
        private List<String> secrets;

        /** Plain-text environment variables for this accessory. */
        private Map<String, String> env;

        /** Volume mount specs, e.g. "/var/lib/mysql:/var/lib/mysql". */
        private List<String> volumes;

        public String getImage() { return image; }
        public void setImage(String image) { this.image = image; }

        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }

        public Integer getPort() { return port; }
        public void setPort(Integer port) { this.port = port; }

        public List<String> getSecrets() { return secrets; }
        public void setSecrets(List<String> secrets) {
            this.secrets = secrets != null ? secrets : new ArrayList<>();
        }

        public Map<String, String> getEnv() { return env; }
        public void setEnv(Map<String, String> env) {
            this.env = env != null ? env : new HashMap<>();
        }

        public List<String> getVolumes() { return volumes; }
        public void setVolumes(List<String> volumes) {
            this.volumes = volumes != null ? volumes : new ArrayList<>();
        }
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

    public List<String> getSecrets() { return secrets; }
    public void setSecrets(List<String> secrets) {
        this.secrets = secrets != null ? secrets : new ArrayList<>();
    }

    public Map<String, AccessoryConfig> getAccessories() { return accessories; }
    public void setAccessories(Map<String, AccessoryConfig> accessories) {
        this.accessories = accessories != null ? accessories : new LinkedHashMap<>();
    }

    public String getStoragePath() { return storagePath; }
    public void setStoragePath(String storagePath) {
        this.storagePath = storagePath != null ? storagePath : "/var/lib";
    }

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
                ", storagePath='" + storagePath + '\'' +
                ", secrets=" + secrets +
                ", accessories=" + (accessories != null ? accessories.keySet() : "[]") +
                '}';
    }
}

