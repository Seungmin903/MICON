package com.micon.backend.common.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.net.URI;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.StreamingHttpOutputMessage;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;

class ObservedAiRequestFactoryTest {
    private final URI uri = URI.create("http://user:secret@localhost:8788/voice-analysis-jobs/private-job/retry?token=secret");

    @Test
    void logsStatusAndPropagatesTraceWithoutCredentialsQueryOrJobId() throws Exception {
        try (var logs = new AccessLogCapture()) {
            var delegate = mock(ClientHttpRequestFactory.class);
            var request = mock(ClientHttpRequest.class);
            var response = mock(ClientHttpResponse.class);
            var headers = new HttpHeaders();
            when(delegate.createRequest(uri, HttpMethod.POST)).thenReturn(request);
            when(request.getURI()).thenReturn(uri);
            when(request.getMethod()).thenReturn(HttpMethod.POST);
            when(request.getHeaders()).thenReturn(headers);
            when(request.execute()).thenReturn(response);
            when(response.getStatusCode()).thenReturn(HttpStatus.SERVICE_UNAVAILABLE);
            String trace = UUID.randomUUID().toString();
            MDC.put(AccessLog.TRACE_ID, trace);
            assertThat(new ObservedAiRequestFactory(delegate).createRequest(uri, HttpMethod.POST).execute()).isSameAs(response);
            assertThat(headers.getFirst("X-Request-ID")).isEqualTo(trace);
            assertThat(logs.text()).contains("status=503", "host=localhost", "port=8788",
                    "route=/voice-analysis-jobs/{id}/retry", "trace_id=" + trace)
                    .doesNotContain("secret", "private-job", "user:", "token=");
        }
    }

    @Test
    void preservesStreamingAndPropagatesIoFailure() throws Exception {
        try (var logs = new AccessLogCapture()) {
            var delegate = mock(ClientHttpRequestFactory.class);
            var request = mock(ClientHttpRequest.class, withSettings().extraInterfaces(StreamingHttpOutputMessage.class));
            when(delegate.createRequest(uri, HttpMethod.POST)).thenReturn(request);
            when(request.getURI()).thenReturn(uri);
            when(request.getMethod()).thenReturn(HttpMethod.POST);
            when(request.getHeaders()).thenReturn(new HttpHeaders());
            when(request.execute()).thenThrow(new IOException("secret timeout URL"));
            var wrapped = new ObservedAiRequestFactory(delegate).createRequest(uri, HttpMethod.POST);
            assertThat(wrapped).isInstanceOf(StreamingHttpOutputMessage.class);
            StreamingHttpOutputMessage.Body body = output -> output.write(new byte[]{1, 2, 3});
            ((StreamingHttpOutputMessage) wrapped).setBody(body);
            verify((StreamingHttpOutputMessage) request).setBody(body);
            verify(request, never()).getBody();
            assertThatThrownBy(wrapped::execute).isInstanceOf(IOException.class);
            assertThat(logs.text()).contains("status=0", "error=IOException").doesNotContain("secret");
            assertThat(MDC.get(AccessLog.TRACE_ID)).isNull();
        }
    }
}
