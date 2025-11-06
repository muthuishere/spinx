package tools.muthuishere.spinx.aws.fargate;

import tools.muthuishere.spinx.CloudDeployer;
import tools.muthuishere.spinx.Runner;
import tools.muthuishere.spinx.EnvironmentParser;
import org.yaml.snakeyaml.Yaml;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.waiters.WaiterOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.*;
import software.amazon.awssdk.services.cloudwatchlogs.model.ResourceNotFoundException;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.*;
import software.amazon.awssdk.services.ecr.EcrClient;
import software.amazon.awssdk.services.ecr.model.*;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.*;
import software.amazon.awssdk.services.ecs.model.Service;
import software.amazon.awssdk.services.ecs.model.TransportProtocol;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.*;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.LoadBalancer;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.TargetGroup;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.*;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.GetCallerIdentityRequest;

import java.io.FileInputStream;
import java.nio.file.Paths;
import java.util.*;

public class AwsFargateDeployer implements CloudDeployer {
    
    private FargateConfig config;
    private String configFilename;
    private EcsClient ecsClient;
    private Ec2Client ec2Client;
    private EcrClient ecrClient;
    private IamClient iamClient;
    private StsClient stsClient;
    private ElasticLoadBalancingV2Client elbClient;
    private CloudWatchLogsClient cloudWatchLogsClient;

    @Override
    public void init(String configFile) {
        System.out.println("AWS Fargate: Initializing with config file: " + configFile);
        this.configFilename = configFile;
        
        try {
            if (!Paths.get(configFile).toFile().exists()) {
                throw new RuntimeException("Config file not found: " + configFile);
            }
            
            System.out.println("Config file found: " + configFile);
            
            Yaml yaml = new Yaml();
            try (FileInputStream fis = new FileInputStream(configFile)) {
                this.config = yaml.loadAs(fis, FargateConfig.class);
            }
            
            // Convert relative paths to absolute paths based on config file location
            String configDir = Paths.get(configFile).getParent().toString();
            
            // Resolve dockerfilePath relative to config directory
            if (!Paths.get(config.getDockerfilePath()).isAbsolute()) {
                String absoluteDockerPath = Paths.get(configDir, config.getDockerfilePath()).normalize().toString();
                config.setDockerfilePath(absoluteDockerPath);
            }
            
            // Resolve environmentFile relative to config directory
            if (!Paths.get(config.getEnvironmentFile()).isAbsolute()) {
                String absoluteEnvPath = Paths.get(configDir, config.getEnvironmentFile()).normalize().toString();
                config.setEnvironmentFile(absoluteEnvPath);
            }
            
            System.out.println("Loaded config: " + config);
            
            // Load environment variables using EnvironmentParser
            loadEnvironmentVariables();
            
            initializeAwsClients();
                
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize AWS Fargate deployer", e);
        }
    }
    
    private void loadEnvironmentVariables() {
        // Get config file directory for relative path resolution
        String configFileDirectory = null;
        if (configFilename != null) {
            java.io.File configFile = new java.io.File(configFilename);
            configFileDirectory = configFile.getParent();
        }
        
        // Use EnvironmentParser to handle all environment variable parsing
        EnvironmentParser.EnvironmentResult result = EnvironmentParser.parseEnvironmentVariables(
            config.getEnvironmentVariables(),  // YAML environment variables (takes precedence)
            config.getEnvironmentFile(),       // .env file path
            configFileDirectory                // Config file directory for relative path resolution
        );
        
        // Update config with final parsed environment variables
        config.setEnvironmentVariables(result.getEnvironmentVariables());
        
        // Log summary
        System.out.println("📊 Environment parsing complete:");
        System.out.println("   📝 Source: " + result.getSource());
        System.out.println("   📊 Total variables: " + result.getTotalCount());
        if (result.getYamlCount() > 0) {
            System.out.println("   📋 From YAML: " + result.getYamlCount());
        }
        if (result.getEnvFileCount() > 0) {
            System.out.println("   📄 From .env file: " + result.getEnvFileCount());
        }
    }
    
    private void initializeAwsClients() {
        Region region = Region.of(config.getRegion());
        DefaultCredentialsProvider credentialsProvider = DefaultCredentialsProvider.create();
        
        this.ecsClient = EcsClient.builder()
            .region(region)
            .credentialsProvider(credentialsProvider)
            .build();
            
        this.ec2Client = Ec2Client.builder()
            .region(region)
            .credentialsProvider(credentialsProvider)
            .build();
            
        this.ecrClient = EcrClient.builder()
            .region(region)
            .credentialsProvider(credentialsProvider)
            .build();
            
        this.iamClient = IamClient.builder()
            .region(region)
            .credentialsProvider(credentialsProvider)
            .build();
            
        this.stsClient = StsClient.builder()
            .region(region)
            .credentialsProvider(credentialsProvider)
            .build();
            
        this.elbClient = ElasticLoadBalancingV2Client.builder()
            .region(region)
            .credentialsProvider(credentialsProvider)
            .build();
            
        this.cloudWatchLogsClient = CloudWatchLogsClient.builder()
            .region(region)
            .credentialsProvider(credentialsProvider)
            .build();
    }

    @Override
    public void setup() {
        System.out.println("AWS Fargate: Setting up infrastructure for " + config.getClusterName());
        
        try {
            // 1. Create ECR repository
            System.out.println("- Creating ECR repository: " + config.getEcrRepository());
            createEcrRepository();
            
            // 2. Create VPC and networking
            System.out.println("- Creating VPC and networking");
            VpcResources vpcResources = createVpcAndNetworking();
            
            // 3. Create IAM roles
            System.out.println("- Creating IAM roles");
            createIamRoles();
            
            // 4. Create ECS cluster
            System.out.println("- Creating ECS cluster");
            createEcsCluster();
            
            // 5. Create Load Balancer
            System.out.println("- Creating Application Load Balancer");
            LoadBalancerResources lbResources = createLoadBalancer(vpcResources);
            
            // 6. Create CloudWatch Log Group
            System.out.println("- Creating CloudWatch Log Group");
            createLogGroup();
            
            System.out.println("✅ Fargate infrastructure setup completed!");
            
        } catch (Exception e) {
            System.err.println("Setup failed: " + e.getMessage());
            throw new RuntimeException("Failed to setup Fargate infrastructure", e);
        }
    }

    @Override
    public void deploy() {
        System.out.println("AWS Fargate: Deploying " + config.getClusterName());
        
        try {
            // 0. Validate that setup has been run
            System.out.println("- Validating infrastructure setup...");
            validateSetupHasBeenRun();
            
            // Get AWS account ID and construct ECR repository URL
            String accountId = getAccountId();
            String ecrRepositoryUrl = accountId + ".dkr.ecr." + config.getRegion() + ".amazonaws.com/" + config.getEcrRepository();
            
            // Build and push Docker image
            System.out.println("- Building Docker image from " + config.getDockerfilePath());
            buildAndPushImage(ecrRepositoryUrl);
            
            // Create/update task definition
            System.out.println("- Creating/updating task definition");
            String taskDefinitionArn = createOrUpdateTaskDefinition(ecrRepositoryUrl + ":latest", accountId);
            
            // Create/update ECS service
            System.out.println("- Creating/updating ECS service");
            retryECSServiceDeployment(taskDefinitionArn);
            
            // Get the load balancer URL
            String serviceUrl = getServiceUrl();
            
            System.out.println("\n=== AWS Fargate Deployment Complete ===");
            System.out.println("Cluster Name: " + config.getClusterName());
            System.out.println("Service Name: " + config.getEcsServiceName());
            System.out.println("Service URL: " + serviceUrl);
            System.out.println("Region: " + config.getRegion());
            
            // Check if we have HTTPS available
            if (serviceUrl.startsWith("https://")) {
                System.out.println("🔒 HTTPS: Available");
                System.out.println("� HTTP to HTTPS redirect: Enabled");
            } else {
                System.out.println("📡 Protocol: HTTP (ALB default domain)");
                System.out.println("� For HTTPS support:");
                System.out.println("   • ALB default domains (*.elb.amazonaws.com) cannot use SSL certificates");  
                System.out.println("   • For HTTPS, you need a custom domain + ACM certificate");
                System.out.println("   • Example: api.yourdomain.com → CNAME → " + serviceUrl.replace("http://", ""));
                System.out.println("   • Security groups already configured for HTTPS (port 443)");
            }
            
            System.out.println("\nMCP Configuration:");
            System.out.println("Add this to your MCP client config:");
            System.out.println("\"" + config.getClusterName() + "\": {");
            System.out.println("  \"type\": \"http\",");
            System.out.println("  \"url\": \"" + serviceUrl + "/mcp\"");
            System.out.println("}");
            System.out.println("\nNote: It may take a few minutes for the service to be fully available");
            
        } catch (Exception e) {
            System.err.println("Deployment failed: " + e.getMessage());
            throw new RuntimeException("Deployment failed", e);
        }
    }

