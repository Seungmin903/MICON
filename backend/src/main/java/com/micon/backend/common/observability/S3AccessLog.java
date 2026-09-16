package com.micon.backend.common.observability;

import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import software.amazon.awssdk.awscore.exception.AwsServiceException;

public final class S3AccessLog {
    private S3AccessLog() { }

    public static <T> T call(String operation, String bucket, String key, Supplier<T> action,
                             ToIntFunction<T> responseStatus) {
        long started = System.nanoTime();
        Throwable failure = null;
        int status = 0;
        String awsCode = "-";
        String awsRequestId = "-";
        try {
            T result = action.get();
            status = responseStatus.applyAsInt(result);
            return result;
        } catch (RuntimeException exception) {
            failure = exception;
            if (exception instanceof AwsServiceException aws) {
                status = aws.statusCode();
                awsRequestId = aws.requestId();
                if (aws.awsErrorDetails() != null) awsCode = aws.awsErrorDetails().errorCode();
            }
            throw exception;
        } finally {
            // Object keys can contain user identifiers. Keep only a stable fingerprint.
            AccessLog.event("s3_access", failure != null, "operation", operation, "bucket", bucket,
                    "object_id", AccessLog.fingerprint(key), "status", failure == null ? "ok" : "error",
                    "http_status", status, "duration_ms", AccessLog.elapsed(started),
                    "error", AccessLog.errorType(failure), "aws_code", awsCode, "aws_request_id", awsRequestId);
        }
    }
}
