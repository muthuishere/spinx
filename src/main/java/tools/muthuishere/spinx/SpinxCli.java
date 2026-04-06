package tools.muthuishere.spinx;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import tools.muthuishere.spinx.aws.fargate.AwsFargateDeployer;
import tools.muthuishere.spinx.azure.containerapps.AzureContainerAppsDeployer;
import tools.muthuishere.spinx.gcp.cloudrun.GcpCloudRunDeployer;
import tools.muthuishere.spinx.kamal.KamalDeployer;
import tools.muthuishere.spinx.kamal.KamalSpecGenerator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Callable;

@Command(
    name = "spinx",
    description = "Multi-cloud deployment CLI for AWS, Azure, and GCP",
    mixinStandardHelpOptions = true,
    version = "spinx 0.3.1"
)
public class SpinxCli implements Callable<Integer> {

    @Parameters(index = "0", description = "Cloud provider: aws-fargate, azure-container-apps, gcp-cloudrun, kamal")
    private String provider;

    @Parameters(index = "1", description = "Action to perform: setup, deploy, destroy, logs, generate-spec")
    private String action;

    @Parameters(index = "2", description = "Config file path (YAML file) or output path for generate-spec")
    private String configFile;

    @Option(names = {"-v", "--verbose"}, description = "Enable verbose output")
    private boolean verbose;

    public static void main(String[] args) {
        int exitCode = new CommandLine(new SpinxCli()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() throws Exception {
        if (verbose) {
            System.out.println("Current working directory: " + System.getProperty("user.dir"));
            System.out.println("Original config file path: " + configFile);
        }

        // Resolve config file path - handle both absolute and relative paths
        Path configPath = Paths.get(configFile);
        if (!configPath.isAbsolute()) {
            // If relative path, resolve against current working directory (where CLI is invoked)
            configPath = Paths.get(System.getProperty("user.dir")).resolve(configFile);
        }
        
        // Normalize the path to handle any ".." or "." components
        configPath = configPath.normalize();
        
        String resolvedConfigFile = configPath.toString();

        if (verbose) {
            System.out.println("Resolved config file path: " + resolvedConfigFile);
        }

        // generate-spec writes to the given path — the file does not need to exist yet.
        if ("generate-spec".equals(action)) {
            if (!"kamal".equals(provider)) {
                System.err.println("generate-spec is only supported for the kamal provider");
                return 1;
            }
            try {
                KamalSpecGenerator.generate(resolvedConfigFile);
                return 0;
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                if (verbose) e.printStackTrace();
                return 1;
            }
        }

        if (!Files.exists(configPath)) {
            System.err.println("Config file not found: " + resolvedConfigFile);
            System.err.println("(original path: " + configFile + ")");
            return 1;
        }



        try {
            tools.muthuishere.spinx.CloudDeployer deployer = switch (provider) {
                case "aws-fargate" -> new AwsFargateDeployer();
                case "azure-container-apps" -> new AzureContainerAppsDeployer();
                case "gcp-cloudrun" -> new GcpCloudRunDeployer();
                case "kamal" -> new KamalDeployer();
                default -> {
                    System.err.println("Unknown provider: " + provider);
                    System.err.println("Supported providers: aws-fargate, azure-container-apps, gcp-cloudrun, kamal");
                    yield null;
                }
            };

            if (deployer == null) {
                return 1;
            }

            if (verbose) {
                System.out.println("Using provider: " + provider);
                System.out.println("Executing action: " + action);
            }

            // Initialize with resolved config file path
            deployer.init(resolvedConfigFile);

            // Execute the action
            switch (action) {
                case "setup" -> deployer.setup();
                case "deploy" -> deployer.deploy();
                case "destroy" -> deployer.destroy();
                case "logs" -> deployer.showLogs();
                default -> {
                    System.err.println("Unknown action: " + action);
                    System.err.println("Supported actions: setup, deploy, destroy, logs, generate-spec");
                    return 1;
                }
            }

            if (verbose) {
                System.out.println("Action completed successfully");
            }
            
            return 0;
            
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            if (verbose) {
                e.printStackTrace();
            }
            return 1;
        }
    }
}