    @Override
    public void destroy() {
        System.out.println("AWS Fargate: Destroying ALL infrastructure for " + config.getClusterName());
        System.out.println("⚠️  This will delete ALL AWS resources to avoid any billing charges!");
        
        try {
            // 1. Delete ECS service
            System.out.println("- Deleting ECS service");
            deleteEcsService();
            
            // 2. Delete task definition (deregister)
            System.out.println("- Deregistering task definition");
            deregisterTaskDefinition();
            
            // 3. Delete Load Balancer
            System.out.println("- Deleting Load Balancer");
            deleteLoadBalancer();
            
            // 4. Delete ECS cluster
            System.out.println("- Deleting ECS cluster");
            deleteEcsCluster();
            
            // 5. Delete IAM roles (FORCE DELETE - no sharing)
            System.out.println("- Deleting IAM roles");
            deleteIamRoles();
            
            // 6. Delete VPC and networking (FORCE DELETE - no sharing)
            System.out.println("- Deleting VPC and networking");
            deleteVpcAndNetworking();
            
            // 7. Delete CloudWatch Log Group (FORCE DELETE - no sharing)
            System.out.println("- Deleting CloudWatch Log Group");
            deleteLogGroup();
            
            // 8. Delete ECR repository (FORCE DELETE - no sharing)
            System.out.println("- Cleaning up ECR repository");
            deleteEcrRepository();
            
            // 9. Clean up any remaining security groups
            System.out.println("- Cleaning up security groups");
            deleteSecurityGroups();
            
            // 10. Clean up any remaining network interfaces
            System.out.println("- Cleaning up network interfaces");
            deleteNetworkInterfaces();
            
            System.out.println("✅ AWS Fargate infrastructure cleanup completed!");
            System.out.println("");
            System.out.println("📋 Resources cleaned up:");
            System.out.println("   • ECS Service: Deleted");
            System.out.println("   • Task Definitions: Deregistered");
            System.out.println("   • Load Balancer: Deleted");
            System.out.println("   • ECS Cluster: Deleted (after waiting for tasks to stop)");
            System.out.println("   • IAM Roles: Deleted");
            System.out.println("   • CloudWatch Logs: Deleted");
            System.out.println("   • ECR Repository: Deleted");
            System.out.println("   • Security Groups: Cleaned up");
            System.out.println("   • Network Interfaces: Cleaned up");
            System.out.println("");
            System.out.println("💰 All AWS resources deleted to prevent any unexpected costs!");
            
        } catch (Exception e) {
            System.err.println("❌ Cleanup failed: " + e.getMessage());
            System.err.println("");
            System.err.println("⚠️  IMPORTANT: Please check AWS Console manually to ensure all resources are deleted!");
            System.err.println("📍 Region: " + config.getRegion());
            System.err.println("🏷️  Resource Names to Check:");
            System.err.println("   • ECS Cluster: " + config.getClusterName());
            System.err.println("   • ECS Service: " + config.getEcsServiceName());
            System.err.println("   • Load Balancer: " + config.getLoadBalancerName());
            System.err.println("   • ECR Repository: " + config.getEcrRepository());
            System.err.println("   • IAM Roles: " + config.getExecutionRoleName() + ", " + config.getTaskRoleName());
            System.err.println("   • Log Group: " + config.getLogGroupName());
            System.err.println("");
            System.err.println("💡 You can also try running destroy again - some resources may need more time to stop");
        }
    }

    // Infrastructure creation methods
    private void createEcrRepository() {
        try {
            // First check if repository exists
            DescribeRepositoriesRequest describeRequest = DescribeRepositoriesRequest.builder()
                .repositoryNames(config.getEcrRepository())
                .build();
            
            DescribeRepositoriesResponse describeResponse = ecrClient.describeRepositories(describeRequest);
            if (!describeResponse.repositories().isEmpty()) {
                String repositoryUri = describeResponse.repositories().get(0).repositoryUri();
                System.out.println("ECR repository already exists: " + repositoryUri);
                return;
            }
            
        } catch (RepositoryNotFoundException e) {
            // Repository doesn't exist, proceed to create it
        }
        
        try {
            CreateRepositoryRequest request = CreateRepositoryRequest.builder()
                .repositoryName(config.getEcrRepository())
                .imageTagMutability(ImageTagMutability.MUTABLE)
                .build();
            
            CreateRepositoryResponse response = ecrClient.createRepository(request);
            System.out.println("Created ECR repository: " + response.repository().repositoryUri());
            
        } catch (Exception e) {
            System.err.println("Failed to create ECR repository: " + e.getMessage());
            throw e;
        }
    }

    private VpcResources createVpcAndNetworking() {
        VpcResources resources = new VpcResources();
        
        try {
            // First try to find default VPC
            DescribeVpcsResponse vpcsResponse = ec2Client.describeVpcs(DescribeVpcsRequest.builder()
                .filters(Filter.builder().name("isDefault").values("true").build())
                .build());
                
            if (!vpcsResponse.vpcs().isEmpty()) {
                // Use existing default VPC
                Vpc defaultVpc = vpcsResponse.vpcs().get(0);
                resources.setVpcId(defaultVpc.vpcId());
                
                // Get subnets in the default VPC
                DescribeSubnetsResponse subnetsResponse = ec2Client.describeSubnets(DescribeSubnetsRequest.builder()
                    .filters(Filter.builder().name("vpc-id").values(defaultVpc.vpcId()).build())
                    .build());
                    
                List<String> subnetIds = subnetsResponse.subnets().stream()
                    .map(Subnet::subnetId)
                    .toList();
                    
                resources.setSubnetIds(subnetIds);
                
                System.out.println("Using existing default VPC: " + resources.getVpcId() + " with " + subnetIds.size() + " subnets");
                
            } else {
                // No default VPC found, create one using AWS CLI
                System.out.println("No default VPC found, creating default VPC using AWS CLI...");
                createDefaultVpcWithAwsCli();
                
                // Wait a moment for VPC creation to complete
                Thread.sleep(5000);
                
                // Try again to find the default VPC
                vpcsResponse = ec2Client.describeVpcs(DescribeVpcsRequest.builder()
                    .filters(Filter.builder().name("isDefault").values("true").build())
                    .build());
                    
                if (!vpcsResponse.vpcs().isEmpty()) {
                    Vpc defaultVpc = vpcsResponse.vpcs().get(0);
                    resources.setVpcId(defaultVpc.vpcId());
                    
                    // Get subnets in the newly created default VPC
                    DescribeSubnetsResponse subnetsResponse = ec2Client.describeSubnets(DescribeSubnetsRequest.builder()
                        .filters(Filter.builder().name("vpc-id").values(defaultVpc.vpcId()).build())
                        .build());
                        
                    List<String> subnetIds = subnetsResponse.subnets().stream()
                        .map(Subnet::subnetId)
                        .toList();
                        
                    resources.setSubnetIds(subnetIds);
                    
                    System.out.println("Created and using new default VPC: " + resources.getVpcId() + " with " + subnetIds.size() + " subnets");
                } else {
                    throw new RuntimeException("Failed to create default VPC. Please check AWS CLI configuration and permissions.");
                }
            }
            
            return resources;
            
        } catch (Exception e) {
            System.err.println("Failed to setup VPC and networking: " + e.getMessage());
            throw new RuntimeException("Failed to setup VPC and networking", e);
        }
    }
    
    private void createDefaultVpcWithAwsCli() {
        try {
            System.out.println("Creating default VPC using AWS CLI...");
            
            // Set AWS region for the CLI command
            String awsRegion = config.getRegion();
            
            // Create default VPC using AWS CLI
            Runner.runCommand("aws ec2 create-default-vpc --region " + awsRegion);
            
            System.out.println("✅ Default VPC created successfully in region: " + awsRegion);
            
        } catch (Exception e) {
            System.err.println("Failed to create default VPC with AWS CLI: " + e.getMessage());
            System.err.println("Please ensure:");
            System.err.println("1. AWS CLI is installed and configured");
            System.err.println("2. You have appropriate EC2 permissions");
            System.err.println("3. The region supports default VPC creation");
            throw new RuntimeException("Failed to create default VPC", e);
        }
    }
    private void createIamRoles() {
        createTaskExecutionRole();
        createTaskRole();
    }
    
    private void createTaskExecutionRole() {
        String roleName = config.getExecutionRoleName();
        
        try {
            // Check if role exists
            GetRoleRequest getRoleRequest = GetRoleRequest.builder()
                .roleName(roleName)
                .build();
            
            GetRoleResponse getRoleResponse = iamClient.getRole(getRoleRequest);
            System.out.println("Task execution IAM role already exists: " + getRoleResponse.role().arn());
            return;
            
        } catch (NoSuchEntityException e) {
            // Role doesn't exist, proceed to create it
        }
        
        try {
            String trustPolicy = """
                {
                    "Version": "2012-10-17",
                    "Statement": [
                        {
                            "Effect": "Allow",
                            "Principal": {
                                "Service": "ecs-tasks.amazonaws.com"
                            },
                            "Action": "sts:AssumeRole"
                        }
                    ]
                }
                """;
            
            CreateRoleRequest createRoleRequest = CreateRoleRequest.builder()
                .roleName(roleName)
                .assumeRolePolicyDocument(trustPolicy)
                .description("IAM execution role for ECS Fargate task " + config.getServiceName())
                .build();
            
            CreateRoleResponse response = iamClient.createRole(createRoleRequest);
            System.out.println("Created task execution IAM role: " + response.role().arn());
            
            // Attach the required policy
            AttachRolePolicyRequest attachPolicyRequest = AttachRolePolicyRequest.builder()
                .roleName(roleName)
                .policyArn("arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy")
                .build();
            
            iamClient.attachRolePolicy(attachPolicyRequest);
            System.out.println("Attached ECS task execution policy to role");
            
        } catch (Exception e) {
            System.err.println("Failed to create task execution IAM role: " + e.getMessage());
            throw e;
        }
    }
    
