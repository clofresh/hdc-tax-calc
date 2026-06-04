package com.hdc.hdc_map_backend.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.util.UUID;

@Service
public class S3Service {

    @Value("${aws.s3.bucket-name}")
    private String bucketName;

    @Value("${aws.region}")
    private String region;

    @Value("${aws.access.key.id:}")
    private String accessKeyId;

    @Value("${aws.secret.access.key:}")
    private String secretAccessKey;

    @Value("${aws.s3.endpoint:}")
    private String s3Endpoint;

    private S3Client s3Client;

    static void applyEndpointOverride(S3ClientBuilder builder, String endpointUrl) {
        if (endpointUrl != null && !endpointUrl.isEmpty()) {
            builder.endpointOverride(java.net.URI.create(endpointUrl))
                   .forcePathStyle(true);
        }
    }

    @PostConstruct
    public void init() {
        if (accessKeyId != null && !accessKeyId.isEmpty()
                && secretAccessKey != null && !secretAccessKey.isEmpty()) {
            AwsBasicCredentials credentials = AwsBasicCredentials.create(accessKeyId, secretAccessKey);
            S3ClientBuilder builder = S3Client.builder()
                    .region(Region.of(region))
                    .credentialsProvider(StaticCredentialsProvider.create(credentials));
            applyEndpointOverride(builder, s3Endpoint);
            this.s3Client = builder.build();
        } else {
            S3ClientBuilder builder = S3Client.builder().region(Region.of(region));
            applyEndpointOverride(builder, s3Endpoint);
            this.s3Client = builder.build();
        }
    }

    /**
     * Upload profile image to S3
     * 
     * @param file   The image file to upload
     * @param userId The user ID for organizing files
     * @return The public URL of the uploaded image
     */
    public String uploadProfileImage(MultipartFile file, Long userId) throws IOException {
        
        if (file.isEmpty()) {
            throw new IllegalArgumentException("File is empty");
        }

        
        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new IllegalArgumentException("File must be an image");
        }

        
        if (file.getSize() > 20 * 1024 * 1024) {
            throw new IllegalArgumentException("File size must not exceed 20MB");
        }

        
        String originalFilename = file.getOriginalFilename();
        String extension = originalFilename != null && originalFilename.contains(".")
                ? originalFilename.substring(originalFilename.lastIndexOf("."))
                : ".jpg";
        String fileName = "profile-images/" + userId + "/" + UUID.randomUUID() + extension;

        
        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(fileName)
                .contentType(contentType)
                .build();

        s3Client.putObject(putObjectRequest, RequestBody.fromInputStream(file.getInputStream(), file.getSize()));


        return String.format("https://%s.s3.%s.amazonaws.com/%s", bucketName, region, fileName);
    }

    /**
     * Delete profile image from S3
     *
     * @param imageUrl The full URL of the image to delete
     */
    public void deleteProfileImage(String imageUrl) {
        if (imageUrl == null || imageUrl.isEmpty()) {
            return;
        }

        try {
            
            String key = imageUrl.substring(imageUrl.indexOf("profile-images/"));

            DeleteObjectRequest deleteObjectRequest = DeleteObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .build();

            s3Client.deleteObject(deleteObjectRequest);
        } catch (Exception e) {
            
            System.err.println("Failed to delete profile image: " + e.getMessage());
        }
    }

    /**
     * Upload banner image to S3
     *
     * @param file   The image file to upload
     * @param userId The user ID for organizing files
     * @return The public URL of the uploaded image
     */
    public String uploadBannerImage(MultipartFile file, Long userId) throws IOException {
        
        if (file.isEmpty()) {
            throw new IllegalArgumentException("File is empty");
        }

        
        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new IllegalArgumentException("File must be an image");
        }

        
        if (file.getSize() > 20 * 1024 * 1024) {
            throw new IllegalArgumentException("File size must not exceed 20MB");
        }

        
        String originalFilename = file.getOriginalFilename();
        String extension = originalFilename != null && originalFilename.contains(".")
                ? originalFilename.substring(originalFilename.lastIndexOf("."))
                : ".jpg";
        String fileName = "banner-images/" + userId + "/" + UUID.randomUUID() + extension;

        
        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(fileName)
                .contentType(contentType)
                .build();

        s3Client.putObject(putObjectRequest, RequestBody.fromInputStream(file.getInputStream(), file.getSize()));


        return String.format("https://%s.s3.%s.amazonaws.com/%s", bucketName, region, fileName);
    }

    /**
     * Delete banner image from S3
     *
     * @param imageUrl The full URL of the image to delete
     */
    public void deleteBannerImage(String imageUrl) {
        if (imageUrl == null || imageUrl.isEmpty()) {
            return;
        }

        try {
            
            String key = imageUrl.substring(imageUrl.indexOf("banner-images/"));

            DeleteObjectRequest deleteObjectRequest = DeleteObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .build();

            s3Client.deleteObject(deleteObjectRequest);
        } catch (Exception e) {
            
            System.err.println("Failed to delete banner image: " + e.getMessage());
        }
    }
}
