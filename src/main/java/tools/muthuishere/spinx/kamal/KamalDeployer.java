package tools.muthuishere.spinx.kamal;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import tools.muthuishere.spinx.CloudDeployer;
import tools.muthuishere.spinx.EnvironmentParser;
import tools.muthuishere.spinx.Runner;

import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Kamal deployer – wraps the {@code kamal} CLI tool for VPS / bare-metal
 * deployments.
 *
 * <p>Workflow:
 * <ol>
 *   <li>Load {@code KamalConfig} from the provided YAML file.</li>
 *   <li>Generate a {@code deploy.yml} in the working directory.</li>
 *   <li>Delegate to {@code kamal} CLI commands.</li>
 * </ol>
 */
public class KamalDeployer implements CloudDeployer {

    private KamalConfig config;
    private String configFilename;

    // -----------------------------------------------------------------------
    // CloudDeployer – lifecycle
    // -----------------------------------------------------------------------

    @Override
    public void init(String configFile) {
        System.out.println("Kamal: Initializing with config file: " + configFile);
        this.configFilename = configFile;

        try {
            if (!Paths.get(configFile).toFile().exists()) {
                throw new RuntimeException("Config file not found: " + configFile);
            }

            Yaml yaml = new Yaml();
            try (FileInputStream fis = new FileInputStream(configFile)) {
                this.config = yaml.loadAs(fis, KamalConfig.class);
            }

            // Resolve relative paths against config file's directory
            Path configPath = Paths.get(configFile);
            String configDir = configPath.getParent() != null
                    ? configPath.getParent().toString()
                    : System.getProperty("user.dir");

            if (config.getDockerfilePath() != null && !Paths.get(config.getDockerfilePath()).isAbsolute()) {
                config.setDockerfilePath(
                        Paths.get(configDir, config.getDockerfilePath()).normalize().toString());
            }
            if (config.getEnvironmentFile() != null && !Paths.get(config.getEnvironmentFile()).isAbsolute()) {
                config.setEnvironmentFile(
                        Paths.get(configDir, config.getEnvironmentFile()).normalize().toString());
            }

            System.out.println("Loaded config: " + config);

            // Parse and merge environment variables
            loadEnvironmentVariables(configDir);

        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize Kamal deployer", e);
        }
    }

    @Override
    public void setup() {
        System.out.println("🚀 Kamal: Running setup...");
        generateKamalDeployYml();
        Runner.runCommand("kamal setup");
    }

    @Override
    public void deploy() {
        System.out.println("🚀 Kamal: Deploying...");
        generateKamalDeployYml();
        Runner.runCommand("kamal deploy");
    }

    @Override
    public void destroy() {
        System.out.println("🗑️  Kamal: Removing deployment...");
        Runner.runCommand("kamal remove");
    }

    @Override
    public void showLogs() {
        System.out.println("📋 Kamal: Streaming logs...");
        Runner.runCommand("kamal logs");
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    private void loadEnvironmentVariables(String configDir) {
        EnvironmentParser.EnvironmentResult result = EnvironmentParser.parseEnvironmentVariables(
                config.getEnvironmentVariables(),
                config.getEnvironmentFile(),
                configDir);
        config.setEnvironmentVariables(result.getEnvironmentVariables());

        System.out.println("📊 Environment parsing complete:");
        System.out.println("   📝 Source: " + result.getSource());
        System.out.println("   📊 Total variables: " + result.getTotalCount());
    }

    /**
     * Generates a {@code deploy.yml} file in the current working directory
     * that is compatible with the Kamal CLI.
     */
    void generateKamalDeployYml() {
        Map<String, Object> deploy = new LinkedHashMap<>();

        deploy.put("service", config.getServiceName());
        deploy.put("image", config.getImage());

        // Servers
        List<String> servers = config.getServers();
        if (servers != null && !servers.isEmpty()) {
            deploy.put("servers", servers);
        }

        // Proxy (port forwarding)
        Map<String, Object> proxy = new LinkedHashMap<>();
        proxy.put("app_port", config.getContainerPort());
        deploy.put("proxy", proxy);

        // Registry
        KamalConfig.RegistryConfig reg = config.getRegistry();
        if (reg != null) {
            Map<String, Object> registry = new LinkedHashMap<>();
            if (reg.getServer() != null && !reg.getServer().isBlank()) {
                registry.put("server", reg.getServer());
            }
            if (reg.getUsername() != null && !reg.getUsername().isBlank()) {
                registry.put("username", reg.getUsername());
            }
            List<String> passwordList = new ArrayList<>();
            passwordList.add(reg.getPasswordEnvVar());
            registry.put("password", passwordList);
            deploy.put("registry", registry);
        }

        // SSH
        Map<String, Object> ssh = new LinkedHashMap<>();
        ssh.put("user", config.getSshUser());
        deploy.put("ssh", ssh);

        // Environment variables
        Map<String, String> envVars = config.getEnvironmentVariables();
        if (envVars != null && !envVars.isEmpty()) {
            Map<String, Object> env = new LinkedHashMap<>();
            env.put("clear", new LinkedHashMap<>(envVars));
            deploy.put("env", env);
        }

        // Write the deploy.yml
        Path deployYmlPath = Paths.get(System.getProperty("user.dir"), "deploy.yml");
        try (FileWriter writer = new FileWriter(deployYmlPath.toFile())) {
            DumperOptions opts = new DumperOptions();
            opts.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
            opts.setPrettyFlow(true);
            new Yaml(opts).dump(deploy, writer);
            System.out.println("✅ Generated Kamal deploy.yml at: " + deployYmlPath);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write deploy.yml", e);
        }
    }
}