    private void createTaskRole() {
        String roleName = config.getTaskRoleName();
        
        try {
            // Check if role exists
            GetRoleRequest getRoleRequest = GetRoleRequest.builder()
                .roleName(roleName)
                .build();
            
            GetRoleResponse getRoleResponse = iamClient.getRole(getRoleRequest);
            System.out.println("Task IAM role already exists: " + getRoleResponse.role().arn());
            return;
            
        } catch (NoSuchEntityException e) {
            // Role doesn't exist, proceed to create it
        }
        
        try {
            String trustPolicy = """
                {
                    "Version": "2012-10-17",
                    "Statement": [
                        {
                            "Effect": "Allow",
                            "Principal": {
                                "Service": "ecs-tasks.amazonaws.com"
                            },
                            "Action": "sts:AssumeRole"
                        }
                    ]
                }
                """;
            
            CreateRoleRequest createRoleRequest = CreateRoleRequest.builder()
                .roleName(roleName)
                .assumeRolePolicyDocument(trustPolicy)
                .description("IAM task role for ECS Fargate task " + config.getServiceName())
                .build();
            
            CreateRoleResponse response = iamClient.createRole(createRoleRequest);
            System.out.println("Created task IAM role: " + response.role().arn());
            
        } catch (Exception e) {
            System.err.println("Failed to create task IAM role: " + e.getMessage());
            throw e;
        }
    }

    
    private void createEcsServiceLinkedRole() {
        try {
            System.out.println("Creating ECS service-linked role...");
            
            // Use AWS CLI to create the service-linked role for ECS
            Runner.runCommand("aws iam create-service-linked-role --aws-service-name ecs.amazonaws.com --region " + config.getRegion());
            
            System.out.println("✅ ECS service-linked role created successfully");
            
        } catch (Exception e) {
            System.out.println("ℹ️  ECS service-linked role may already exist (this is normal)");
            // This is expected if the role already exists, so we continue
        }
    }

    private void createEcsCluster() {
        // First, ensure the ECS service-linked role exists
        createEcsServiceLinkedRole();
        
        try {
            // Check if cluster exists
            DescribeClustersRequest describeRequest = DescribeClustersRequest.builder()
                .clusters(config.getClusterName())
                .build();
            
            DescribeClustersResponse describeResponse = ecsClient.describeClusters(describeRequest);
            if (!describeResponse.clusters().isEmpty() && 
                describeResponse.clusters().get(0).status().equals("ACTIVE")) {
                System.out.println("ECS cluster already exists: " + config.getClusterName());
                return;
            }
            
        } catch (Exception e) {
            // Proceed to create cluster
        }
        
        try {
            CreateClusterRequest createRequest = CreateClusterRequest.builder()
                .clusterName(config.getClusterName())
                .capacityProviders("FARGATE")
                .defaultCapacityProviderStrategy(
                    CapacityProviderStrategyItem.builder()
                        .capacityProvider("FARGATE")
                        .weight(1)
                        .build()
                )
                .build();
            
            CreateClusterResponse response = ecsClient.createCluster(createRequest);
            System.out.println("Created ECS cluster: " + response.cluster().clusterArn());
            
        } catch (Exception e) {
            System.err.println("Failed to create ECS cluster: " + e.getMessage());
            throw e;
        }
    }

    private LoadBalancerResources createLoadBalancer(VpcResources vpcResources) {
        LoadBalancerResources lbResources = new LoadBalancerResources();
        
        try {
            // Create security group for load balancer
            String securityGroupId = createLoadBalancerSecurityGroup(vpcResources.getVpcId());
            lbResources.setSecurityGroupId(securityGroupId);
            
            // Check if Application Load Balancer already exists
            LoadBalancer loadBalancer = null;
            try {
                DescribeLoadBalancersRequest describeRequest = DescribeLoadBalancersRequest.builder()
                    .names(config.getLoadBalancerName())
                    .build();
                
                DescribeLoadBalancersResponse describeResponse = elbClient.describeLoadBalancers(describeRequest);
                
                if (!describeResponse.loadBalancers().isEmpty()) {
                    loadBalancer = describeResponse.loadBalancers().get(0);
                    System.out.println("Using existing Load Balancer: " + loadBalancer.dnsName());
                }
            } catch (Exception e) {
                // Load balancer doesn't exist, proceed to create it
                System.out.println("Load balancer doesn't exist, creating new one...");
            }
            
            // Create Application Load Balancer if it doesn't exist
            if (loadBalancer == null) {
                CreateLoadBalancerRequest createLbRequest = CreateLoadBalancerRequest.builder()
                    .name(config.getLoadBalancerName())
                    .scheme(LoadBalancerSchemeEnum.INTERNET_FACING)
                    .type(LoadBalancerTypeEnum.APPLICATION)
                    .subnets(vpcResources.getSubnetIds())
                    .securityGroups(securityGroupId)
                    .build();
                
                CreateLoadBalancerResponse lbResponse = elbClient.createLoadBalancer(createLbRequest);
                loadBalancer = lbResponse.loadBalancers().get(0);
                System.out.println("Created Load Balancer: " + loadBalancer.dnsName());
            }
            
            lbResources.setLoadBalancerArn(loadBalancer.loadBalancerArn());
            lbResources.setDnsName(loadBalancer.dnsName());
            
            // Check if target group already exists
            TargetGroup targetGroup = null;
            try {
                DescribeTargetGroupsRequest describeRequest = DescribeTargetGroupsRequest.builder()
                    .names(config.getTargetGroupName())
                    .build();
                
                DescribeTargetGroupsResponse describeResponse = elbClient.describeTargetGroups(describeRequest);
                
                if (!describeResponse.targetGroups().isEmpty()) {
                    targetGroup = describeResponse.targetGroups().get(0);
                    System.out.println("Using existing Target Group: " + targetGroup.targetGroupName());
                }
            } catch (Exception e) {
                // Target group doesn't exist, proceed to create it
                System.out.println("Target group doesn't exist, creating new one...");
            }
            
            // Create target group if it doesn't exist
            if (targetGroup == null) {
                CreateTargetGroupRequest createTgRequest = CreateTargetGroupRequest.builder()
                    .name(config.getTargetGroupName())
                    .protocol(ProtocolEnum.HTTP)
                    .port(config.getContainerPort())
                    .vpcId(vpcResources.getVpcId())
                    .targetType(TargetTypeEnum.IP)
                    .healthCheckPath(config.getHealthCheckPath())
                    .healthCheckIntervalSeconds(config.getHealthCheckIntervalSeconds())
                    .build();
                
                CreateTargetGroupResponse tgResponse = elbClient.createTargetGroup(createTgRequest);
                targetGroup = tgResponse.targetGroups().get(0);
                System.out.println("Created Target Group: " + targetGroup.targetGroupName());
            }
            
            lbResources.setTargetGroupArn(targetGroup.targetGroupArn());
            
            // Check if listener already exists
            String listenerArn = null;
            try {
                DescribeListenersRequest describeRequest = DescribeListenersRequest.builder()
                    .loadBalancerArn(loadBalancer.loadBalancerArn())
                    .build();
                
                DescribeListenersResponse describeResponse = elbClient.describeListeners(describeRequest);
                
                if (!describeResponse.listeners().isEmpty()) {
                    listenerArn = describeResponse.listeners().get(0).listenerArn();
                    System.out.println("Using existing Listener for Load Balancer");
                }
            } catch (Exception e) {
                // Listener doesn't exist, proceed to create it
                System.out.println("Listener doesn't exist, creating new one...");
            }
            
            // Create listener if it doesn't exist
            if (listenerArn == null) {
                // ALB default domains (*.elb.amazonaws.com) cannot use SSL certificates
                // HTTPS requires a custom domain + ACM certificate
                String certificateArn = null;
                System.out.println("ℹ️  ALB domain ready for HTTPS (security groups configured for port 443)");
                
                if (certificateArn != null) {
                    // Create HTTPS listener
                    CreateListenerRequest httpsListenerRequest = CreateListenerRequest.builder()
                        .loadBalancerArn(loadBalancer.loadBalancerArn())
                        .protocol(ProtocolEnum.HTTPS)
                        .port(443)
                        .certificates(Certificate.builder().certificateArn(certificateArn).build())
                        .defaultActions(Action.builder()
                            .type(ActionTypeEnum.FORWARD)
                            .targetGroupArn(targetGroup.targetGroupArn())
                            .build())
                        .build();
                    
                    CreateListenerResponse httpsListenerResponse = elbClient.createListener(httpsListenerRequest);
                    listenerArn = httpsListenerResponse.listeners().get(0).listenerArn();
                    System.out.println("Created HTTPS Listener for Load Balancer on port 443");
                    
                    // Also create HTTP listener that redirects to HTTPS
                    try {
                        CreateListenerRequest httpRedirectListenerRequest = CreateListenerRequest.builder()
                            .loadBalancerArn(loadBalancer.loadBalancerArn())
                            .protocol(ProtocolEnum.HTTP)
                            .port(80)
                            .defaultActions(Action.builder()
                                .type(ActionTypeEnum.REDIRECT)
                                .redirectConfig(RedirectActionConfig.builder()
                                    .protocol("HTTPS")
                                    .port("443")
                                    .statusCode(RedirectActionStatusCodeEnum.HTTP_301)
                                    .build())
                                .build())
                            .build();
                        
                        elbClient.createListener(httpRedirectListenerRequest);
                        System.out.println("Created HTTP to HTTPS redirect listener on port 80");
                    } catch (Exception e) {
                        System.out.println("Could not create HTTP redirect listener (may already exist): " + e.getMessage());
                    }
                } else {
                    // Create HTTP listener (default behavior)
                    CreateListenerRequest httpListenerRequest = CreateListenerRequest.builder()
                        .loadBalancerArn(loadBalancer.loadBalancerArn())
                        .protocol(ProtocolEnum.HTTP)
                        .port(80)
                        .defaultActions(Action.builder()
                            .type(ActionTypeEnum.FORWARD)
                            .targetGroupArn(targetGroup.targetGroupArn())
                            .build())
                        .build();
                    
                    CreateListenerResponse httpListenerResponse = elbClient.createListener(httpListenerRequest);
                    listenerArn = httpListenerResponse.listeners().get(0).listenerArn();
                    System.out.println("Created HTTP Listener for Load Balancer on port 80");
                }
            }
            
            lbResources.setListenerArn(listenerArn);
            
            return lbResources;
            
        } catch (Exception e) {
            System.err.println("Failed to create Load Balancer: " + e.getMessage());
            throw e;
        }
    }
    
