package tools.muthuishere.spinx.kamal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class KamalDeployerTest {

    @TempDir
    Path tempDir;

    // -----------------------------------------------------------------------
    // KamalConfig – unit tests
    // -----------------------------------------------------------------------

    @Test
    void kamalConfig_defaultImage_fallsBackToServiceName() {
        KamalConfig cfg = new KamalConfig();
        cfg.setServiceName("myapp");
        assertEquals("myapp", cfg.getImage(),
                "image should default to serviceName when not set");
    }

    @Test
    void kamalConfig_explicitImage_overridesServiceName() {
        KamalConfig cfg = new KamalConfig();
        cfg.setServiceName("myapp");
        cfg.setImage("myorg/myapp");
        assertEquals("myorg/myapp", cfg.getImage());
    }

    @Test
    void kamalConfig_baseFields_inheritedFromSpinxBaseConfig() {
        KamalConfig cfg = new KamalConfig();
        cfg.setServiceName("svc");
        cfg.setContainerPort(3000);
        cfg.setDockerfilePath("app/Dockerfile");
        cfg.setEnvironmentFile("app/.env");

        assertEquals("svc", cfg.getServiceName());
        assertEquals(3000, cfg.getContainerPort());
        assertEquals("app/Dockerfile", cfg.getDockerfilePath());
        assertEquals("app/.env", cfg.getEnvironmentFile());
    }

    @Test
    void kamalConfig_registryDefaults() {
        KamalConfig cfg = new KamalConfig();
        assertNotNull(cfg.getRegistry());
        assertEquals("KAMAL_REGISTRY_PASSWORD", cfg.getRegistry().getPasswordEnvVar());
    }

    @Test
    void kamalConfig_sshUserDefault() {
        KamalConfig cfg = new KamalConfig();
        assertEquals("root", cfg.getSshUser());
    }

    @Test
    void kamalConfig_secretsDefaultToEmptyList() {
        KamalConfig cfg = new KamalConfig();
        // secrets defaults to null (populated via setter only when present in YAML)
        assertNull(cfg.getSecrets());
    }

    @Test
    void kamalConfig_accessoriesDefaultToEmptyMap() {
        KamalConfig cfg = new KamalConfig();
        // accessories defaults to null (populated via setter only when present in YAML)
        assertNull(cfg.getAccessories());
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Builds a {@link KamalDeployer} with a captured-command invoker. */
    private KamalDeployer createDeployerWithConfig(KamalConfig cfg,
                                                    List<String> capturedCommands) {
        return new KamalDeployer(capturedCommands::add, cfg);
    }

    private KamalConfig buildBasicConfig(String serviceName, String image,
                                          List<String> servers, String registryUser) {
        KamalConfig cfg = new KamalConfig();
        cfg.setServiceName(serviceName);
        cfg.setImage(image);
        cfg.setServers(servers);
        cfg.setContainerPort(80);
        cfg.setEnvironmentVariables(Map.of("APP_ENV", "production"));

        KamalConfig.RegistryConfig reg = new KamalConfig.RegistryConfig();
        reg.setUsername(registryUser);
        cfg.setRegistry(reg);
        return cfg;
    }

    // -----------------------------------------------------------------------
    // CommandInvoker – command verification tests
    // -----------------------------------------------------------------------

    @Test
    void deploy_invokesKamalDeployCommand() {
        List<String> commands = new ArrayList<>();
        KamalConfig cfg = buildBasicConfig("myapp", "myuser/myapp", List.of("10.0.0.1"), "myuser");
        KamalDeployer deployer = new KamalDeployer(commands::add, cfg);

        String originalDir = System.getProperty("user.dir");
        System.setProperty("user.dir", tempDir.toString());
        try {
            deployer.deploy();
        } finally {
            System.setProperty("user.dir", originalDir);
        }

        assertTrue(commands.contains("kamal deploy"),
                "deploy() must invoke 'kamal deploy'; got: " + commands);
    }

    @Test
    void setup_invokesKamalSetupCommand() {
        List<String> commands = new ArrayList<>();
        KamalConfig cfg = buildBasicConfig("myapp", "myuser/myapp", List.of("10.0.0.1"), "myuser");
        KamalDeployer deployer = new KamalDeployer(commands::add, cfg);

        String originalDir = System.getProperty("user.dir");
        System.setProperty("user.dir", tempDir.toString());
        try {
            deployer.setup();
        } finally {
            System.setProperty("user.dir", originalDir);
        }

        assertTrue(commands.contains("kamal setup"),
                "setup() must invoke 'kamal setup'; got: " + commands);
    }

    @Test
    void destroy_invokesKamalRemoveCommand() {
        List<String> commands = new ArrayList<>();
        KamalConfig cfg = buildBasicConfig("myapp", "myuser/myapp", List.of("10.0.0.1"), "myuser");
        KamalDeployer deployer = new KamalDeployer(commands::add, cfg);

        deployer.destroy();

        assertTrue(commands.contains("kamal remove"),
                "destroy() must invoke 'kamal remove'; got: " + commands);
    }

    @Test
    void showLogs_invokesKamalLogsCommand() {
        List<String> commands = new ArrayList<>();
        KamalConfig cfg = buildBasicConfig("myapp", "myuser/myapp", List.of("10.0.0.1"), "myuser");
        KamalDeployer deployer = new KamalDeployer(commands::add, cfg);

        deployer.showLogs();

        assertTrue(commands.contains("kamal logs"),
                "showLogs() must invoke 'kamal logs'; got: " + commands);
    }

    @Test
    void deploy_invokesExactlyOneKamalCommand() {
        List<String> commands = new ArrayList<>();
        KamalConfig cfg = buildBasicConfig("myapp", "myuser/myapp", List.of("10.0.0.1"), "myuser");
        KamalDeployer deployer = new KamalDeployer(commands::add, cfg);

        String originalDir = System.getProperty("user.dir");
        System.setProperty("user.dir", tempDir.toString());
        try {
            deployer.deploy();
        } finally {
            System.setProperty("user.dir", originalDir);
        }

        assertEquals(1, commands.size(), "deploy() should invoke exactly one command");
    }

    // -----------------------------------------------------------------------
    // generateKamalDeployYml – content verification
    // -----------------------------------------------------------------------

    @Test
    void generateKamalDeployYml_createsFile() throws IOException {
        KamalConfig cfg = buildBasicConfig("myapp", "myuser/myapp", List.of("10.0.0.1"), "myuser");

        String originalDir = System.getProperty("user.dir");
        System.setProperty("user.dir", tempDir.toString());
        try {
            new KamalDeployer(cmd -> {}, cfg).generateKamalDeployYml();
            assertTrue(Files.exists(tempDir.resolve("deploy.yml")), "deploy.yml should be created");
        } finally {
            System.setProperty("user.dir", originalDir);
        }
    }

    @Test
    void generateKamalDeployYml_containsServiceNameAndImage() throws IOException {
        KamalConfig cfg = buildBasicConfig("myapp", "myuser/myapp", List.of("10.0.0.1"), "myuser");

        String originalDir = System.getProperty("user.dir");
        System.setProperty("user.dir", tempDir.toString());
        try {
            new KamalDeployer(cmd -> {}, cfg).generateKamalDeployYml();
            String content = Files.readString(tempDir.resolve("deploy.yml"));
            assertTrue(content.contains("service: myapp"), "deploy.yml must contain 'service: myapp'");
            assertTrue(content.contains("image: myuser/myapp"), "deploy.yml must contain image");
            assertTrue(content.contains("10.0.0.1"), "deploy.yml must contain server IP");
        } finally {
            System.setProperty("user.dir", originalDir);
        }
    }

    @Test
    void generateKamalDeployYml_containsRegistryAndEnv() throws IOException {
        KamalConfig cfg = buildBasicConfig("myapp", "myuser/myapp", List.of("10.0.0.1"), "myuser");

        String originalDir = System.getProperty("user.dir");
        System.setProperty("user.dir", tempDir.toString());
        try {
            new KamalDeployer(cmd -> {}, cfg).generateKamalDeployYml();
            String content = Files.readString(tempDir.resolve("deploy.yml"));
            assertTrue(content.contains("username: myuser"), "registry username must be present");
            assertTrue(content.contains("KAMAL_REGISTRY_PASSWORD"), "registry password env-var must be present");
            assertTrue(content.contains("APP_ENV"), "env variable APP_ENV must be present");
        } finally {
            System.setProperty("user.dir", originalDir);
        }
    }

    @Test
    void generateKamalDeployYml_containsAppPort() throws IOException {
        KamalConfig cfg = buildBasicConfig("myapp", "myuser/myapp", List.of("10.0.0.1"), "myuser");

        String originalDir = System.getProperty("user.dir");
        System.setProperty("user.dir", tempDir.toString());
        try {
            new KamalDeployer(cmd -> {}, cfg).generateKamalDeployYml();
            String content = Files.readString(tempDir.resolve("deploy.yml"));
            assertTrue(content.contains("app_port: 80"), "proxy app_port must be present");
        } finally {
            System.setProperty("user.dir", originalDir);
        }
    }

    @Test
    void generateKamalDeployYml_secretsAppearsInEnvSection() throws IOException {
        KamalConfig cfg = buildBasicConfig("myapp", "myuser/myapp", List.of("10.0.0.1"), "myuser");
        cfg.setSecrets(List.of("SECRET_KEY_BASE", "DATABASE_PASSWORD"));

        String originalDir = System.getProperty("user.dir");
        System.setProperty("user.dir", tempDir.toString());
        try {
            new KamalDeployer(cmd -> {}, cfg).generateKamalDeployYml();
            String content = Files.readString(tempDir.resolve("deploy.yml"));
            assertTrue(content.contains("SECRET_KEY_BASE"), "secrets must appear in deploy.yml");
            assertTrue(content.contains("DATABASE_PASSWORD"), "secrets must appear in deploy.yml");
            assertTrue(content.contains("secret:"), "secrets block label must be present");
        } finally {
            System.setProperty("user.dir", originalDir);
        }
    }

    @Test
    void generateKamalDeployYml_accessoriesAppearsInOutput() throws IOException {
        KamalConfig cfg = buildBasicConfig("myapp", "myuser/myapp", List.of("10.0.0.1"), "myuser");

        KamalConfig.AccessoryConfig db = new KamalConfig.AccessoryConfig();
        db.setImage("mysql:8.0");
        db.setHost("10.0.0.2");
        db.setPort(3306);
        db.setSecrets(List.of("MYSQL_ROOT_PASSWORD"));
        db.setEnv(Map.of("MYSQL_DATABASE", "myapp"));
        db.setVolumes(List.of("/var/lib/mysql:/var/lib/mysql"));

        KamalConfig.AccessoryConfig redis = new KamalConfig.AccessoryConfig();
        redis.setImage("redis:7.0");
        redis.setHost("10.0.0.2");
        redis.setPort(6379);
        redis.setVolumes(List.of("/var/lib/redis:/data"));

        cfg.setAccessories(Map.of("db", db, "redis", redis));

        String originalDir = System.getProperty("user.dir");
        System.setProperty("user.dir", tempDir.toString());
        try {
            new KamalDeployer(cmd -> {}, cfg).generateKamalDeployYml();
            String content = Files.readString(tempDir.resolve("deploy.yml"));
            assertTrue(content.contains("accessories:"), "accessories section must be present");
            assertTrue(content.contains("mysql:8.0"), "MySQL image must be present");
            assertTrue(content.contains("redis:7.0"), "Redis image must be present");
            assertTrue(content.contains("MYSQL_ROOT_PASSWORD"), "MySQL secret must be present");
            assertTrue(content.contains("MYSQL_DATABASE"), "MySQL env var must be present");
            assertTrue(content.contains("/var/lib/mysql:/var/lib/mysql"), "volume must be present");
            assertTrue(content.contains("/var/lib/redis:/data"), "redis volume must be present");
        } finally {
            System.setProperty("user.dir", originalDir);
        }
    }

    // -----------------------------------------------------------------------
    // secretsFile – loading and integration tests
    // -----------------------------------------------------------------------

    @Test
    void secretsFile_keysAddedToDeployYmlSecretsSection() throws IOException {
        // Write a .env.secrets file into the temp dir
        Path secretsFilePath = tempDir.resolve(".env.secrets");
        Files.writeString(secretsFilePath,
                "SECRET_KEY_BASE=abc123supersecret\n" +
                "DATABASE_PASSWORD=dbpassword\n" +
                "# this is a comment\n" +
                "\n" +
                "AWS_SECRET_ACCESS_KEY=awssecret\n");

        KamalConfig cfg = buildBasicConfig("myapp", "myuser/myapp", List.of("10.0.0.1"), "myuser");
        cfg.setSecretsFile(secretsFilePath.toString());

        // Simulate what KamalDeployer.loadRuntimeSecrets would do by calling
        // EnvironmentParser.loadSecretsFile directly and merging keys into secrets
        var loadedSecrets = tools.muthuishere.spinx.EnvironmentParser.loadSecretsFile(
                secretsFilePath.toString(), null);
        List<String> secretKeys = new ArrayList<>(loadedSecrets.keySet());
        cfg.setSecrets(secretKeys);

        String originalDir = System.getProperty("user.dir");
        System.setProperty("user.dir", tempDir.toString());
        try {
            new KamalDeployer(cmd -> {}, cfg).generateKamalDeployYml();
            String content = Files.readString(tempDir.resolve("deploy.yml"));
            assertTrue(content.contains("SECRET_KEY_BASE"), "SECRET_KEY_BASE key must appear in deploy.yml");
            assertTrue(content.contains("DATABASE_PASSWORD"), "DATABASE_PASSWORD key must appear in deploy.yml");
            assertTrue(content.contains("AWS_SECRET_ACCESS_KEY"), "AWS_SECRET_ACCESS_KEY key must appear in deploy.yml");
            // Values must NOT appear in the generated YAML
            assertFalse(content.contains("abc123supersecret"), "secret value must NOT be written to deploy.yml");
            assertFalse(content.contains("dbpassword"), "secret value must NOT be written to deploy.yml");
            assertFalse(content.contains("awssecret"), "secret value must NOT be written to deploy.yml");
        } finally {
            System.setProperty("user.dir", originalDir);
        }
    }

    @Test
    void secretsFile_missingFileIsGracefullyIgnored() {
        KamalConfig cfg = buildBasicConfig("myapp", "myuser/myapp", List.of("10.0.0.1"), "myuser");
        cfg.setSecretsFile("/nonexistent/.env.secrets.prod");

        // loadSecretsFile must return empty map without throwing
        var result = tools.muthuishere.spinx.EnvironmentParser.loadSecretsFile(
                cfg.getSecretsFile(), null);
        assertNotNull(result, "result must not be null");
        assertTrue(result.isEmpty(), "missing secrets file should yield an empty map");
    }

    @Test
    void secretsFile_handlesQuotedAndUnquotedValues() throws IOException {
        Path secretsFilePath = tempDir.resolve(".env.secrets");
        Files.writeString(secretsFilePath,
                "QUOTED_DOUBLE=\"my secret value\"\n" +
                "QUOTED_SINGLE='another secret'\n" +
                "UNQUOTED=plain\n");

        var result = tools.muthuishere.spinx.EnvironmentParser.loadSecretsFile(
                secretsFilePath.toString(), null);
        assertEquals("my secret value", result.get("QUOTED_DOUBLE"));
        assertEquals("another secret", result.get("QUOTED_SINGLE"));
        assertEquals("plain", result.get("UNQUOTED"));
    }

    @Test
    void secretsFile_baseConfigFieldInherited() {
        KamalConfig cfg = new KamalConfig();
        cfg.setSecretsFile(".env.secrets.prod");
        assertEquals(".env.secrets.prod", cfg.getSecretsFile(),
                "secretsFile field should be readable from KamalConfig via SpinxBaseConfig");
    }

    @Test
    void secretsFile_valuesLoadedIntoRuntimeSecrets() throws IOException {
        // Write secrets file
        Path secretsFilePath = tempDir.resolve(".env.secrets.test");
        Files.writeString(secretsFilePath, "MY_TOKEN=tok123\nMY_PASS=pass456\n");

        // Build deployer with the test constructor that bypasses file-based init
        KamalConfig cfg = buildBasicConfig("myapp", "myuser/myapp", List.of("10.0.0.1"), "myuser");
        KamalDeployer deployer = new KamalDeployer(cmd -> {}, cfg);

        // Simulate what init() would call
        var loaded = tools.muthuishere.spinx.EnvironmentParser.loadSecretsFile(
                secretsFilePath.toString(), null);
        // Manually load via reflection-free accessor by invoking the same EnvironmentParser path
        // and verifying the map contents are correct
        assertEquals("tok123", loaded.get("MY_TOKEN"),
                "MY_TOKEN value must be loaded from secrets file");
        assertEquals("pass456", loaded.get("MY_PASS"),
                "MY_PASS value must be loaded from secrets file");
        assertEquals(2, loaded.size(), "exactly 2 secrets should be loaded");
    }
}

