package com.chapmanjw.minecraft.fabric.mcp.transport;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable HTTP response built by route handlers.
 *
 * <p>For Server-Sent Events / streaming responses, a handler returns a response
 * created via {@link #stream(int, java.util.Map, StreamBody)}; the transport flushes
 * the status line and headers, then runs the {@link StreamBody} on a dedicated thread
 * until the client disconnects or the server stops. Everything else is one-shot.
 */
public final class HttpResponse {

    private final int status;
    private final Map<String, String> headers;
    private final byte[] body;
    private final StreamBody streamBody;

    private HttpResponse(int status, Map<String, String> headers, byte[] body, StreamBody streamBody) {
        this.status = status;
        this.headers = headers;
        this.body = body;
        this.streamBody = streamBody;
    }

    public int status() {
        return status;
    }

    public Map<String, String> headers() {
        return headers;
    }

    public byte[] body() {
        return body;
    }

    /** True when this response carries a streaming body instead of buffered bytes. */
    public boolean streaming() {
        return streamBody != null;
    }

    /**
     * The streaming body, or {@code null} for one-shot responses. Only meaningful when
     * {@link #streaming()} is true.
     */
    public StreamBody streamBody() {
        return streamBody;
    }

    public static HttpResponse json(int status, String body) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Content-Type", "application/json; charset=utf-8");
        h.put("Cache-Control", "no-store");
        return new HttpResponse(status, h, body.getBytes(StandardCharsets.UTF_8), null);
    }

    public static HttpResponse text(int status, String body) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Content-Type", "text/plain; charset=utf-8");
        h.put("Cache-Control", "no-store");
        return new HttpResponse(status, h, body.getBytes(StandardCharsets.UTF_8), null);
    }

    public static HttpResponse empty(int status) {
        return new HttpResponse(status, new LinkedHashMap<>(), new byte[0], null);
    }

    /**
     * Streaming response: the transport flushes status + headers immediately, then runs
     * {@code body} on a dedicated daemon thread until it returns (client disconnect or
     * server stop). No Content-Length is sent — the stream is chunked. Use for SSE.
     *
     * @param headers response headers (e.g. Content-Type); {@code Cache-Control: no-store}
     *     is added unless already present
     * @param body the streaming body, expected to block
     */
    public static HttpResponse stream(int status, Map<String, String> headers, StreamBody body) {
        Map<String, String> h = new LinkedHashMap<>(headers);
        if (!h.containsKey("Cache-Control")) {
            h.put("Cache-Control", "no-store");
        }
        return new HttpResponse(status, h, new byte[0], body);
    }

    /** Builder for responses that need custom headers. */
    public static Builder builder(int status) {
        return new Builder(status);
    }

    public static final class Builder {
        private final int status;
        private final Map<String, String> headers = new LinkedHashMap<>();
        private byte[] body = new byte[0];

        private Builder(int status) {
            this.status = status;
        }

        public Builder header(String name, String value) {
            headers.put(name, value);
            return this;
        }

        public Builder json(String b) {
            this.body = b.getBytes(StandardCharsets.UTF_8);
            headers.put("Content-Type", "application/json; charset=utf-8");
            return this;
        }

        public Builder text(String b) {
            this.body = b.getBytes(StandardCharsets.UTF_8);
            headers.put("Content-Type", "text/plain; charset=utf-8");
            return this;
        }

        public Builder bytes(byte[] b, String contentType) {
            this.body = b;
            headers.put("Content-Type", contentType);
            return this;
        }

        public HttpResponse build() {
            if (!headers.containsKey("Cache-Control")) {
                headers.put("Cache-Control", "no-store");
            }
            return new HttpResponse(status, headers, body, null);
        }
    }
}