    private String createLoadBalancerSecurityGroup(String vpcId) {
        try {
            String sgName = config.getLoadBalancerSecurityGroupName();
            
            // First, check if security group already exists
            try {
                DescribeSecurityGroupsRequest describeRequest = DescribeSecurityGroupsRequest.builder()
                    .filters(Filter.builder()
                        .name("group-name")
                        .values(sgName)
                        .build(),
                        Filter.builder()
                        .name("vpc-id")
                        .values(vpcId)
                        .build())
                    .build();
                
                DescribeSecurityGroupsResponse describeResponse = ec2Client.describeSecurityGroups(describeRequest);
                
                if (!describeResponse.securityGroups().isEmpty()) {
                    String existingSecurityGroupId = describeResponse.securityGroups().get(0).groupId();
                    System.out.println("Using existing Load Balancer Security Group: " + existingSecurityGroupId);
                    return existingSecurityGroupId;
                }
            } catch (Exception e) {
                // Security group doesn't exist, proceed to create it
                System.out.println("Security group doesn't exist, creating new one...");
            }
            
            // Create new security group
            CreateSecurityGroupRequest createSgRequest = CreateSecurityGroupRequest.builder()
                .groupName(sgName)
                .description("Security group for " + config.getServiceName() + " ALB")
                .vpcId(vpcId)
                .build();
            
            CreateSecurityGroupResponse sgResponse = ec2Client.createSecurityGroup(createSgRequest);
            String securityGroupId = sgResponse.groupId();
            
            // Allow HTTP traffic from anywhere
            try {
                AuthorizeSecurityGroupIngressRequest httpIngressRequest = AuthorizeSecurityGroupIngressRequest.builder()
                    .groupId(securityGroupId)
                    .ipPermissions(IpPermission.builder()
                        .ipProtocol("tcp")
                        .fromPort(80)
                        .toPort(80)
                        .ipRanges(IpRange.builder().cidrIp("0.0.0.0/0").build())
                        .build())
                    .build();
                
                ec2Client.authorizeSecurityGroupIngress(httpIngressRequest);
            } catch (Exception e) {
                // HTTP ingress rule might already exist, that's OK
                System.out.println("HTTP ingress rule may already exist (OK): " + e.getMessage());
            }
            
            // Allow HTTPS traffic from anywhere if HTTPS is enabled
            if (config.isEnableHttps()) {
                try {
                    AuthorizeSecurityGroupIngressRequest httpsIngressRequest = AuthorizeSecurityGroupIngressRequest.builder()
                        .groupId(securityGroupId)
                        .ipPermissions(IpPermission.builder()
                            .ipProtocol("tcp")
                            .fromPort(443)
                            .toPort(443)
                            .ipRanges(IpRange.builder().cidrIp("0.0.0.0/0").build())
                            .build())
                        .build();
                    
                    ec2Client.authorizeSecurityGroupIngress(httpsIngressRequest);
                    System.out.println("Added HTTPS (port 443) ingress rule");
                } catch (Exception e) {
                    // HTTPS ingress rule might already exist, that's OK
                    System.out.println("HTTPS ingress rule may already exist (OK): " + e.getMessage());
                }
            }
            
            System.out.println("Created Load Balancer Security Group: " + securityGroupId);
            
            return securityGroupId;
            
        } catch (Exception e) {
            System.err.println("Failed to create Load Balancer Security Group: " + e.getMessage());
            throw e;
        }
    }

    private void createLogGroup() {
        String logGroupName = config.getLogGroupName();
        
        try {
            CreateLogGroupRequest request = CreateLogGroupRequest.builder()
                .logGroupName(logGroupName)
                .build();
            
            cloudWatchLogsClient.createLogGroup(request);
            System.out.println("Created CloudWatch Log Group: " + logGroupName);
            
        } catch (ResourceAlreadyExistsException e) {
            System.out.println("CloudWatch Log Group already exists: " + logGroupName);
        } catch (Exception e) {
            System.err.println("Failed to create CloudWatch Log Group: " + e.getMessage());
            throw e;
        }
    }

    // Deployment methods
    private void buildAndPushImage(String ecrRepositoryUrl) {
        System.out.println("- Building Docker image from " + config.getDockerfilePath());
        String imageTag = ecrRepositoryUrl + ":latest";
        Runner.runDockerBuildxFast(imageTag, config.getDockerfilePath(), ".");
        
        // Login to ECR using AWS CLI
        System.out.println("- Logging into ECR");
        String accountId = getAccountId();
        System.out.println("  Registry: " + accountId + ".dkr.ecr." + config.getRegion() + ".amazonaws.com");
        System.out.println("  Username: AWS");
        String ecrLoginCommand = String.format(
            "aws ecr get-login-password --region %s | docker login --username AWS --password-stdin %s.dkr.ecr.%s.amazonaws.com",
            config.getRegion(), accountId, config.getRegion()
        );
        Runner.runSecureCommand(ecrLoginCommand, 
            "aws ecr get-login-password --region " + config.getRegion() + " | docker login --username AWS --password-stdin [ECR_REGISTRY]");
        
        // Push to ECR
        System.out.println("- Pushing image to ECR: " + ecrRepositoryUrl);
        Runner.runDocker("push", imageTag);
        
        // Wait for image to be fully available in ECR
        System.out.println("- Waiting for image to be available in ECR...");
        waitForImageAvailabilityInECR(ecrRepositoryUrl + ":latest");
    }

    private void waitForImageAvailabilityInECR(String imageUri) {
        System.out.println("    Performing quick ECR image verification...");
        
        try {
            String[] imageparts = imageUri.split(":");
            String repository = imageparts[0].substring(imageparts[0].lastIndexOf("/") + 1);
            String tag = imageparts.length > 1 ? imageparts[1] : "latest";
            
            List<String> command = Arrays.asList(
                "aws", "ecr", "describe-images",
                "--repository-name", repository,
                "--image-ids", "imageTag=" + tag,
                "--region", config.getRegion(),
                "--query", "imageDetails[0].imageDigest",
                "--output", "text"
            );
            
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            
            int exitCode = process.waitFor();
            if (exitCode == 0) {
                System.out.println("    ✅ Image verified as available in ECR");
                return;
            }
            
            // If first check fails, wait a bit and try once more
            System.out.println("    Image not immediately available, waiting 10 seconds...");
            Thread.sleep(10000);
            
            process = pb.start();
            exitCode = process.waitFor();
            if (exitCode == 0) {
                System.out.println("    ✅ Image verified as available in ECR (after retry)");
                return;
            }
            
            System.out.println("    ⚠️  ECR image verification inconclusive, proceeding with deployment...");
            
        } catch (Exception e) {
            System.out.println("    ⚠️  ECR image verification failed: " + e.getMessage());
            System.out.println("    Proceeding with deployment...");
        }
    }

