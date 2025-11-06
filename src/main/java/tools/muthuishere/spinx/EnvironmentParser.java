package tools.muthuishere.spinx;

import java.io.File;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import io.github.cdimascio.dotenv.Dotenv;

/**
 * Static utility class for parsing environment variables from specified sources only.
 * 
 * This class provides a unified way to load and merge environment variables from:
 * 1. YAML config environmentVariables section (takes precedence)
 * 2. .env files (as base values)
 * 
 * IMPORTANT: System environment variables are completely ignored. Only the specified
 * environmentFile and environmentVariables from YAML config are considered.
 * 
 * The class handles path resolution, file validation, and provides detailed logging.
 */
public class EnvironmentParser {
    
    /**
     * Result of environment variable parsing containing the final map and metadata
     */
    public static class EnvironmentResult {
        private final Map<String, String> environmentVariables;
        private final int yamlCount;
        private final int envFileCount;
        private final String source;
        
        public EnvironmentResult(Map<String, String> environmentVariables, int yamlCount, int envFileCount, String source) {
            this.environmentVariables = environmentVariables;
            this.yamlCount = yamlCount;
            this.envFileCount = envFileCount;
            this.source = source;
        }
        
        public Map<String, String> getEnvironmentVariables() { return environmentVariables; }
        public int getYamlCount() { return yamlCount; }
        public int getEnvFileCount() { return envFileCount; }
        public String getSource() { return source; }
        public int getTotalCount() { return environmentVariables.size(); }
    }
    
    /**
     * Parse environment variables with YAML config taking precedence over .env file
     * 
     * @param yamlEnvironmentVariables Environment variables from YAML config (can be null)
     * @param environmentFilePath Path to .env file (can be null or empty)
     * @param configFileDirectory Directory containing the main config file (for relative path resolution)
     * @return EnvironmentResult containing parsed variables and metadata
     */
    public static EnvironmentResult parseEnvironmentVariables(
            Map<String, String> yamlEnvironmentVariables,
            String environmentFilePath, 
            String configFileDirectory) {
        
        System.out.println("🔧 Parsing environment variables...");
        
        // Start with environment file as base (if provided)
        Map<String, String> finalEnvVars = new HashMap<>();
        int envFileCount = 0;
        
        // 1. Load from .env file first (as base)
        if (environmentFilePath != null && !environmentFilePath.trim().isEmpty()) {
            String resolvedPath = resolveEnvironmentFilePath(environmentFilePath, configFileDirectory);
            Map<String, String> envFileVars = loadEnvironmentFile(resolvedPath);
            if (envFileVars != null) {
                finalEnvVars.putAll(envFileVars);
                envFileCount = envFileVars.size();
                System.out.println("📁 Loaded " + envFileCount + " variables from .env file: " + resolvedPath);
            }
        }
        
        // 2. Override with YAML config variables (YAML takes precedence)
        int yamlCount = 0;
        if (yamlEnvironmentVariables != null && !yamlEnvironmentVariables.isEmpty()) {
            for (Map.Entry<String, String> entry : yamlEnvironmentVariables.entrySet()) {
                String key = entry.getKey();
                String yamlValue = entry.getValue();
                String previousValue = finalEnvVars.put(key, yamlValue);
                yamlCount++;
                
                if (previousValue != null) {
                    System.out.println("   🔄 YAML override: " + key + " (was from .env file)");
                } else {
                    System.out.println("   📝 YAML: " + key + "=" + truncateValue(yamlValue));
                }
            }
            System.out.println("📝 Applied " + yamlCount + " variables from YAML config");
        }
        
        // Determine primary source
        String source;
        if (yamlCount > 0 && envFileCount > 0) {
            source = "YAML config (with .env file base)";
        } else if (yamlCount > 0) {
            source = "YAML config only";
        } else if (envFileCount > 0) {
            source = ".env file only";
        } else {
            source = "none";
        }
        
        // Summary
        if (finalEnvVars.isEmpty()) {
            System.out.println("⚠️  No environment variables found - container will run with no env vars");
        } else {
            System.out.println("✅ Final environment: " + finalEnvVars.size() + " variables from " + source);
            System.out.println("   📋 Variables: " + String.join(", ", finalEnvVars.keySet()));
        }
        
        return new EnvironmentResult(finalEnvVars, yamlCount, envFileCount, source);
    }
    
