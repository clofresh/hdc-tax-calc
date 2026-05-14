package com.hdc.hdc_map_backend.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BedrockCredentialResolverTest {

    @Test
    void usesBedrockSpecificKey_whenBothBedrockValuesPresent() {
        AWSConfig.ResolvedKey r = AWSConfig.resolveBedrockKey("bedrock-id", "bedrock-secret",
                                                              "shared-id", "shared-secret");
        assertEquals("bedrock-id", r.accessKeyId());
        assertEquals("bedrock-secret", r.secretAccessKey());
    }

    @Test
    void fallsBackToShared_whenBedrockValuesEmpty() {
        AWSConfig.ResolvedKey r = AWSConfig.resolveBedrockKey("", "", "shared-id", "shared-secret");
        assertEquals("shared-id", r.accessKeyId());
        assertEquals("shared-secret", r.secretAccessKey());
    }

    @Test
    void fallsBackToShared_whenBedrockValuesNull() {
        AWSConfig.ResolvedKey r = AWSConfig.resolveBedrockKey(null, null, "shared-id", "shared-secret");
        assertEquals("shared-id", r.accessKeyId());
        assertEquals("shared-secret", r.secretAccessKey());
    }

    @Test
    void fallsBackToShared_whenOnlyBedrockAccessKeyMissing() {
        AWSConfig.ResolvedKey r = AWSConfig.resolveBedrockKey("", "bedrock-secret",
                                                              "shared-id", "shared-secret");
        // Partial Bedrock creds → fall back to shared (don't mix half from each)
        assertEquals("shared-id", r.accessKeyId());
        assertEquals("shared-secret", r.secretAccessKey());
    }

    @Test
    void bothEmpty_returnsEmpty() {
        AWSConfig.ResolvedKey r = AWSConfig.resolveBedrockKey("", "", "", "");
        assertEquals("", r.accessKeyId());
        assertEquals("", r.secretAccessKey());
    }
}
