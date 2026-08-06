package com.chapmanjw.minecraft.fabric.mcp.transport;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Streaming response body, written by the transport on a dedicated thread.
 *
 * <p>Unlike a one-shot {@link HttpResponse}, a streamed body is never buffered: the
 * transport flushes the status line and headers first, then invokes {@link #writeTo}
 * and keeps the exchange open until the body returns. Implementations are expected to
 * block — typically looping on a keep-alive interval — and to return only when the
 * client disconnects (a write fails) or the server stops (the exchange is closed /
 * the thread interrupted).
 */
@FunctionalInterface
public interface StreamBody {

    /**
     * Writes the streaming body. May block indefinitely; returning closes the stream.
     *
     * @param out the exchange's response body stream (chunked transfer encoding)
     */
    void writeTo(OutputStream out) throws IOException;
}
