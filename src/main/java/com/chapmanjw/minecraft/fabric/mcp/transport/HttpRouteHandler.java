package com.chapmanjw.minecraft.fabric.mcp.transport;

/**
 * Handles one HTTP route. Implementations are registered with the
 * {@link HttpTransport} at startup; the transport handles security filtering,
 * size limits, and rate limiting before delegating here.
 *
 * <p>Streaming responses (SSE) are expressed through
 * {@link HttpResponse#stream(int, java.util.Map, StreamBody)}: the transport flushes
 * the status line and headers, then runs the {@link StreamBody} on a dedicated daemon
 * thread until the client disconnects or the server stops.
 */
@FunctionalInterface
public interface HttpRouteHandler {

    HttpResponse handle(HttpRequest request) throws Exception;
}
