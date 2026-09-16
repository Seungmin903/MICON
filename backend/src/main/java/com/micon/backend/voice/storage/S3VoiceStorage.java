package com.micon.backend.voice.storage;

import com.micon.backend.common.observability.S3AccessLog;

import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.io.InputStream;
import java.net.URI;
import java.time.Duration;

@Component
public class S3VoiceStorage implements VoiceStorage {
    private final S3Client s3;
    private final S3Presigner presigner;
    private final VoiceStorageProperties properties;

    public S3VoiceStorage(VoiceStorageProperties properties) {
        this.properties = properties;
        this.s3 = S3Client.builder().region(Region.of(properties.region())).build();
        this.presigner = S3Presigner.builder().region(Region.of(properties.region())).build();
    }

    @Override
    public URI createDownloadUrl(String objectKey, Duration validity) {
        GetObjectRequest objectRequest = GetObjectRequest.builder()
                .bucket(properties.bucket()).key(objectKey).build();
        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(validity).getObjectRequest(objectRequest).build();
        return S3AccessLog.call("presign_get", properties.bucket(), objectKey,
                () -> URI.create(presigner.presignGetObject(presignRequest).url().toString()), ignored -> 0);
    }

    @Override
    public StoredObject download(String objectKey) {
        ResponseBytes<GetObjectResponse> object = S3AccessLog.call("get", properties.bucket(), objectKey,
                () -> s3.getObjectAsBytes(GetObjectRequest.builder()
                        .bucket(properties.bucket()).key(objectKey).build()),
                result -> result.response().sdkHttpResponse().statusCode());
        return new StoredObject(object.asByteArray(), object.response().contentType());
    }

    @Override
    public void upload(String objectKey, InputStream content, long contentLength, String contentType) {
        S3AccessLog.call("put", properties.bucket(), objectKey,
                () -> s3.putObject(PutObjectRequest.builder().bucket(properties.bucket()).key(objectKey)
                        .contentType(contentType).build(), RequestBody.fromInputStream(content, contentLength)),
                result -> result.sdkHttpResponse().statusCode());
    }

    @Override
    public void delete(String objectKey) {
        S3AccessLog.call("delete", properties.bucket(), objectKey,
                () -> s3.deleteObject(DeleteObjectRequest.builder().bucket(properties.bucket()).key(objectKey).build()),
                result -> result.sdkHttpResponse().statusCode());
    }
}
