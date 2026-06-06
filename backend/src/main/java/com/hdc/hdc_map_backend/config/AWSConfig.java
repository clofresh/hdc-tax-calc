package com.hdc.hdc_map_backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;

@Configuration
public class AWSConfig {

    @Value("${aws.access.key.id:}")
    private String accessKeyId;

    @Value("${aws.secret.access.key:}")
    private String secretAccessKey;

    @Value("${aws.bedrock.access.key.id:}")
    private String bedrockAccessKeyId;

    @Value("${aws.bedrock.secret.access.key:}")
    private String bedrockSecretAccessKey;

    @Value("${bedrock.region:us-east-2}")
    private String bedrockRegion;

    public record ResolvedKey(String accessKeyId, String secretAccessKey) {}

    static ResolvedKey resolveBedrockKey(String bedrockAccessKeyId,
                                         String bedrockSecretAccessKey,
                                         String sharedAccessKeyId,
                                         String sharedSecretAccessKey) {
        String bid = bedrockAccessKeyId == null ? "" : bedrockAccessKeyId;
        String bsk = bedrockSecretAccessKey == null ? "" : bedrockSecretAccessKey;
        if (!bid.isEmpty() && !bsk.isEmpty()) {
            return new ResolvedKey(bid, bsk);
        }
        String sid = sharedAccessKeyId == null ? "" : sharedAccessKeyId;
        String ssk = sharedSecretAccessKey == null ? "" : sharedSecretAccessKey;
        return new ResolvedKey(sid, ssk);
    }

    @Bean
    public BedrockRuntimeClient bedrockRuntimeClient() {
        System.out.println("=== Configuring AWS Bedrock Client ===");
        System.out.println("Access Key ID present: " + (!accessKeyId.isEmpty()));
        System.out.println("Secret Access Key present: " + (!secretAccessKey.isEmpty()));
        System.out.println("Region: " + bedrockRegion);

        ResolvedKey resolved = resolveBedrockKey(bedrockAccessKeyId, bedrockSecretAccessKey,
                                                 accessKeyId, secretAccessKey);

        AwsCredentialsProvider credentialsProvider;
        if (!resolved.accessKeyId().isEmpty() && !resolved.secretAccessKey().isEmpty()) {
            System.out.println("Using configured AWS credentials for Bedrock"
                    + (bedrockAccessKeyId != null && !bedrockAccessKeyId.isEmpty()
                        ? " (Bedrock-specific)" : " (shared)"));
            credentialsProvider = StaticCredentialsProvider.create(
                AwsBasicCredentials.create(resolved.accessKeyId(), resolved.secretAccessKey())
            );
        } else {
            System.out.println("Attempting to use default AWS credentials provider chain for Bedrock");
            try {
                credentialsProvider = DefaultCredentialsProvider.builder().build();
                credentialsProvider.resolveCredentials();
                System.out.println("Default credentials provider chain successful");
            } catch (Exception e) {
                System.err.println("No AWS credentials available for Bedrock: " + e.getMessage());
                System.err.println("To use Bedrock, set aws.access.key.id and aws.secret.access.key, "
                        + "or aws.bedrock.access.key.id and aws.bedrock.secret.access.key");
                return null;
            }
        }

        try {
            BedrockRuntimeClient client = BedrockRuntimeClient.builder()
                    .region(Region.of(bedrockRegion))
                    .credentialsProvider(credentialsProvider)
                    .build();
            System.out.println("AWS Bedrock client created successfully");
            return client;
        } catch (Exception e) {
            System.err.println("Failed to create Bedrock client: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }
}
