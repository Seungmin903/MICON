package com.micon.backend.common.observability;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.util.Set;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.http.HttpMethod;
import org.springframework.http.StreamingHttpOutputMessage;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.support.HttpRequestWrapper;

/** Preserve streaming multipart uploads; a byte[] request interceptor would buffer entire WAVs. */
public final class ObservedAiRequestFactory implements ClientHttpRequestFactory {
    private static final Set<String> PATH_WORDS = Set.of(
            "voice-analysis-jobs", "artist-search-jobs", "recommendations", "song-fit", "retry", "cancel", "status");
    private final ClientHttpRequestFactory delegate;

    public ObservedAiRequestFactory(ClientHttpRequestFactory delegate) {
        this.delegate = delegate;
    }

    @Override
    public ClientHttpRequest createRequest(URI uri, HttpMethod method) throws IOException {
        ClientHttpRequest request = delegate.createRequest(uri, method);
        return request instanceof StreamingHttpOutputMessage streaming
                ? new StreamingRequest(request, streaming) : new ObservedRequest(request);
    }

    static String route(URI uri) {
        var route = new StringBuilder();
        for (String part : uri.getPath().split("/")) {
            if (!part.isEmpty()) route.append('/').append(PATH_WORDS.contains(part) ? part : "{id}");
        }
        return route.isEmpty() ? "/" : route.toString();
    }

    private static class ObservedRequest extends HttpRequestWrapper implements ClientHttpRequest {
        private final ClientHttpRequest request;

        ObservedRequest(ClientHttpRequest request) {
            super(request);
            this.request = request;
        }

        @Override
        public OutputStream getBody() throws IOException { return request.getBody(); }

        @Override
        public ClientHttpResponse execute() throws IOException {
            String previous = MDC.get(AccessLog.TRACE_ID);
            if (previous == null) MDC.put(AccessLog.TRACE_ID, UUID.randomUUID().toString());
            request.getHeaders().set(RequestAccessLogFilter.HEADER, MDC.get(AccessLog.TRACE_ID));
            long started = System.nanoTime();
            int status = 0;
            Throwable failure = null;
            try {
                ClientHttpResponse response = request.execute();
                try {
                    status = response.getStatusCode().value();
                } catch (IOException | RuntimeException exception) {
                    response.close();
                    throw exception;
                }
                return response;
            } catch (IOException | RuntimeException exception) {
                failure = exception;
                throw exception;
            } finally {
                URI uri = getURI();
                AccessLog.event("ai_http", failure != null || status >= 400,
                        "method", getMethod(), "host", uri.getHost(), "port", uri.getPort(),
                        "route", route(uri), "status", status, "duration_ms", AccessLog.elapsed(started),
                        "error", AccessLog.errorType(failure));
                if (previous == null) MDC.remove(AccessLog.TRACE_ID);
            }
        }
    }

    private static final class StreamingRequest extends ObservedRequest implements StreamingHttpOutputMessage {
        private final StreamingHttpOutputMessage streaming;

        StreamingRequest(ClientHttpRequest request, StreamingHttpOutputMessage streaming) {
            super(request);
            this.streaming = streaming;
        }

        @Override
        public void setBody(Body body) { streaming.setBody(body); }
    }
}