    private String createOrUpdateTaskDefinition(String imageUri, String accountId) {
        String executionRoleArn = "arn:aws:iam::" + accountId + ":role/" + config.getExecutionRoleName();
        String taskRoleArn = "arn:aws:iam::" + accountId + ":role/" + config.getTaskRoleName();
        String logGroupName = config.getLogGroupName();
        
        try {
            System.out.println("📋 Adding " + config.getEnvironmentVariables().size() + " environment variables to task definition:");
            for (Map.Entry<String, String> entry : config.getEnvironmentVariables().entrySet()) {
                String value = entry.getValue();
                String displayValue = value.length() > 20 ? value.substring(0, 20) + "..." : value;
                System.out.println("   🔑 " + entry.getKey() + "=" + displayValue);
            }
            
            RegisterTaskDefinitionRequest request = RegisterTaskDefinitionRequest.builder()
                .family(config.getTaskDefinitionFamily())
                .networkMode(NetworkMode.AWSVPC)
                .requiresCompatibilities(Compatibility.FARGATE)
                .cpu(String.valueOf(config.getCpu()))
                .memory(String.valueOf(config.getMemory()))
                .executionRoleArn(executionRoleArn)
                .taskRoleArn(taskRoleArn)
                .containerDefinitions(ContainerDefinition.builder()
                    .name(config.getServiceName())
                    .image(imageUri)
                    .essential(true)
                    .portMappings(PortMapping.builder()
                        .containerPort(config.getContainerPort())
                        .protocol(TransportProtocol.TCP)
                        .build())
                    .logConfiguration(LogConfiguration.builder()
                        .logDriver(LogDriver.AWSLOGS)
                        .options(Map.of(
                            "awslogs-group", logGroupName,
                            "awslogs-region", config.getRegion(),
                            "awslogs-stream-prefix", "ecs"
                        ))
                        .build())
                    .environment(
                        config.getEnvironmentVariables().entrySet().stream()
                            .map(entry -> KeyValuePair.builder()
                                .name(entry.getKey())
                                .value(entry.getValue())
                                .build())
                            .toArray(KeyValuePair[]::new)
                    )
                    .build())
                .build();
            
            RegisterTaskDefinitionResponse response = ecsClient.registerTaskDefinition(request);
            String taskDefinitionArn = response.taskDefinition().taskDefinitionArn();
            
            System.out.println("Registered task definition: " + taskDefinitionArn);
            
            return taskDefinitionArn;
            
        } catch (Exception e) {
            System.err.println("Failed to register task definition: " + e.getMessage());
            throw e;
        }
    }

