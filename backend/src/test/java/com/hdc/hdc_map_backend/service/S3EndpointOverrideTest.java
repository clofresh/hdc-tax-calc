package com.hdc.hdc_map_backend.service;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.*;

class S3EndpointOverrideTest {

    @Test
    void applyEndpointOverride_setsEndpoint_whenUrlProvided() {
        S3ClientBuilder b = S3Client.builder().region(Region.US_EAST_2);
        S3Service.applyEndpointOverride(b, "http://localstack:4566");
        // The builder's endpoint override is package-private; we exercise the
        // method by building and reading the resulting client's serviceClientConfiguration.
        try (S3Client client = b.build()) {
            URI endpoint = client.serviceClientConfiguration().endpointOverride().orElseThrow();
            assertEquals(URI.create("http://localstack:4566"), endpoint);
        }
    }

    @Test
    void applyEndpointOverride_isNoop_whenNull() {
        S3ClientBuilder b = S3Client.builder().region(Region.US_EAST_2);
        S3Service.applyEndpointOverride(b, null);
        try (S3Client client = b.build()) {
            assertTrue(client.serviceClientConfiguration().endpointOverride().isEmpty());
        }
    }

    @Test
    void applyEndpointOverride_isNoop_whenEmpty() {
        S3ClientBuilder b = S3Client.builder().region(Region.US_EAST_2);
        S3Service.applyEndpointOverride(b, "");
        try (S3Client client = b.build()) {
            assertTrue(client.serviceClientConfiguration().endpointOverride().isEmpty());
        }
    }
}
