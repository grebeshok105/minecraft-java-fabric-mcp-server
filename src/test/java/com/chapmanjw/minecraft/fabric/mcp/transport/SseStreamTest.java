package com.chapmanjw.minecraft.fabric.mcp.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.chapmanjw.minecraft.fabric.mcp.compat.ToolAccess;
import com.chapmanjw.minecraft.fabric.mcp.compat.ToolCategory;
import com.chapmanjw.minecraft.fabric.mcp.compat.ToolDescriptor;
import com.chapmanjw.minecraft.fabric.mcp.config.Config;
import com.chapmanjw.minecraft.fabric.mcp.protocol.McpDispatcher;
import com.chapmanjw.minecraft.fabric.mcp.protocol.McpHttpRoute;
import com.chapmanjw.minecraft.fabric.mcp.protocol.Tool;
import com.chapmanjw.minecraft.fabric.mcp.protocol.ToolContext;
import com.chapmanjw.minecraft.fabric.mcp.protocol.ToolRegistry;
import com.chapmanjw.minecraft.fabric.mcp.protocol.ToolResult;

/**
 * Integration tests for the SSE hold-open contract: {@code GET /mcp} must answer 200
 * {@code text/event-stream} with headers flushed immediately, keep the stream open
 * with periodic keep-alive comments, and never occupy the fixed HTTP pool — while
 * POST semantics stay untouched.
 *
 * <p>Heartbeats are shortened to 300 ms via {@link McpHttpRoute}'s test constructor so
 * hold-open behavior is observable in well under the production ~15 s interval.
 */
class SseStreamTest {

    private static final long HEARTBEAT_MS = 300L;

    private HttpTransport transport;
    private int port;
    private final ExecutorService readerPool =
            Executors.newCachedThreadPool(
                    r -> {
                        Thread t = new Thread(r, "sse-test-reader");
                        t.setDaemon(true);
                        return t;
                    });

    @BeforeEach
    void setUp() throws IOException {
        // Pick a free port up front: SecurityFilter validates the Host header against
        // the configured port, so an ephemeral bind (port 0) would reject every
        // request with 403 ("Host header '127.0.0.1:<n>' not in allowed set").
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        Config config =
                new Config(
                        "127.0.0.1",
                        port,
                        false,
                        null,
                        false,
                        List.of(),
                        15000L,
                        600,
                        16 * 1024 * 1024,
                        1024,
                        256,
                        "info",
                        null,
                        null,
                        false,
                        List.of(),
                        List.of(),
                        "write",
                        false);
        transport = new HttpTransport(config);
    }

    @AfterEach
    void tearDown() {
        if (transport != null) {
            transport.stop();
        }
        readerPool.shutdownNow();
    }