    private void retryECSServiceDeployment(String taskDefinitionArn) {
        int maxRetries = 3;
        int currentRetry = 0;
        Exception lastException = null;
        
        while (currentRetry < maxRetries) {
            try {
                System.out.println("Attempting ECS service deployment (attempt " + (currentRetry + 1) + "/" + maxRetries + ")...");
                createOrUpdateService(taskDefinitionArn);
                return; // Success, exit the method
                
            } catch (Exception e) {
                lastException = e;
                
                // Check if this is an image not found error
                boolean isImageNotFound = e.getMessage() != null && 
                    (e.getMessage().contains("image") && 
                     (e.getMessage().contains("not found") || e.getMessage().contains("pull") || e.getMessage().contains("manifest")));
                
                currentRetry++;
                
                if (isImageNotFound && currentRetry < maxRetries) {
                    int waitTime = 30 + (currentRetry * 30); // 30s, 60s, 90s
                    System.out.println("    Image not found error detected. Waiting " + waitTime + " seconds for image propagation...");
                    try {
                        Thread.sleep(waitTime * 1000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("ECS service deployment retry interrupted", ie);
                    }
                } else if (currentRetry < maxRetries) {
                    System.out.println("    ECS service deployment failed with error: " + e.getMessage());
                    System.out.println("    Retrying in 15 seconds...");
                    try {
                        Thread.sleep(15000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("ECS service deployment retry interrupted", ie);
                    }
                } else {
                    System.out.println("    Maximum retry attempts reached");
                    break;
                }
            }
        }
        
        throw new RuntimeException("Failed to deploy ECS service after " + maxRetries + " attempts", lastException);
    }

    private void createOrUpdateService(String taskDefinitionArn) {
        try {
            // Get VPC and subnet information
            VpcResources vpcResources = getVpcResources();
            LoadBalancerResources lbResources = getLoadBalancerResources();
            
            // Create security group for ECS service
            String serviceSecurityGroupId = createServiceSecurityGroup(vpcResources.getVpcId(), lbResources.getSecurityGroupId());
            
            // Check if service exists
            DescribeServicesRequest describeRequest = DescribeServicesRequest.builder()
                .cluster(config.getClusterName())
                .services(config.getEcsServiceName())
                .build();
            
            DescribeServicesResponse describeResponse = ecsClient.describeServices(describeRequest);
            
            if (!describeResponse.services().isEmpty() && 
                !describeResponse.services().get(0).status().equals("INACTIVE")) {
                
                // Update existing service
                System.out.println("- Service exists, updating...");
                UpdateServiceRequest updateRequest = UpdateServiceRequest.builder()
                    .cluster(config.getClusterName())
                    .service(config.getEcsServiceName())
                    .taskDefinition(taskDefinitionArn)
                    .desiredCount(config.getDesiredCount())
                    .build();
                
                UpdateServiceResponse updateResponse = ecsClient.updateService(updateRequest);
                System.out.println("Updated ECS service: " + updateResponse.service().serviceArn());
                
            } else {
                // Create new service
                System.out.println("- Service doesn't exist, creating...");
                CreateServiceRequest createRequest = CreateServiceRequest.builder()
                    .cluster(config.getClusterName())
                    .serviceName(config.getEcsServiceName())
                    .taskDefinition(taskDefinitionArn)
                    .desiredCount(config.getDesiredCount())
                    .launchType(LaunchType.FARGATE)
                    .networkConfiguration(NetworkConfiguration.builder()
                        .awsvpcConfiguration(AwsVpcConfiguration.builder()
                            .subnets(vpcResources.getSubnetIds())
                            .securityGroups(serviceSecurityGroupId)
                            .assignPublicIp(AssignPublicIp.ENABLED)
                            .build())
                        .build())
                    .loadBalancers(software.amazon.awssdk.services.ecs.model.LoadBalancer.builder()
                        .targetGroupArn(lbResources.getTargetGroupArn())
                        .containerName(config.getServiceName())
                        .containerPort(config.getContainerPort())
                        .build())
                    .build();
                
                CreateServiceResponse createResponse = ecsClient.createService(createRequest);
                System.out.println("Created ECS service: " + createResponse.service().serviceArn());
            }
            
            // Wait for service to be stable
            System.out.println("- Waiting for service to be stable...");
            waitForServiceStable();
            
        } catch (Exception e) {
            System.err.println("Failed to create/update ECS service: " + e.getMessage());
            throw e;
        }
    }
    




    private String createServiceSecurityGroup(String vpcId, String lbSecurityGroupId) {
        try {
            String sgName = config.getSecurityGroupName();
            
            // First, check if security group already exists
            try {
                DescribeSecurityGroupsRequest describeRequest = DescribeSecurityGroupsRequest.builder()
                    .filters(Filter.builder()
                        .name("group-name")
                        .values(sgName)
                        .build(),
                        Filter.builder()
                        .name("vpc-id")
                        .values(vpcId)
                        .build())
                    .build();
                
                DescribeSecurityGroupsResponse describeResponse = ec2Client.describeSecurityGroups(describeRequest);
                
                if (!describeResponse.securityGroups().isEmpty()) {
                    String existingSecurityGroupId = describeResponse.securityGroups().get(0).groupId();
                    System.out.println("Using existing Service Security Group: " + existingSecurityGroupId);
                    return existingSecurityGroupId;
                }
            } catch (Exception e) {
                // Security group doesn't exist, proceed to create it
                System.out.println("Service security group doesn't exist, creating new one...");
            }
            
            CreateSecurityGroupRequest createSgRequest = CreateSecurityGroupRequest.builder()
                .groupName(sgName)
                .description("Security group for " + config.getServiceName() + " ECS service")
                .vpcId(vpcId)
                .build();
            
            CreateSecurityGroupResponse sgResponse = ec2Client.createSecurityGroup(createSgRequest);
            String securityGroupId = sgResponse.groupId();
            
            // Allow traffic from load balancer security group
            try {
                AuthorizeSecurityGroupIngressRequest ingressRequest = AuthorizeSecurityGroupIngressRequest.builder()
                    .groupId(securityGroupId)
                    .ipPermissions(IpPermission.builder()
                        .ipProtocol("tcp")
                        .fromPort(config.getContainerPort())
                        .toPort(config.getContainerPort())
                        .userIdGroupPairs(UserIdGroupPair.builder()
                            .groupId(lbSecurityGroupId)
                            .build())
                        .build())
                    .build();
                
                ec2Client.authorizeSecurityGroupIngress(ingressRequest);
            } catch (Exception e) {
                // Ingress rule might already exist, that's OK
                System.out.println("Ingress rule may already exist (OK): " + e.getMessage());
            }
            
            System.out.println("Created Service Security Group: " + securityGroupId);
            
            return securityGroupId;
            
        } catch (Exception e) {
            System.err.println("Failed to create Service Security Group: " + e.getMessage());
            throw e;
        }
    }

    private void waitForServiceStable() {
        try {
            WaiterOverrideConfiguration waiterConfig = WaiterOverrideConfiguration.builder()
                .maxAttempts(20) // 10 minutes max
                .build();
            
            DescribeServicesRequest request = DescribeServicesRequest.builder()
                .cluster(config.getClusterName())
                .services(config.getEcsServiceName())
                .build();
            
            ecsClient.waiter().waitUntilServicesStable(request, waiterConfig);
            System.out.println("✅ Service is stable and ready");
            
        } catch (Exception e) {
            System.out.println("⚠️ Service may still be starting. Check AWS Console for status.");
        }
    }

    // Utility methods
    private void validateSetupHasBeenRun() {
        try {
            System.out.println("    Checking ECS cluster: " + config.getClusterName());
            
            // Check if ECS cluster exists
            try {
                DescribeClustersRequest clusterRequest = DescribeClustersRequest.builder()
                    .clusters(config.getClusterName())
                    .build();
                
                DescribeClustersResponse clusterResponse = ecsClient.describeClusters(clusterRequest);
                
                if (clusterResponse.clusters().isEmpty() || 
                    !"ACTIVE".equals(clusterResponse.clusters().get(0).status())) {
                    throw new RuntimeException("ECS cluster not found or not active");
                }
            } catch (Exception e) {
                System.err.println("\n❌ Setup validation failed!");
                System.err.println("ECS cluster '" + config.getClusterName() + "' does not exist or is not active.");
                System.err.println("\nPlease run the setup command first:");
                System.err.println("  spinx aws-fargate setup");
                throw new RuntimeException("Infrastructure setup required. ECS cluster '" + config.getClusterName() + "' not found.", e);
            }
            
            System.out.println("    Checking ECR repository: " + config.getEcrRepository());
            
            // Check if ECR repository exists
            try {
                DescribeRepositoriesRequest ecrRequest = DescribeRepositoriesRequest.builder()
                    .repositoryNames(config.getEcrRepository())
                    .build();
                
                ecrClient.describeRepositories(ecrRequest);
            } catch (Exception e) {
                System.err.println("\n❌ Setup validation failed!");
                System.err.println("ECR repository '" + config.getEcrRepository() + "' does not exist.");
                System.err.println("\nPlease run the setup command first:");
                System.err.println("  spinx aws-fargate setup");
                throw new RuntimeException("Infrastructure setup required. ECR repository '" + config.getEcrRepository() + "' not found.", e);
            }
            
            System.out.println("    Checking Load Balancer: " + config.getLoadBalancerName());
            
            // Check if Load Balancer exists
            try {
                DescribeLoadBalancersRequest lbRequest = DescribeLoadBalancersRequest.builder()
                    .names(config.getLoadBalancerName())
                    .build();
                
                DescribeLoadBalancersResponse lbResponse = elbClient.describeLoadBalancers(lbRequest);
                
                if (lbResponse.loadBalancers().isEmpty()) {
                    throw new RuntimeException("Load balancer not found");
                }
            } catch (Exception e) {
                System.err.println("\n❌ Setup validation failed!");
                System.err.println("Application Load Balancer '" + config.getLoadBalancerName() + "' does not exist.");
                System.err.println("\nPlease run the setup command first:");
                System.err.println("  spinx aws-fargate setup");
                throw new RuntimeException("Infrastructure setup required. Load Balancer '" + config.getLoadBalancerName() + "' not found.", e);
            }
            
            System.out.println("    Checking IAM execution role: " + config.getExecutionRoleName());
            
            // Check if IAM execution role exists
            try {
                GetRoleRequest roleRequest = GetRoleRequest.builder()
                    .roleName(config.getExecutionRoleName())
                    .build();
                
                iamClient.getRole(roleRequest);
            } catch (Exception e) {
                System.err.println("\n❌ Setup validation failed!");
                System.err.println("IAM execution role '" + config.getExecutionRoleName() + "' does not exist.");
                System.err.println("\nPlease run the setup command first:");
                System.err.println("  spinx aws-fargate setup");
                throw new RuntimeException("Infrastructure setup required. IAM role '" + config.getExecutionRoleName() + "' not found.", e);
            }
            
            System.out.println("    ✅ Infrastructure validation completed successfully");
            
        } catch (RuntimeException e) {
            throw e; // Re-throw runtime exceptions with our custom messages
        } catch (Exception e) {
            System.err.println("\n❌ Setup validation failed!");
            System.err.println("Error validating infrastructure: " + e.getMessage());
            System.err.println("\nPlease run the setup command first:");
            System.err.println("  spinx aws-fargate setup");
            throw new RuntimeException("Infrastructure validation failed", e);
        }
    }
    
    private String getAccountId() {
        try {
            GetCallerIdentityRequest request = GetCallerIdentityRequest.builder().build();
            return stsClient.getCallerIdentity(request).account();
        } catch (Exception e) {
            System.err.println("Failed to get AWS account ID: " + e.getMessage());
            throw e;
        }
    }

    private String getServiceUrl() {
        try {
            // Get load balancer DNS name
            DescribeLoadBalancersRequest request = DescribeLoadBalancersRequest.builder()
                .names(config.getLoadBalancerName())
                .build();
            
            DescribeLoadBalancersResponse response = elbClient.describeLoadBalancers(request);
            if (!response.loadBalancers().isEmpty()) {
                String dnsName = response.loadBalancers().get(0).dnsName();
                
                // Always use HTTP for ALB default domains
                return "http://" + dnsName;
            }
            
            return "Load Balancer not found";
            
        } catch (Exception e) {
            return "Error getting service URL: " + e.getMessage();
        }
    }

    private VpcResources getVpcResources() {
        // Simplified - in a real implementation, you'd store this info from setup
        VpcResources resources = new VpcResources();
        
        try {
            DescribeVpcsResponse vpcsResponse = ec2Client.describeVpcs(DescribeVpcsRequest.builder()
                .filters(Filter.builder().name("isDefault").values("true").build())
                .build());
                
            if (!vpcsResponse.vpcs().isEmpty()) {
                Vpc defaultVpc = vpcsResponse.vpcs().get(0);
                resources.setVpcId(defaultVpc.vpcId());
                
                DescribeSubnetsResponse subnetsResponse = ec2Client.describeSubnets(DescribeSubnetsRequest.builder()
                    .filters(Filter.builder().name("vpc-id").values(defaultVpc.vpcId()).build())
                    .build());
                    
                List<String> subnetIds = subnetsResponse.subnets().stream()
                    .map(Subnet::subnetId)
                    .toList();
                    
                resources.setSubnetIds(subnetIds);
            }
            
            return resources;
            
        } catch (Exception e) {
            throw new RuntimeException("Failed to get VPC resources", e);
        }
    }

    private LoadBalancerResources getLoadBalancerResources() {
        LoadBalancerResources resources = new LoadBalancerResources();
        
        try {
            // Get load balancer
            DescribeLoadBalancersRequest lbRequest = DescribeLoadBalancersRequest.builder()
                .names(config.getLoadBalancerName())
                .build();
            
            DescribeLoadBalancersResponse lbResponse = elbClient.describeLoadBalancers(lbRequest);
            if (!lbResponse.loadBalancers().isEmpty()) {
                LoadBalancer lb = lbResponse.loadBalancers().get(0);
                resources.setLoadBalancerArn(lb.loadBalancerArn());
                resources.setDnsName(lb.dnsName());
                resources.setSecurityGroupId(lb.securityGroups().get(0));
            }
            
            // Get target group
            DescribeTargetGroupsRequest tgRequest = DescribeTargetGroupsRequest.builder()
                .names(config.getTargetGroupName())
                .build();
            
            DescribeTargetGroupsResponse tgResponse = elbClient.describeTargetGroups(tgRequest);
            if (!tgResponse.targetGroups().isEmpty()) {
                resources.setTargetGroupArn(tgResponse.targetGroups().get(0).targetGroupArn());
            }
            
            return resources;
            
        } catch (Exception e) {
            throw new RuntimeException("Failed to get Load Balancer resources", e);
        }
    }

    // Cleanup methods - implementation would be similar to Lambda deployer
    private void deleteEcsService() {
        try {
            String clusterName = config.getClusterName();
            String serviceName = config.getEcsServiceName();
            
            // First check if service exists and get its status
            try {
                DescribeServicesRequest describeRequest = DescribeServicesRequest.builder()
                    .cluster(clusterName)
                    .services(serviceName)
                    .build();
                
                DescribeServicesResponse describeResponse = ecsClient.describeServices(describeRequest);
                
                if (describeResponse.services().isEmpty()) {
                    System.out.println("ECS service not found or already deleted: " + serviceName);
                    return;
                }
                
                Service service = describeResponse.services().get(0);
                if (!"ACTIVE".equals(service.status())) {
                    System.out.println("ECS service not in ACTIVE state (current: " + service.status() + "): " + serviceName);
                    // Try to delete it anyway if it exists
                    if (!"INACTIVE".equals(service.status())) {
                        deleteServiceDirectly(clusterName, serviceName);
                    }
                    return;
                }
                
                // Scale down to 0 first
                UpdateServiceRequest updateRequest = UpdateServiceRequest.builder()
                    .cluster(clusterName)
                    .service(serviceName)
                    .desiredCount(0)
                    .build();
                
                ecsClient.updateService(updateRequest);
                System.out.println("Scaled down ECS service to 0 tasks: " + serviceName);
                
            } catch (Exception e) {
                System.out.println("Could not describe ECS service: " + e.getMessage());
            }
            
            // Delete service
            deleteServiceDirectly(clusterName, serviceName);
            
        } catch (Exception e) {
            System.err.println("Failed to delete ECS service: " + e.getMessage());
        }
    }
    
    private void deleteServiceDirectly(String clusterName, String serviceName) {
        try {
            DeleteServiceRequest deleteRequest = DeleteServiceRequest.builder()
                .cluster(clusterName)
                .service(serviceName)
                .build();
            
            ecsClient.deleteService(deleteRequest);
            System.out.println("Deleted ECS service: " + serviceName);
        } catch (Exception e) {
            System.out.println("Could not delete ECS service: " + e.getMessage());
        }
    }

    private void deregisterTaskDefinition() {
        // Implementation for deregistering task definition
        try {
            ListTaskDefinitionsRequest listRequest = ListTaskDefinitionsRequest.builder()
                .familyPrefix(config.getTaskDefinitionFamily())
                .status(TaskDefinitionStatus.ACTIVE)
                .build();
            
            ListTaskDefinitionsResponse listResponse = ecsClient.listTaskDefinitions(listRequest);
            
            for (String taskDefArn : listResponse.taskDefinitionArns()) {
                DeregisterTaskDefinitionRequest deregisterRequest = DeregisterTaskDefinitionRequest.builder()
                    .taskDefinition(taskDefArn)
                    .build();
                
                ecsClient.deregisterTaskDefinition(deregisterRequest);
                System.out.println("Deregistered task definition: " + taskDefArn);
            }
            
        } catch (Exception e) {
            System.err.println("Failed to deregister task definitions: " + e.getMessage());
        }
    }

    private void deleteLoadBalancer() {
        try {
            String loadBalancerArn = getLoadBalancerArn();
            if (loadBalancerArn == null) {
                System.out.println("Load Balancer not found or already deleted: " + config.getLoadBalancerName());
                return;
            }
            
            // Delete listeners first
            try {
                DescribeListenersRequest listenerRequest = DescribeListenersRequest.builder()
                    .loadBalancerArn(loadBalancerArn)
                    .build();
                
                DescribeListenersResponse listenerResponse = elbClient.describeListeners(listenerRequest);
                for (Listener listener : listenerResponse.listeners()) {
                    DeleteListenerRequest deleteListenerRequest = DeleteListenerRequest.builder()
                        .listenerArn(listener.listenerArn())
                        .build();
                    
                    elbClient.deleteListener(deleteListenerRequest);
                }
            } catch (Exception e) {
                System.out.println("Could not delete listeners: " + e.getMessage());
            }
            
            // Delete target group
            try {
                String targetGroupArn = getTargetGroupArn();
                if (targetGroupArn != null) {
                    DeleteTargetGroupRequest deleteTgRequest = DeleteTargetGroupRequest.builder()
                        .targetGroupArn(targetGroupArn)
                        .build();
                    
                    elbClient.deleteTargetGroup(deleteTgRequest);
                }
            } catch (Exception e) {
                System.out.println("Could not delete target group: " + e.getMessage());
            }
            
            // Delete load balancer
            DeleteLoadBalancerRequest deleteLbRequest = DeleteLoadBalancerRequest.builder()
                .loadBalancerArn(loadBalancerArn)
                .build();
            
            elbClient.deleteLoadBalancer(deleteLbRequest);
            
            System.out.println("Deleted Load Balancer: " + config.getLoadBalancerName());
            
        } catch (Exception e) {
            System.err.println("Failed to delete Load Balancer: " + e.getMessage());
        }
    }

    private void deleteEcsCluster() {
        try {
            String clusterName = config.getClusterName();
            
            // First, wait for all tasks to stop
            System.out.println("  Waiting for all tasks to stop in cluster: " + clusterName);
            waitForTasksToStop(clusterName);
            
            // Now delete the cluster
            DeleteClusterRequest request = DeleteClusterRequest.builder()
                .cluster(clusterName)
                .build();
            
            ecsClient.deleteCluster(request);
            System.out.println("Deleted ECS cluster: " + clusterName);
            
        } catch (Exception e) {
            System.err.println("Failed to delete ECS cluster: " + e.getMessage());
            // Don't throw - continue with cleanup
        }
    }
    
    private void waitForTasksToStop(String clusterName) {
        try {
            int maxWaitMinutes = 5;
            int waitIntervalSeconds = 10;
            int maxAttempts = (maxWaitMinutes * 60) / waitIntervalSeconds;
            
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                ListTasksRequest listRequest = ListTasksRequest.builder()
                    .cluster(clusterName)
                    .build();
                
                ListTasksResponse listResponse = ecsClient.listTasks(listRequest);
                
                if (listResponse.taskArns().isEmpty()) {
                    System.out.println("  ✅ All tasks stopped");
                    return;
                }
                
                System.out.println("  ⏳ Waiting for " + listResponse.taskArns().size() + " tasks to stop... (attempt " + attempt + "/" + maxAttempts + ")");
                Thread.sleep(waitIntervalSeconds * 1000);
            }
            
            System.out.println("  ⚠️  Some tasks may still be running, but proceeding with cluster deletion");
            
        } catch (Exception e) {
            System.err.println("  ⚠️  Could not check task status: " + e.getMessage());
        }
    }

    private void deleteIamRoles() {
        // Implementation for deleting IAM roles
        deleteRole(config.getExecutionRoleName(), "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy");
        deleteRole(config.getTaskRoleName(), null);
    }
    
    private void deleteRole(String roleName, String policyArn) {
        try {
            // Detach policies first if specified
            if (policyArn != null) {
                DetachRolePolicyRequest detachRequest = DetachRolePolicyRequest.builder()
                    .roleName(roleName)
                    .policyArn(policyArn)
                    .build();
                
                iamClient.detachRolePolicy(detachRequest);
            }
            
            // Delete the role
            DeleteRoleRequest deleteRequest = DeleteRoleRequest.builder()
                .roleName(roleName)
                .build();
            
            iamClient.deleteRole(deleteRequest);
            System.out.println("Deleted IAM role: " + roleName);
            
        } catch (NoSuchEntityException e) {
            System.out.println("IAM role not found or already deleted: " + roleName);
        } catch (Exception e) {
            System.err.println("Failed to delete IAM role " + roleName + ": " + e.getMessage());
        }
    }

    private void deleteVpcAndNetworking() {
        // Implementation for cleaning up VPC resources
        // For now, we're using default VPC, so no cleanup needed
        System.out.println("Using default VPC, no cleanup needed");
    }

    private void deleteLogGroup() {
        // Implementation for deleting CloudWatch log group
        try {
            String logGroupName = config.getLogGroupName();
            
            DeleteLogGroupRequest request = DeleteLogGroupRequest.builder()
                .logGroupName(logGroupName)
                .build();
            
            cloudWatchLogsClient.deleteLogGroup(request);
            System.out.println("Deleted CloudWatch Log Group: " + logGroupName);
            
        } catch (ResourceNotFoundException e) {
            System.out.println("CloudWatch Log Group not found or already deleted");
        } catch (Exception e) {
            System.err.println("Failed to delete CloudWatch Log Group: " + e.getMessage());
        }
    }

    private void deleteEcrRepository() {
        // Implementation for deleting ECR repository
        try {
            DeleteRepositoryRequest request = DeleteRepositoryRequest.builder()
                .repositoryName(config.getEcrRepository())
                .force(true) // Delete even if it contains images
                .build();
            
            ecrClient.deleteRepository(request);
            System.out.println("Deleted ECR repository: " + config.getEcrRepository());
            
        } catch (RepositoryNotFoundException e) {
            System.out.println("ECR repository not found or already deleted");
        } catch (Exception e) {
            System.err.println("Failed to delete ECR repository: " + e.getMessage());
        }
    }

    // Helper methods for getting ARNs
    private String getLoadBalancerArn() {
        try {
            DescribeLoadBalancersRequest request = DescribeLoadBalancersRequest.builder()
                .names(config.getLoadBalancerName())
                .build();
            
            DescribeLoadBalancersResponse response = elbClient.describeLoadBalancers(request);
            return response.loadBalancers().get(0).loadBalancerArn();
        } catch (Exception e) {
            return null;
        }
    }
    
    private String getTargetGroupArn() {
        try {
            DescribeTargetGroupsRequest request = DescribeTargetGroupsRequest.builder()
                .names(config.getTargetGroupName())
                .build();
            
            DescribeTargetGroupsResponse response = elbClient.describeTargetGroups(request);
            return response.targetGroups().get(0).targetGroupArn();
        } catch (Exception e) {
            return null;
        }
    }

    // Log tailing method (similar to Lambda)
    public void showLogs() {
        String logGroupName = config.getLogGroupName();
        System.out.println("=== Tailing logs for ECS service: " + config.getEcsServiceName() + " ===");
        System.out.println("Log Group: " + logGroupName);
        System.out.println("Press Ctrl+C to stop...\n");
        
        long startTime = System.currentTimeMillis() - (5 * 60 * 1000); // Start from 5 minutes ago
        String nextToken = null;
        
        try {
            while (true) {
                try {
                    // Get recent log events
                    FilterLogEventsRequest.Builder requestBuilder = FilterLogEventsRequest.builder()
                        .logGroupName(logGroupName)
                        .startTime(startTime)
                        .limit(100);
                    
                    if (nextToken != null) {
                        requestBuilder.nextToken(nextToken);
                    }
                    
                    FilterLogEventsResponse response = cloudWatchLogsClient.filterLogEvents(requestBuilder.build());
                    
                    // Print new log events
                    for (FilteredLogEvent event : response.events()) {
                        printFormattedLogEvent(event);
                        startTime = Math.max(startTime, event.timestamp() + 1); // Update start time
                    }
                    
                    nextToken = response.nextToken();
                    
                    // If no more events and no next token, wait before polling again
                    if (response.events().isEmpty()) {
                        Thread.sleep(2000); // Wait 2 seconds before next poll
                        nextToken = null; // Reset token for fresh search
                    }
                    
                } catch (ResourceNotFoundException e) {
                    System.err.println("❌ Log group not found: " + logGroupName);
                    System.err.println("   Service may not have been deployed yet or doesn't exist.");
                    Thread.sleep(5000); // Wait 5 seconds before retrying
                    
                } catch (Exception e) {
                    System.err.println("❌ Error reading logs: " + e.getMessage());
                    Thread.sleep(5000); // Wait 5 seconds before retrying
                }
            }
            
        } catch (InterruptedException e) {
            System.out.println("\n🛑 Log tailing stopped by user");
            Thread.currentThread().interrupt();
        }
    }

    private void printFormattedLogEvent(FilteredLogEvent event) {
        String timestamp = formatTimestamp(event.timestamp());
        String message = event.message().trim();
        
        // Categorize and format different types of log messages
        if (message.contains("Started") && message.contains("Application")) {
            System.out.println("🌟 " + timestamp + " ✅ " + message);
            
        } else if (message.contains("Tomcat started on port") || message.contains("server started")) {
            System.out.println("🌐 " + timestamp + " ✅ " + message);
            
        } else if (message.contains("ERROR") || message.contains("Error") || message.contains("Exception")) {
            System.out.println("❌ " + timestamp + " 🔥 " + message);
            
        } else if (message.contains("WARN") || message.contains("Warning")) {
            System.out.println("⚠️  " + timestamp + " ⚠️  " + message);
            
        } else {
            // Regular application logs
            System.out.println("📝 " + timestamp + " " + message);
        }
    }

    private String formatTimestamp(long timestamp) {
        java.time.Instant instant = java.time.Instant.ofEpochMilli(timestamp);
        java.time.LocalDateTime dateTime = java.time.LocalDateTime.ofInstant(instant, java.time.ZoneId.systemDefault());
        return dateTime.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss.SSS"));
    }

    // Helper classes for storing resource information
    private static class VpcResources {
        private String vpcId;
        private List<String> subnetIds;
        private String internetGatewayId;
        private String routeTableId;
        private boolean isCustomVpc = false;
        
        public String getVpcId() { return vpcId; }
        public void setVpcId(String vpcId) { this.vpcId = vpcId; }
        public List<String> getSubnetIds() { return subnetIds; }
        public void setSubnetIds(List<String> subnetIds) { this.subnetIds = subnetIds; }
        public String getInternetGatewayId() { return internetGatewayId; }
        public void setInternetGatewayId(String internetGatewayId) { this.internetGatewayId = internetGatewayId; }
        public String getRouteTableId() { return routeTableId; }
        public void setRouteTableId(String routeTableId) { this.routeTableId = routeTableId; }
        public boolean isCustomVpc() { return isCustomVpc; }
        public void setCustomVpc(boolean isCustomVpc) { this.isCustomVpc = isCustomVpc; }
    }
    
    private static class LoadBalancerResources {
        private String loadBalancerArn;
        private String targetGroupArn;
        private String listenerArn;
        private String dnsName;
        private String securityGroupId;
        
        // Getters and setters
        public String getLoadBalancerArn() { return loadBalancerArn; }
        public void setLoadBalancerArn(String loadBalancerArn) { this.loadBalancerArn = loadBalancerArn; }
        public String getTargetGroupArn() { return targetGroupArn; }
        public void setTargetGroupArn(String targetGroupArn) { this.targetGroupArn = targetGroupArn; }
        public String getListenerArn() { return listenerArn; }
        public void setListenerArn(String listenerArn) { this.listenerArn = listenerArn; }
        public String getDnsName() { return dnsName; }
        public void setDnsName(String dnsName) { this.dnsName = dnsName; }
        public String getSecurityGroupId() { return securityGroupId; }
        public void setSecurityGroupId(String securityGroupId) { this.securityGroupId = securityGroupId; }
    }
    
    // Additional aggressive cleanup methods
    private void deleteSecurityGroups() {
        try {
            // Delete specific security groups we created (by name)
            String[] securityGroupNames = {
                config.getLoadBalancerSecurityGroupName(),
                config.getSecurityGroupName()
            };
            
            for (String sgName : securityGroupNames) {
                try {
                    // First, find the security group by name
                    var describeRequest = DescribeSecurityGroupsRequest.builder()
                        .filters(Filter.builder()
                            .name("group-name")
                            .values(sgName)
                            .build())
                        .build();
                    
                    var response = ec2Client.describeSecurityGroups(describeRequest);
                    
                    for (var sg : response.securityGroups()) {
                        // Retry deletion with exponential backoff for dependencies
                        boolean deleted = false;
                        int maxRetries = 3;
                        
                        for (int i = 0; i < maxRetries && !deleted; i++) {
                            try {
                                if (i > 0) {
                                    Thread.sleep(5000 * i); // Wait longer each retry
                                }
                                
                                var deleteRequest = DeleteSecurityGroupRequest.builder()
                                    .groupId(sg.groupId())
                                    .build();
                                ec2Client.deleteSecurityGroup(deleteRequest);
                                System.out.println("  ✅ Deleted security group: " + sgName + " (" + sg.groupId() + ")");
                                deleted = true;
                                
                            } catch (Exception e) {
                                if (e.getMessage().contains("dependent object") && i < maxRetries - 1) {
                                    System.out.println("  ⏳ Security group " + sgName + " has dependent objects, retrying in " + (5 * (i + 1)) + " seconds...");
                                } else if (e.getMessage().contains("does not exist")) {
                                    System.out.println("  ✅ Security group " + sgName + " already deleted");
                                    deleted = true;
                                } else {
                                    System.out.println("  ⚠️  Could not delete security group " + sgName + " after " + (i + 1) + " attempts: " + e.getMessage());
                                    if (i == maxRetries - 1) {
                                        System.out.println("     Please manually delete security group: " + sg.groupId());
                                    }
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    System.out.println("  ⚠️  Could not find security group " + sgName + ": " + e.getMessage());
                }
            }
        } catch (Exception e) {
            System.out.println("  ⚠️  Could not clean up security groups: " + e.getMessage());
        }
    }
    
    private void deleteNetworkInterfaces() {
        try {
            // Delete any ENIs with our cluster name in description
            var describeRequest = DescribeNetworkInterfacesRequest.builder()
                .filters(Filter.builder()
                    .name("description")
                    .values("*" + config.getClusterName() + "*")
                    .build())
                .build();
            
            var response = ec2Client.describeNetworkInterfaces(describeRequest);
            for (var eni : response.networkInterfaces()) {
                if (eni.status() == NetworkInterfaceStatus.AVAILABLE) {
                    try {
                        var deleteRequest = DeleteNetworkInterfaceRequest.builder()
                            .networkInterfaceId(eni.networkInterfaceId())
                            .build();
                        ec2Client.deleteNetworkInterface(deleteRequest);
                        System.out.println("✅ Deleted network interface: " + eni.networkInterfaceId());
                    } catch (Exception e) {
                        System.out.println("Could not delete network interface " + eni.networkInterfaceId() + ": " + e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("Could not clean up network interfaces: " + e.getMessage());
        }
    }
}