package com.micon.backend.common.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.services.s3.model.S3Exception;

class S3AccessLogTest {
    @Test
    void signingIsNotReportedAsDownloadAndNeverLeaksUrlOrObjectKey() {
        try (var logs = new AccessLogCapture()) {
            var url = URI.create("https://bucket.s3.test/private?X-Amz-Signature=secret");
            assertThat(S3AccessLog.call("presign_get", "bucket", "voice/private-user.wav", () -> url, ignored -> 0)).isEqualTo(url);
            assertThat(logs.text()).contains("operation=presign_get", "http_status=0", "object_id=")
                    .doesNotContain("private-user", "Signature", "secret", "https://");
        }
    }

    @Test
    void reportsAccessDeniedAndRethrowsOriginalException() {
        try (var logs = new AccessLogCapture()) {
            var failure = S3Exception.builder().statusCode(403).requestId("aws-request-1")
                    .awsErrorDetails(AwsErrorDetails.builder().errorCode("AccessDenied").build())
                    .message("secret object and authorization").build();
            assertThatThrownBy(() -> S3AccessLog.call("get", "bucket", "private-key",
                    () -> { throw failure; }, ignored -> 200)).isSameAs(failure);
            assertThat(logs.text()).contains("operation=get", "status=error", "http_status=403", "aws_code=AccessDenied")
                    .doesNotContain("secret", "authorization", "private-key");
        }
    }
}
