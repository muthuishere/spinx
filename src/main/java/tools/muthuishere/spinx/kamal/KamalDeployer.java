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
 *   <li>Delegate to {@code kamal} CLI commands via the {@link CommandInvoker}.</li>
 * </ol>
 *
 * <p>The {@link CommandInvoker} is injectable so that tests can capture the
 * exact commands that would be sent without spawning OS processes.
 */
public class KamalDeployer implements CloudDeployer {

    private KamalConfig config;
    private String configFilename;
    private final CommandInvoker commandInvoker;

    /**
     * Runtime secrets loaded from {@code secretsFile} (values are never logged).
     * These are injected as environment variables into the kamal subprocess so
     * Kamal can pass them through SSH to the containers.
     *
     * <p>The field is populated by {@link #loadRuntimeSecrets} during {@link #init}.
     * The production {@link CommandInvoker} lambda captures {@code this}, so it
     * always sees the fully-populated map at invocation time (i.e. after init
     * completes), not the empty map at construction time.
     */
    private Map<String, String> runtimeSecrets = new java.util.HashMap<>();

    /** Production constructor – delegates to {@link Runner#runCommandWithEnv(String, Map)}. */
    public KamalDeployer() {
        this.commandInvoker = command -> Runner.runCommandWithEnv(command, runtimeSecrets);
    }

    /**
     * Test-friendly constructor that accepts a custom {@link CommandInvoker}.
     *
     * @param commandInvoker strategy used to execute kamal CLI commands
     */
    public KamalDeployer(CommandInvoker commandInvoker) {
        this.commandInvoker = commandInvoker;
    }

    /**
     * Constructor that accepts both a custom invoker and a pre-built config,
     * bypassing file loading entirely (useful for unit tests).
     *
     * @param commandInvoker strategy used to execute kamal CLI commands
     * @param config         pre-built Kamal configuration
     */
    public KamalDeployer(CommandInvoker commandInvoker, KamalConfig config) {
        this.commandInvoker = commandInvoker;
        this.config = config;
    }

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
            if (config.getSecretsFile() != null && !Paths.get(config.getSecretsFile()).isAbsolute()) {
                config.setSecretsFile(
                        Paths.get(configDir, config.getSecretsFile()).normalize().toString());
            }

            System.out.println("Loaded config: " + config);

            // Parse and merge regular environment variables
            loadEnvironmentVariables(configDir);

            // Load secrets from secretsFile — values are never logged
            loadRuntimeSecrets(configDir);

        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize Kamal deployer", e);
        }
    }

    @Override
    public void setup() {
        System.out.println("🚀 Kamal: Running setup...");
        generateKamalDeployYml();
        commandInvoker.invoke("kamal setup");
    }

    @Override
    public void deploy() {
        System.out.println("🚀 Kamal: Deploying...");
        generateKamalDeployYml();
        commandInvoker.invoke("kamal deploy");
    }

    @Override
    public void destroy() {
        System.out.println("🗑️  Kamal: Removing deployment...");
        commandInvoker.invoke("kamal remove");
    }

    @Override
    public void showLogs() {
        System.out.println("📋 Kamal: Streaming logs...");
        commandInvoker.invoke("kamal logs");
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
     * Load secrets from the {@code secretsFile} into {@link #runtimeSecrets}.
     * Values are <strong>never</strong> logged; only key names are surfaced.
     * The secret key names are also merged into {@code config.secrets} so that
     * they appear in the {@code secrets:} section of the generated
     * {@code deploy.yml}.
     */
    private void loadRuntimeSecrets(String configDir) {
        String secretsFilePath = config.getSecretsFile();
        if (secretsFilePath == null || secretsFilePath.trim().isEmpty()) {
            return;
        }

        Map<String, String> fileSecrets = EnvironmentParser.loadSecretsFile(secretsFilePath, configDir);
        if (fileSecrets.isEmpty()) {
            return;
        }

        // Store values for subprocess injection (never logged)
        runtimeSecrets.putAll(fileSecrets);

        // Add key names to the config.secrets list so they appear in deploy.yml
        List<String> secretKeys = config.getSecrets() != null
                ? new ArrayList<>(config.getSecrets())
                : new ArrayList<>();
        for (String key : fileSecrets.keySet()) {
            if (!secretKeys.contains(key)) {
                secretKeys.add(key);
            }
        }
        config.setSecrets(secretKeys);
    }

    /**
     * Package-private accessor for tests to verify that runtime secrets were
     * loaded from the {@code secretsFile}.  Returns a copy to prevent mutation.
     */
    Map<String, String> getRuntimeSecrets() {
        return new java.util.HashMap<>(runtimeSecrets);
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

        // Environment variables (clear + secrets)
        buildEnvSection(deploy, config.getEnvironmentVariables(), config.getSecrets());

        // Accessories (databases, caches, etc.)
        buildAccessoriesSection(deploy, config.getAccessories());

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

    /**
     * Builds the {@code env} section of deploy.yml, separating plain-text
     * variables ({@code clear}) from secret references ({@code secret}).
     */
    private void buildEnvSection(Map<String, Object> deploy,
                                  Map<String, String> envVars,
                                  List<String> secrets) {
        boolean hasEnv = envVars != null && !envVars.isEmpty();
        boolean hasSecrets = secrets != null && !secrets.isEmpty();
        if (!hasEnv && !hasSecrets) return;

        Map<String, Object> env = new LinkedHashMap<>();
        if (hasSecrets) {
            env.put("secret", new ArrayList<>(secrets));
        }
        if (hasEnv) {
            env.put("clear", new LinkedHashMap<>(envVars));
        }
        deploy.put("env", env);
    }

    /**
     * Builds the {@code accessories} section of deploy.yml from
     * {@link KamalConfig.AccessoryConfig} entries.
     */
    private void buildAccessoriesSection(Map<String, Object> deploy,
                                          Map<String, KamalConfig.AccessoryConfig> accessories) {
        if (accessories == null || accessories.isEmpty()) return;

        Map<String, Object> accessoriesMap = new LinkedHashMap<>();
        for (Map.Entry<String, KamalConfig.AccessoryConfig> entry : accessories.entrySet()) {
            KamalConfig.AccessoryConfig acc = entry.getValue();
            Map<String, Object> accMap = new LinkedHashMap<>();

            if (acc.getImage() != null) accMap.put("image", acc.getImage());
            if (acc.getHost() != null) accMap.put("host", acc.getHost());
            if (acc.getPort() != null) accMap.put("port", acc.getPort());

            // Accessory env (secrets + clear)
            boolean hasAccSecrets = acc.getSecrets() != null && !acc.getSecrets().isEmpty();
            boolean hasAccEnv = acc.getEnv() != null && !acc.getEnv().isEmpty();
            if (hasAccSecrets || hasAccEnv) {
                Map<String, Object> accEnv = new LinkedHashMap<>();
                if (hasAccSecrets) accEnv.put("secret", new ArrayList<>(acc.getSecrets()));
                if (hasAccEnv) accEnv.put("clear", new LinkedHashMap<>(acc.getEnv()));
                accMap.put("env", accEnv);
            }

            if (acc.getVolumes() != null && !acc.getVolumes().isEmpty()) {
                accMap.put("volumes", new ArrayList<>(acc.getVolumes()));
            }

            accessoriesMap.put(entry.getKey(), accMap);
        }
        deploy.put("accessories", accessoriesMap);
    }
}

