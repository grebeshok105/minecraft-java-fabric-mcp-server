package com.chapmanjw.minecraft.fabric.mcp.protocol;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import com.chapmanjw.minecraft.fabric.mcp.transport.StreamBody;

/**
 * Streaming body for the server-to-client SSE channel ({@code GET /mcp}).
 *
 * <p>Per the MCP Streamable HTTP spec, the GET stream stays open for the lifetime of the
 * session so clients can receive server-initiated messages. The v1 surface never sends
 * any, but a stream that closes immediately makes well-behaved clients (e.g. Oh My Pi)
 * treat the connection as lost and enter a reconnect loop. This body writes an opening
 * comment frame so the client's reader unblocks as soon as headers land, then a
 * keep-alive comment every {@code heartbeatMillis} to keep the socket and any
 * intermediaries alive.
 *
 * <p>The stream ends only when the client disconnects (the transport's next write fails)
 * or the server stops (the exchange is closed and the thread interrupted).
 */
public final class SseStreamBody implements StreamBody {

    /** Production keep-alive interval, per the MCP spec's ~15 s guidance. */
    public static final long DEFAULT_HEARTBEAT_MILLIS = 15_000L;

    private static final byte[] OPENING_FRAME = ": open\n\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] PING_FRAME = ": ping\n\n".getBytes(StandardCharsets.UTF_8);

    private final long heartbeatMillis;

    public SseStreamBody() {
        this(DEFAULT_HEARTBEAT_MILLIS);
    }

    public SseStreamBody(long heartbeatMillis) {
        this.heartbeatMillis = heartbeatMillis;
    }

    @Override
    public void writeTo(OutputStream out) throws IOException {
        out.write(OPENING_FRAME);
        out.flush();
        try {
            while (true) {
                Thread.sleep(heartbeatMillis);
                out.write(PING_FRAME);
                out.flush();
            }
        } catch (InterruptedException e) {
            // Transport stopping — the caller closes the exchange.
            Thread.currentThread().interrupt();
        }
    }
}