    private static HttpClient newClient() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    }

    /**
     * A full {@code /mcp} route backed by a dispatcher with one trivial tool, so the
     * POST regression test exercises the same surface OMP uses.
     */
    private static McpHttpRoute route(long heartbeatMillis) {
        ObjectMapper mapper = new ObjectMapper();
        ToolRegistry registry = new ToolRegistry();
        registry.register(
                new ToolDescriptor(
                        "echo", "", "", "", List.of(), "",
                        ToolCategory.GAMEPLAY,
                        ToolAccess.WRITE,
                        EchoTool.class),
                new EchoTool());
        ToolContext ctx = new ToolContext(null, null, null, null, mapper, null, null);
        McpDispatcher dispatcher =
                new McpDispatcher(
                        registry,
                        ctx,
                        mapper,
                        new McpDispatcher.ServerInfo("t", "0", null));
        return new McpHttpRoute(dispatcher, mapper, heartbeatMillis);
    }

    @Test
    void getMcpHoldsStreamOpenPastFirstFlush() throws Exception {
        transport.registerRoute("/mcp", route(HEARTBEAT_MS));
        transport.start();
        String url = "http://127.0.0.1:" + port + "/mcp";

        HttpResponse<InputStream> resp =
                newClient()
                        .send(
                                HttpRequest.newBuilder(URI.create(url))
                                        .header("Accept", "text/event-stream")
                                        .GET()
                                        .build(),
                                HttpResponse.BodyHandlers.ofInputStream());

        // Headers must land immediately, before any body is produced.
        assertEquals(200, resp.statusCode());
        assertEquals(
                "text/event-stream; charset=utf-8",
                resp.headers().firstValue("Content-Type").orElse(""));

        BufferedReader reader =
                new BufferedReader(
                        new InputStreamReader(resp.body(), StandardCharsets.UTF_8));
        // First frame arrives with the flushed headers (one-shot responses also do
        // this) — the hold-open contract is that MORE frames keep arriving.
        String opening = readLineWithin(reader, 5_000, "the opening SSE comment frame");
        assertTrue(
                opening != null && opening.startsWith(":"),
                "SSE comment frame expected, got: " + opening);

        // The stream must remain open: keep-alive comments keep arriving. A one-shot
        // response would EOF right after the first frame, and readHeartbeat would
        // return null instead of the heartbeat.
        assertEquals(": ping", readHeartbeat(reader));
        assertEquals(": ping", readHeartbeat(reader));

        // Client disconnect — the server-side stream thread must release the exchange.
        resp.body().close();
    }

    /**
     * The concurrency cap: with a 2-slot transport, two streams hold their slots, the
     * third GET is rejected with 503 and a JSON error body, and closing one stream
     * releases its slot so a follow-up GET streams again. Covers the semaphore
     * acquire/reject/release cycle end to end over real sockets.
     */
    @Test
    void streamCapRejectsExcessWith503AndReleasesSlots() throws Exception {
        transport.stop();
        transport = new HttpTransport(cappedConfig(), 2);
        transport.registerRoute("/mcp", route(HEARTBEAT_MS));
        transport.start();
        String url = "http://127.0.0.1:" + port + "/mcp";

        HttpResponse<InputStream> first = openStream(url);
        HttpResponse<InputStream> second = openStream(url);
        assertEquals(200, first.statusCode());
        assertEquals(200, second.statusCode());

        // Both slots held: the third GET must be refused with 503, not queued.
        HttpResponse<String> third =
                newClient()
                        .send(
                                HttpRequest.newBuilder(URI.create(url))
                                        .header("Accept", "text/event-stream")
                                        .GET()
                                        .build(),
                                HttpResponse.BodyHandlers.ofString());
        assertEquals(503, third.statusCode());
        assertTrue(
                third.body().contains("Too many concurrent event streams"),
                "503 body must name the stream cap, got: " + third.body());

        // Releasing one slot re-admits a stream. The release happens in the stream
        // thread's finally after the disconnect is noticed (at the next heartbeat
        // write), so poll with a deadline instead of asserting instantly.
        first.body().close();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        HttpResponse<InputStream> fourth = null;
        while (System.nanoTime() < deadline) {
            HttpResponse<InputStream> attempt = openStream(url);
            if (attempt.statusCode() == 200) {
                fourth = attempt;
                break;
            }
            assertEquals(503, attempt.statusCode(), "only 503 is acceptable while full");
            Thread.sleep(HEARTBEAT_MS);
        }
        assertTrue(fourth != null, "a slot must free up after the first stream closed");
        fourth.body().close();
        second.body().close();
    }

    /** Same shape as setUp's config (the shared instance was stopped by the test). */
    private Config cappedConfig() {
        return new Config(
                "127.0.0.1",
                port,
                false,
                null,
                false,
                List.of(),
                15000L,
                600,
                16 * 1024 * 1024,
                1024,
                256,
                "info",
                null,
                null,
                false,
                List.of(),
                List.of(),
                "write",
                false);
    }

    private HttpResponse<InputStream> openStream(String url) throws IOException, InterruptedException {
        return newClient()
                .send(
                        HttpRequest.newBuilder(URI.create(url))
                                .header("Accept", "text/event-stream")
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofInputStream());
    }

    @Test
    void concurrentStreamsNeverStarvePostAndDisconnectsAreClean() throws Exception {
        transport.registerRoute("/mcp", route(HEARTBEAT_MS));
        transport.start();

        // The HTTP pool is fixed at max(4, min(32, cpus*2)). Open more concurrent SSE
        // streams than pool threads: if a stream occupied a pool thread, POST requests
        // would queue behind the stuck streams and time out.
        int poolSize = Math.max(4, Math.min(32, Runtime.getRuntime().availableProcessors() * 2));
        List<Socket> streams = new ArrayList<>();
        for (int i = 0; i < poolSize + 1; i++) {
            streams.add(openSseStream(port));
        }

        // POST must still be served promptly with every pool thread free of streams.
        JsonNode init =
                post(
                        port,
                        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}");
        assertTrue(
                init.path("result").has("serverInfo"),
                "initialize must succeed while " + streams.size() + " streams are open: " + init);

        // Client disconnect: close every SSE socket. The server-side stream threads
        // see the broken pipe on their next heartbeat write and release their
        // exchanges instead of leaking sockets.
        for (Socket s : streams) {
            s.close();
        }
        streams.clear();
        // Give the server one heartbeat interval to notice the disconnects.
        Thread.sleep(HEARTBEAT_MS + 100);

        // The server must still be healthy end to end.
        JsonNode again =
                post(
                        port,
                        "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"initialize\",\"params\":{}}");
        assertTrue(
                again.path("result").has("serverInfo"),
                "server must survive stream disconnects: " + again);
    }

    @Test
    void serverStopClosesHeldStreams() throws Exception {
        transport.registerRoute("/mcp", route(HEARTBEAT_MS));
        transport.start();
        Socket s = openSseStream(port);
        s.setSoTimeout(3_000);

        // A held-open stream must not delay shutdown: stop() closes every active
        // exchange so the client observes the stream ending immediately. A stream
        // that survived stop() would leave the socket open (test times out).
        transport.stop();

        BufferedReader in =
                new BufferedReader(
                        new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
        // The client may still have chunked-encoding bytes buffered (the opening
        // frame that landed before stop) — drain until the stream ends. EOF or a
        // connection reset proves the server closed the socket; a timeout proves
        // the stream survived the stop.
        try {
            while (in.readLine() != null) {
                // drain buffered body
            }
        } catch (SocketTimeoutException e) {
            throw new AssertionError("stream still open 3 s after server stop", e);
        } catch (IOException e) {
            // Connection reset — the server closed the socket; stream ended.
        }
        s.close();
    }

    @Test
    void postInitializeAndToolsListAreUnchanged() throws Exception {
        transport.registerRoute("/mcp", route(HEARTBEAT_MS));
        transport.start();

        JsonNode init =
                post(
                        port,
                        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}");
        assertEquals("2.0", init.path("jsonrpc").asText());
        assertEquals(1, init.path("id").asInt());
        JsonNode result = init.path("result");
        assertTrue(result.has("serverInfo"), init.toString());
        assertTrue(result.has("capabilities"), init.toString());
        assertTrue(result.path("capabilities").has("tools"), init.toString());

        JsonNode tools =
                post(
                        port,
                        "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}");
        assertEquals(2, tools.path("id").asInt());
        JsonNode toolsArr = tools.path("result").path("tools");
        assertTrue(toolsArr.isArray(), tools.toString());
        boolean foundEcho = false;
        for (JsonNode t : toolsArr) {
            if ("echo".equals(t.path("name").asText())) {
                foundEcho = true;
            }
        }
        assertTrue(foundEcho, "test-registered 'echo' tool must be listed: " + tools);
    }

    // --- helpers -------------------------------------------------------------

    /**
     * Reads the next keep-alive comment, skipping the empty lines that separate SSE
     * frames ({@code ": ping\n\n"} yields {@code ": ping"} then {@code ""}). Returns
     * {@code null} on EOF — a stream that closed is reported as a failed heartbeat
     * assertion by the caller, not silently looped over.
     */
    private String readHeartbeat(BufferedReader reader) throws Exception {
        String line;
        do {
            line = readLineWithin(reader, 10_000, "keep-alive heartbeat");
        } while ("".equals(line));
        return line;
    }

    /**
     * Reads one line with a hard deadline. Returns {@code null} on EOF (stream closed
     * by the server), fails the test on timeout or read error.
     */
    private String readLineWithin(BufferedReader reader, long timeoutMs, String what)
            throws Exception {
        Future<String> line = readerPool.submit(reader::readLine);
        try {
            return line.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            line.cancel(true);
            throw new AssertionError(
                    "Timed out after " + timeoutMs + " ms waiting for " + what
                            + " — the SSE stream is not producing keep-alives");
        } catch (ExecutionException e) {
            line.cancel(true);
            throw new AssertionError(
                    "Stream read failed while waiting for " + what + ": " + e.getCause());
        }
    }

    /**
     * Opens a raw-socket {@code GET /mcp} SSE stream and reads the status line +
     * headers, leaving the connection open with the body unread. Raw sockets are used
     * instead of {@link HttpClient} because the latter limits HTTP/1.1 connections per
     * host (2 by default), which would serialize the concurrent streams.
     */
    private static Socket openSseStream(int port) throws IOException {
        Socket s = new Socket("127.0.0.1", port);
        s.setSoTimeout(5_000);
        try {
            OutputStream out = s.getOutputStream();
            out.write(
                    ("GET /mcp HTTP/1.1\r\nHost: 127.0.0.1:" + port
                                    + "\r\nAccept: text/event-stream\r\n\r\n")
                            .getBytes(StandardCharsets.UTF_8));
            out.flush();
            BufferedReader in =
                    new BufferedReader(
                            new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            String statusLine = in.readLine();
            assertTrue(
                    statusLine != null && statusLine.startsWith("HTTP/1.1 200"),
                    "SSE stream must open with 200, got: " + statusLine);
            String contentType = null;
            String line;
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                if (line.regionMatches(
                        true, 0, "Content-Type:", 0, "Content-Type:".length())) {
                    contentType = line.substring("Content-Type:".length()).trim();
                }
            }
            assertEquals("text/event-stream; charset=utf-8", contentType);
            return s;
        } catch (IOException | RuntimeException e) {
            s.close();
            throw e;
        }
    }

    /** POSTs a JSON-RPC body and parses the 200 response. Fails fast on non-200. */
    private static JsonNode post(int port, String jsonBody) throws Exception {
        HttpResponse<String> resp =
                newClient()
                        .send(
                                HttpRequest.newBuilder(
                                                URI.create("http://127.0.0.1:" + port + "/mcp"))
                                        .header("Content-Type", "application/json")
                                        .header("Accept", "application/json, text/event-stream")
                                        .timeout(Duration.ofSeconds(5))
                                        .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                                        .build(),
                                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode(), "POST /mcp must return 200: " + resp.body());
        return new ObjectMapper().readTree(resp.body());
    }

    /** Trivial tool used purely so dispatcher.handle() has something to dispatch to. */
    private static final class EchoTool implements Tool {
        @Override
        public JsonNode inputSchema() {
            return new ObjectMapper().createObjectNode().put("type", "object");
        }

        @Override
        public ToolResult execute(JsonNode arguments, ToolContext context) {
            ObjectNode args = arguments == null ? null : (ObjectNode) arguments;
            String text = args == null ? "" : args.path("text").asText("");
            return ToolResult.ofText(text);
        }
    }
}