    /**
     * Resolve environment file path to absolute path, handling relative paths
     */
    private static String resolveEnvironmentFilePath(String environmentFilePath, String configFileDirectory) {
        if (environmentFilePath == null || environmentFilePath.trim().isEmpty()) {
            return null;
        }
        
        // If already absolute, return as-is
        if (Paths.get(environmentFilePath).isAbsolute()) {
            return environmentFilePath;
        }
        
        // Resolve relative to config file directory
        if (configFileDirectory != null) {
            String resolvedPath = Paths.get(configFileDirectory, environmentFilePath).normalize().toString();
            System.out.println("🔗 Resolved relative path '" + environmentFilePath + "' to: " + resolvedPath);
            return resolvedPath;
        }
        
        return environmentFilePath;
    }
    
    /**
     * Load environment variables from a .env file using dotenv library
     */
    private static Map<String, String> loadEnvironmentFile(String filePath) {
        if (filePath == null || filePath.trim().isEmpty()) {
            return null;
        }
        
        File envFile = new File(filePath);
        if (!envFile.exists()) {
            System.out.println("📁 Environment file not found: " + filePath);
            return null;
        }
        
        if (!envFile.canRead()) {
            System.out.println("❌ Cannot read environment file: " + filePath);
            return null;
        }
        
        try {
            // First, parse .env file manually to get the set of keys that are actually defined in the file
            Set<String> validEnvKeys = extractValidKeysFromEnvFile(envFile);
            
            // Then use dotenv library to load with proper parsing, but filter to only our valid keys
            Dotenv dotenv = Dotenv.configure()
                    .directory(envFile.getParent())
                    .filename(envFile.getName())
                    .ignoreIfMissing()
                    .ignoreIfMalformed()
                    .load();
            
            // Filter dotenv results to only include keys that are actually in the .env file
            Map<String, String> envVars = new HashMap<>();
            for (String key : validEnvKeys) {
                String value = dotenv.get(key);
                if (value != null) {
                    envVars.put(key, value);
                    System.out.println("   🔑 " + key + "=" + truncateValue(value));
                }
            }
            
            System.out.println("   📋 Loaded " + envVars.size() + " variables from .env file only (system env vars filtered out)");
            return envVars;
            
        } catch (Exception e) {
            System.err.println("❌ Failed to load environment file '" + filePath + "': " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Extract valid keys from .env file by parsing lines manually
     */
    private static Set<String> extractValidKeysFromEnvFile(File envFile) {
        Set<String> validKeys = new HashSet<>();
        
        try (java.io.BufferedReader reader = java.nio.file.Files.newBufferedReader(envFile.toPath())) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                
                // Skip empty lines and comments
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                
                // Only include lines that contain '=' (valid key=value format)
                if (line.contains("=")) {
                    int equalIndex = line.indexOf('=');
                    if (equalIndex > 0) {
                        String key = line.substring(0, equalIndex).trim();
                        if (!key.isEmpty()) {
                            validKeys.add(key);
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("❌ Failed to extract keys from .env file: " + e.getMessage());
        }
        
        return validKeys;
    }
    
    /**
     * Truncate long values for display purposes
     */
    private static String truncateValue(String value) {
        if (value == null) return "null";
        
        // Check if this looks like sensitive data and mask it
        if (isSensitiveValue(value)) {
            return maskSensitiveData(value);
        }
        
        // For non-sensitive data, truncate if too long
        if (value.length() <= 30) return value;
        return value.substring(0, 30) + "...";
    }
    
    private static boolean isSensitiveValue(String value) {
        if (value == null || value.length() < 8) return false;
        
        // Check for patterns that suggest sensitive data
        String lower = value.toLowerCase();
        return lower.matches(".*[0-9a-f]{32,}.*") ||  // Long hex strings (API keys, tokens)
               lower.matches(".*[a-z0-9+/]{40,}={0,2}.*") ||  // Base64 encoded data
               value.matches(".*[A-Za-z0-9+/]{64,}.*") ||  // Long random strings
               lower.contains("password") ||
               lower.contains("secret") ||
               lower.contains("token") ||
               lower.contains("key");
    }
    
    private static String maskSensitiveData(String input) {
        if (input == null || input.length() <= 8) {
            return "***";
        }
        // Show first 4 and last 4 characters, mask the middle
        return input.substring(0, 4) + "***" + input.substring(input.length() - 4);
    }
    
    /**
     * Convenience method for cases where only .env file is needed
     */
    public static EnvironmentResult parseEnvironmentFile(String environmentFilePath, String configFileDirectory) {
        return parseEnvironmentVariables(null, environmentFilePath, configFileDirectory);
    }
    
    /**
     * Convenience method for cases where only YAML config is needed
     */
    public static EnvironmentResult parseYamlEnvironmentVariables(Map<String, String> yamlEnvironmentVariables) {
        return parseEnvironmentVariables(yamlEnvironmentVariables, null, null);
    }
}