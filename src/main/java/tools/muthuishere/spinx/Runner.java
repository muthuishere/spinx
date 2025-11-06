package tools.muthuishere.spinx;

import java.io.File;

public class Runner {
    
    // Get project root directory (where build.gradle is located)
    public static File getProjectRoot() {
        String currentDir = System.getProperty("user.dir");
        File dir = new File(currentDir);
        
        // Look for build.gradle to confirm we're in project root
        while (dir != null && !new File(dir, "build.gradle").exists()) {
            dir = dir.getParentFile();
        }
        
        return dir != null ? dir : new File(currentDir);
    }
    
    public static void runDocker(String... args) {
        try {
            System.out.println("Running: docker " + String.join(" ", args));
            ProcessBuilder pb = new ProcessBuilder();
            pb.command("docker");
            for (String arg : args) {
                pb.command().add(arg);
            }
            pb.directory(getProjectRoot()); // Set working directory to project root
            
            // Enable BuildKit for faster builds and better caching
            pb.environment().put("DOCKER_BUILDKIT", "1");
            
            pb.inheritIO()
              .start()
              .waitFor();
        } catch (Exception e) {
            System.err.println("Error running Docker: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }

    public static void runDockerBuildFast(String imageTag, String dockerfilePath, String context, String... extraArgs) {
        try {
            java.util.List<String> buildArgs = new java.util.ArrayList<>();
            buildArgs.add("build");
            
            // Add performance optimizations
            buildArgs.add("--platform");
            buildArgs.add("linux/amd64");
            buildArgs.add("--provenance=false");
            
            // Enable BuildKit features for better caching
            buildArgs.add("--cache-from");
            buildArgs.add("type=local,src=/tmp/.buildx-cache");
            buildArgs.add("--cache-to");
            buildArgs.add("type=local,dest=/tmp/.buildx-cache-new,mode=max");
            
            // Add build-time optimizations
            buildArgs.add("--progress");
            buildArgs.add("plain"); // Better for CI/logs
            
            // Add extra arguments
            for (String arg : extraArgs) {
                buildArgs.add(arg);
            }
            
            // Add standard arguments
            buildArgs.add("-t");
            buildArgs.add(imageTag);
            buildArgs.add("-f");
            buildArgs.add(dockerfilePath);
            buildArgs.add(context);
            
            System.out.println("🚀 Running optimized Docker build with BuildKit...");
            System.out.println("Running: docker " + String.join(" ", buildArgs));
            
            ProcessBuilder pb = new ProcessBuilder();
            pb.command("docker");
            pb.command().addAll(buildArgs);
            pb.directory(getProjectRoot());
            
            // Enable BuildKit and optimization features
            pb.environment().put("DOCKER_BUILDKIT", "1");
            pb.environment().put("BUILDKIT_PROGRESS", "plain");
            pb.environment().put("DOCKER_CLI_EXPERIMENTAL", "enabled");
            
            int exitCode = pb.inheritIO().start().waitFor();
            
            if (exitCode == 0) {
                // Move cache to preserve it for next build
                try {
                    ProcessBuilder cacheMoveCmd = new ProcessBuilder("sh", "-c", 
                        "rm -rf /tmp/.buildx-cache && mv /tmp/.buildx-cache-new /tmp/.buildx-cache || true");
                    cacheMoveCmd.start().waitFor();
                } catch (Exception e) {
                    System.out.println("Note: Could not optimize cache (this is normal): " + e.getMessage());
                }
            }
            
            if (exitCode != 0) {
                throw new RuntimeException("Docker build failed with exit code: " + exitCode);
            }
            
        } catch (Exception e) {
            System.err.println("Error running optimized Docker build: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }

    public static void setupDockerBuildx() {
        try {
            System.out.println("🔧 Setting up Docker Buildx for even faster builds...");
            
            // Check if buildx is available
            ProcessBuilder checkBuildx = new ProcessBuilder("docker", "buildx", "version");
            checkBuildx.directory(getProjectRoot());
            Process checkProcess = checkBuildx.start();
            int exitCode = checkProcess.waitFor();
            
            if (exitCode == 0) {
                System.out.println("✅ Docker Buildx is available");
                
                // Try to create/use a builder instance for better performance
                try {
                    ProcessBuilder createBuilder = new ProcessBuilder("docker", "buildx", "create", 
                        "--name", "spinx-builder", "--use", "--bootstrap");
                    createBuilder.directory(getProjectRoot());
                    createBuilder.environment().put("DOCKER_BUILDKIT", "1");
                    
                    Process builderProcess = createBuilder.start();
                    int builderExitCode = builderProcess.waitFor();
                    
                    if (builderExitCode == 0) {
                        System.out.println("✅ Created optimized Docker builder 'spinx-builder'");
                    } else {
                        System.out.println("ℹ️  Using existing Docker builder (this is normal)");
                    }
                } catch (Exception e) {
                    System.out.println("ℹ️  Could not create custom builder, using default (this is normal)");
                }
            } else {
                System.out.println("ℹ️  Docker Buildx not available, using standard Docker build");
            }
        } catch (Exception e) {
            System.out.println("ℹ️  Could not setup Docker Buildx, using standard Docker build: " + e.getMessage());
        }
    }

    public static void runDockerBuildxFast(String imageTag, String dockerfilePath, String context, String... extraArgs) {
        try {
            // First try to setup buildx
            setupDockerBuildx();
            
            java.util.List<String> buildArgs = new java.util.ArrayList<>();
            buildArgs.add("buildx");
            buildArgs.add("build");
            
            // Add performance optimizations
            buildArgs.add("--platform");
            buildArgs.add("linux/amd64");
            buildArgs.add("--provenance=false");
            
            // Enhanced caching with buildx
            buildArgs.add("--cache-from");
            buildArgs.add("type=local,src=/tmp/.buildx-cache");
            buildArgs.add("--cache-to");
            buildArgs.add("type=local,dest=/tmp/.buildx-cache-new,mode=max");
            
            // Load the image into local Docker daemon
            buildArgs.add("--load");
            
            // Add progress output
            buildArgs.add("--progress");
            buildArgs.add("plain");
            
            // Add extra arguments
            for (String arg : extraArgs) {
                buildArgs.add(arg);
            }
            
            // Add standard arguments
            buildArgs.add("-t");
            buildArgs.add(imageTag);
            buildArgs.add("-f");
            buildArgs.add(dockerfilePath);
            buildArgs.add(context);
            
            System.out.println("🚀 Running super-optimized Docker buildx build...");
            System.out.println("Running: docker " + String.join(" ", buildArgs));
            
            ProcessBuilder pb = new ProcessBuilder();
            pb.command("docker");
            pb.command().addAll(buildArgs);
            pb.directory(getProjectRoot());
            
            // Enable all optimization features
            pb.environment().put("DOCKER_BUILDKIT", "1");
            pb.environment().put("BUILDKIT_PROGRESS", "plain");
            pb.environment().put("DOCKER_CLI_EXPERIMENTAL", "enabled");
            
            int exitCode = pb.inheritIO().start().waitFor();
            
            if (exitCode == 0) {
                // Move cache to preserve it for next build
                try {
                    ProcessBuilder cacheMoveCmd = new ProcessBuilder("sh", "-c", 
                        "rm -rf /tmp/.buildx-cache && mv /tmp/.buildx-cache-new /tmp/.buildx-cache || true");
                    cacheMoveCmd.start().waitFor();
                } catch (Exception e) {
                    System.out.println("Note: Could not optimize cache (this is normal): " + e.getMessage());
                }
                System.out.println("✅ Super-fast Docker buildx build completed!");
            }
            
            if (exitCode != 0) {
                System.out.println("⚠️  Buildx build failed, falling back to regular build...");
                // Fallback to regular build
                runDockerBuildFast(imageTag, dockerfilePath, context, extraArgs);
            }
            
        } catch (Exception e) {
            System.out.println("⚠️  Buildx build failed, falling back to regular build...");
            // Fallback to regular build
            runDockerBuildFast(imageTag, dockerfilePath, context, extraArgs);
        }
    }
    
    public static void runAwsCli(String... args) {
        try {
            System.out.println("Running: aws " + String.join(" ", args));
            ProcessBuilder pb = new ProcessBuilder();
            pb.command("aws");
            for (String arg : args) {
                pb.command().add(arg);
            }
            pb.directory(getProjectRoot()); // Set working directory to project root
            pb.inheritIO()
              .start()
              .waitFor();
        } catch (Exception e) {
            System.err.println("Error running AWS CLI: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }
    
    public static void runPulumiUp() {
        runPulumi("up");
    }
    
    public static void runPulumiDown() {
        runPulumi("destroy", "--yes");
    }
    
    public static void runPulumiDeploy() {
        runPulumi("up");
    }
    
    public static void runPulumi(String... args) {
        try {
            System.out.println("Running: pulumi " + String.join(" ", args));
            ProcessBuilder pb = new ProcessBuilder();
            pb.command("pulumi");
            for (String arg : args) {
                pb.command().add(arg);
            }
            pb.directory(getProjectRoot()); // Set working directory to project root
            pb.inheritIO()
              .start()
              .waitFor();
        } catch (Exception e) {
            System.err.println("Error running Pulumi: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }
    
    public static void runAzureCli(String... args) {
        try {
            System.out.println("Running: az " + String.join(" ", args));
            ProcessBuilder pb = new ProcessBuilder();
            pb.command("az");
            for (String arg : args) {
                pb.command().add(arg);
            }
            pb.directory(getProjectRoot()); // Set working directory to project root
            pb.inheritIO()
              .start()
              .waitFor();
        } catch (Exception e) {
            System.err.println("Error running Azure CLI: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }
    
    public static String runAzureCliWithOutput(String... args) {
        try {
            System.out.println("Running: az " + String.join(" ", args));
            ProcessBuilder pb = new ProcessBuilder();
            pb.command("az");
            for (String arg : args) {
                pb.command().add(arg);
            }
            pb.directory(getProjectRoot()); // Set working directory to project root
            
            Process process = pb.start();
            
            // Read the output
            StringBuilder output = new StringBuilder();
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }
            
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new RuntimeException("Azure CLI command failed with exit code: " + exitCode);
            }
            
            return output.toString();
        } catch (Exception e) {
            System.err.println("Error running Azure CLI: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }
    
    public static void runCommand(String command) {
        try {
            System.out.println("Running: " + command);
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", command);
            pb.directory(getProjectRoot()); // Set working directory to project root
            pb.inheritIO()
              .start()
              .waitFor();
        } catch (Exception e) {
            System.err.println("Error running command: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }
    
    // Secure version that masks sensitive data in logs
    public static void runSecureCommand(String command, String description) {
        try {
            System.out.println("Running: " + description);
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", command);
            pb.directory(getProjectRoot()); // Set working directory to project root
            pb.inheritIO()
              .start()
              .waitFor();
        } catch (Exception e) {
            System.err.println("Error running secure command: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }
    
    // Helper method to mask sensitive strings
    public static String maskSensitiveData(String input) {
        if (input == null || input.length() <= 8) {
            return "***";
        }
        // Show first 4 and last 4 characters, mask the middle
        return input.substring(0, 4) + "***" + input.substring(input.length() - 4);
    }
}