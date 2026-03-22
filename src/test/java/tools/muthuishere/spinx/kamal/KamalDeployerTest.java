package tools.muthuishere.spinx.kamal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class KamalDeployerTest {

    @TempDir
    Path tempDir;

    // -----------------------------------------------------------------------
    // KamalConfig tests
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

    // -----------------------------------------------------------------------
    // KamalDeployer – generateKamalDeployYml
    // -----------------------------------------------------------------------

    private KamalDeployer createDeployerWithConfig(String serviceName, String image,
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

        // Inject config via reflection to skip file loading and validation that occurs in init()
        KamalDeployer deployer = new KamalDeployer();
        try {
            java.lang.reflect.Field f = KamalDeployer.class.getDeclaredField("config");
            f.setAccessible(true);
            f.set(deployer, cfg);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return deployer;
    }

    @Test
    void generateKamalDeployYml_createsFile() throws IOException {
        String originalDir = System.getProperty("user.dir");
        System.setProperty("user.dir", tempDir.toString());
        try {
            KamalDeployer deployer = createDeployerWithConfig(
                    "myapp", "myuser/myapp", List.of("10.0.0.1"), "myuser");
            deployer.generateKamalDeployYml();

            Path deployYml = tempDir.resolve("deploy.yml");
            assertTrue(Files.exists(deployYml), "deploy.yml should be created");
        } finally {
            System.setProperty("user.dir", originalDir);
        }
    }

    @Test
    void generateKamalDeployYml_containsServiceName() throws IOException {
        String originalDir = System.getProperty("user.dir");
        System.setProperty("user.dir", tempDir.toString());
        try {
            KamalDeployer deployer = createDeployerWithConfig(
                    "myapp", "myuser/myapp", List.of("10.0.0.1"), "myuser");
            deployer.generateKamalDeployYml();

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
        String originalDir = System.getProperty("user.dir");
        System.setProperty("user.dir", tempDir.toString());
        try {
            KamalDeployer deployer = createDeployerWithConfig(
                    "myapp", "myuser/myapp", List.of("10.0.0.1"), "myuser");
            deployer.generateKamalDeployYml();

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
        String originalDir = System.getProperty("user.dir");
        System.setProperty("user.dir", tempDir.toString());
        try {
            KamalDeployer deployer = createDeployerWithConfig(
                    "myapp", "myuser/myapp", List.of("10.0.0.1"), "myuser");
            deployer.generateKamalDeployYml();

            String content = Files.readString(tempDir.resolve("deploy.yml"));
            assertTrue(content.contains("app_port: 80"), "proxy app_port must be present");
        } finally {
            System.setProperty("user.dir", originalDir);
        }
    }
}
