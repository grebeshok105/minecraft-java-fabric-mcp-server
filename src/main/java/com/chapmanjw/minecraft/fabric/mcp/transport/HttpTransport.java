package com.chapmanjw.minecraft.fabric.mcp.transport;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.chapmanjw.minecraft.fabric.mcp.config.Config;

/**
 * Embedded HTTP server hosting the MCP endpoint.
 *
 * <p>Built on JDK's {@code com.sun.net.httpserver.HttpServer} so the mod stays zero
 * external-HTTP-dependency. Performs Host/Origin validation, rate limiting, and
 * optional bearer authentication before delegating to a registered route handler.
 *
 * <p>Lifecycle:
 *
 * <ol>
 *   <li>{@link #HttpTransport(Config)} — capture config, prepare filters.
 *   <li>{@link #registerRoute(String, HttpRouteHandler)} — register one or more routes
 *       (typically called by the protocol layer at module startup).
 *   <li>{@link #start()} — bind the listener, start serving.
 *   <li>{@link #stop()} — drain in-flight requests up to 5 seconds, then close.
 * </ol>
 */
public final class HttpTransport {

    private static final Logger LOGGER = LoggerFactory.getLogger("minecraft_fabric_mcp/transport");

    private final Config config;
    private final SecurityFilter security;
    private final RateLimiter rateLimiter;
    private final Map<String, HttpRouteHandler> routes = new ConcurrentHashMap<>();
    /** Held-open streaming exchanges (SSE), owned by their dedicated threads. */
    private final Set<HttpExchange> activeStreams = ConcurrentHashMap.newKeySet();
    private HttpServer server;

    public HttpTransport(Config config) {
        this(config, DEFAULT_MAX_CONCURRENT_STREAMS);
    }

    /**
     * Test constructor: a small stream cap makes the 503 rejection and slot-release
     * behavior observable without opening hundreds of sockets. Production callers use
     * the single-arg constructor and the default cap.
     */
    HttpTransport(Config config, int maxConcurrentStreams) {
        this.config = config;
        this.security = new SecurityFilter(config);
        this.rateLimiter = new RateLimiter(config.rateLimitRpm());
        this.streamSlots = new java.util.concurrent.Semaphore(maxConcurrentStreams);
        this.maxConcurrentStreams = maxConcurrentStreams;
    }

    // --- registration --------------------------------------------------------

    public void registerRoute(String path, HttpRouteHandler handler) {
        routes.put(path, handler);
    }

    /**
     * Drop rate-limit buckets that have been at full capacity for {@code maxIdleNanos}
     * or longer. Typically called from a tick-aligned task (see
     * {@code McpServerMod.onEndTick}) every ~30 seconds to keep the bucket map from
     * growing unbounded on long-running servers with many distinct clients.
     */
    public void pruneIdleRateLimits(long maxIdleNanos) {
        rateLimiter.pruneIdle(maxIdleNanos);
    }

    // --- lifecycle -----------------------------------------------------------

    public void start() throws IOException {
        InetSocketAddress addr = new InetSocketAddress(config.host(), config.port());
        if (config.tlsEnabled()) {
            HttpsServer https = HttpsServer.create(addr, 32);
            https.setHttpsConfigurator(new HttpsConfigurator(buildSslContext()));
            this.server = https;
        } else {
            this.server = HttpServer.create(addr, 32);
        }

        // Fixed-size pool sized to the rate-limit budget — under sustained load, requests
        // queue at the listener; we never spin up unbounded threads.
        ThreadPoolExecutor pool =
                (ThreadPoolExecutor)
                        Executors.newFixedThreadPool(
                                Math.max(4, Math.min(32, Runtime.getRuntime().availableProcessors() * 2)),
                                namedThreadFactory("mcp-http"));
        server.setExecutor(pool);

        server.createContext("/", this::dispatch);
        server.start();
        LOGGER.info(
                "MCP server listening at {} (host={}, port={}, auth={}, tls={})",
                config.endpointBase(),
                config.host(),
                config.port(),
                config.authRequired(),
                config.tlsEnabled());
    }

    public void stop() {
        if (server != null) {
            // Close held-open SSE streams and interrupt their threads: closing makes the
            // next write fail, interrupting wakes a thread parked in the heartbeat sleep
            // immediately (otherwise cleanup lags up to one heartbeat interval). Daemon
            // threads never block JVM exit either way; server.stop() drains promptly
            // because the exchanges are already closed.
            for (HttpExchange active : activeStreams) {
                try {
                    active.close();
                } catch (RuntimeException e) {
                    LOGGER.debug("Error closing active stream", e);
                }
            }
            activeStreams.clear();
            for (Thread streamThread : streamThreads) {
                streamThread.interrupt();
            }
            streamThreads.clear();
            // Allow up to 5 seconds for in-flight requests to drain; pending sockets are
            // RST'd after that. The MCP SDK times handlers out at the application layer.
            server.stop(5);
            LOGGER.info("MCP server stopped");
            server = null;
        }
    }

    // --- dispatch ------------------------------------------------------------

    private void dispatch(HttpExchange exchange) {
        // One-shot exchanges are closed here; streaming exchanges are handed to a
        // dedicated thread that owns and closes them.
        boolean streamed = false;
        try {
            HttpRequest request = parse(exchange);
            String path = exchange.getRequestURI().getPath();

            // Health endpoint is intentionally not auth-gated, has no rate limit, and
            // does not pass through the security filter (Host check would block
            // upstream liveness probes).
            if ("/healthz".equals(path)) {
                writeResponse(exchange, HttpResponse.json(200, "{\"status\":\"ok\"}"));
                return;
            }

            SecurityFilter.Decision decision = security.evaluate(request);
            if (decision instanceof SecurityFilter.Decision.Rejected r) {
                LOGGER.debug("Rejected {} {} : {}", request.method(), path, r.reason());
                writeResponse(
                        exchange,
                        HttpResponse.json(
                                r.status(),
                                "{\"error\":{\"code\":" + r.status() + ",\"message\":\""
                                        + escapeJson(r.reason()) + "\"}}"));
                return;
            }

            // Per-client rate limiting. Applied AFTER auth so non-authenticated probes
            // can't waste another tenant's budget.
            String key = security.rateLimitKey(request);
            if (!rateLimiter.tryAcquire(key)) {
                exchange.getResponseHeaders().add("Retry-After", "60");
                writeResponse(
                        exchange,
                        HttpResponse.json(
                                429,
                                "{\"error\":{\"code\":429,\"message\":\"Rate limit exceeded\"}}"));
                return;
            }

            HttpRouteHandler handler = routes.get(path);
            if (handler == null) {
                writeResponse(
                        exchange,
                        HttpResponse.json(
                                404,
                                "{\"error\":{\"code\":404,\"message\":\"Not Found: " + escapeJson(path)
                                        + "\"}}"));
                return;
            }

            HttpResponse response;
            try {
                response = handler.handle(request);
            } catch (Exception e) {
                LOGGER.warn("Handler {} threw", path, e);
                response =
                        HttpResponse.json(
                                500,
                                "{\"error\":{\"code\":500,\"message\":\""
                                        + escapeJson("Internal server error: " + e.getMessage())
                                        + "\"}}");
            }
            if (response.streaming()) {
                streamed = streamResponse(exchange, response);
                if (!streamed) {
                    writeResponse(
                            exchange,
                            HttpResponse.json(
                                    503,
                                    "{\"error\":{\"code\":503,\"message\":\"Too many concurrent"
                                            + " event streams\"}}"));
                }
            } else {
                writeResponse(exchange, response);
            }
        } catch (IOException ioe) {
            LOGGER.warn("I/O error handling request", ioe);
        } finally {
            if (!streamed) {
                exchange.close();
            }
        }
    }

    /** Shared factory so stream threads number sequentially (mcp-sse-1, -2, ...). */
    private static final java.util.concurrent.ThreadFactory SSE_THREAD_FACTORY =
            namedThreadFactory("mcp-sse");

    /**
     * Default bound on concurrent held-open streams: each costs one daemon thread, and
     * the rate limiter bounds only the creation RATE — a client that connects and never
     * reads would otherwise accumulate parked threads without limit. 128 comfortably
     * exceeds any legitimate local MCP client population; excess GETs get 503.
     */
    private static final int DEFAULT_MAX_CONCURRENT_STREAMS = 128;

    private final int maxConcurrentStreams;
    private final java.util.concurrent.Semaphore streamSlots;
    private final Set<Thread> streamThreads = ConcurrentHashMap.newKeySet();

    /**
     * Hands the exchange to a dedicated daemon thread that writes the streaming body
     * (SSE). The fixed HTTP pool thread returns as soon as the thread is spawned, so
     * long-lived streams never occupy pool threads — held-open SSE sockets cannot
     * starve POST handling. The stream thread owns the exchange and closes it when the
     * body returns (client disconnect, server stop, or end of stream). Returns false
     * when no stream slot is free (caller answers 503) or the thread cannot start.
     */
    private boolean streamResponse(HttpExchange exchange, HttpResponse response) {
        if (!streamSlots.tryAcquire()) {
            LOGGER.warn("Rejecting SSE stream: {} concurrent streams already open", maxConcurrentStreams);
            return false;
        }
        activeStreams.add(exchange);
        try {
            Thread thread = SSE_THREAD_FACTORY.newThread(() -> writeStream(exchange, response));
            streamThreads.add(thread);
            thread.start();
            return true;
        } catch (RuntimeException | Error e) {
            // start() failure (e.g. native-thread exhaustion) must not leak the slot or
            // leave the exchange registered as an active stream.
            activeStreams.remove(exchange);
            streamSlots.release();
            try {
                exchange.close();
            } catch (RuntimeException closeError) {
                LOGGER.debug("Error closing stream exchange after spawn failure", closeError);
            }
            throw e;
        }
    }

    /** Runs the streaming body on the dedicated stream thread; owns the exchange. */
    private void writeStream(HttpExchange exchange, HttpResponse response) {
        try (exchange) {
            Headers h = exchange.getResponseHeaders();
            for (Map.Entry<String, String> e : response.headers().entrySet()) {
                h.set(e.getKey(), e.getValue());
            }
            // responseLength 0 => chunked transfer encoding: no Content-Length, so the
            // body stays open until we close it.
            exchange.sendResponseHeaders(response.status(), 0);
            try (OutputStream os = exchange.getResponseBody()) {
                response.streamBody().writeTo(os);
            }
        } catch (IOException e) {
            // Client disconnected mid-stream (broken pipe) or the server stopped and
            // closed the exchange.
            LOGGER.debug("SSE stream ended: {}", e.getMessage());
        } finally {
            activeStreams.remove(exchange);
            streamThreads.remove(Thread.currentThread());
            streamSlots.release();
        }
    }

    // --- request / response plumbing -----------------------------------------

    private HttpRequest parse(HttpExchange exchange) throws IOException {
        HttpMethod method = HttpMethod.from(exchange.getRequestMethod());
        String path = exchange.getRequestURI().getPath();
        String query = exchange.getRequestURI().getRawQuery();

        Map<String, List<String>> headers = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : exchange.getRequestHeaders().entrySet()) {
            headers.put(e.getKey(), List.copyOf(e.getValue()));
        }

        byte[] body;
        try (InputStream in = exchange.getRequestBody()) {
            // The transport-level max_body_bytes guard is enforced inside SecurityFilter;
            // here we still cap reads at the same limit + 1 byte so a malicious sender
            // can't OOM the JVM by streaming forever before the size check fires.
            int cap = config.maxBodyBytes() + 1;
            body = in.readNBytes(cap);
        }

        return new HttpRequest(method, path, query, headers, body, exchange.getRemoteAddress());
    }

    private static void writeResponse(HttpExchange exchange, HttpResponse response) throws IOException {
        Headers h = exchange.getResponseHeaders();
        for (Map.Entry<String, String> e : response.headers().entrySet()) {
            h.set(e.getKey(), e.getValue());
        }
        byte[] body = response.body();
        // HTTP requires Content-Length to be the body length for non-chunked responses.
        if (body == null || body.length == 0) {
            exchange.sendResponseHeaders(response.status(), -1);
        } else {
            exchange.sendResponseHeaders(response.status(), body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        }
    }

    // --- TLS -----------------------------------------------------------------

    private SSLContext buildSslContext() throws IOException {
        if (config.tlsCertPath() == null || config.tlsKeyPath() == null) {
            throw new IllegalStateException("TLS paths missing — tlsEnabled() returned true but paths are null");
        }
        try {
            Path certPath = Path.of(config.tlsCertPath());
            Path keyPath = Path.of(config.tlsKeyPath());

            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            X509Certificate cert;
            try (InputStream in = Files.newInputStream(certPath)) {
                cert = (X509Certificate) cf.generateCertificate(in);
            }

            String keyPem = Files.readString(keyPath);
            byte[] keyBytes =
                    Base64.getDecoder()
                            .decode(
                                    keyPem
                                            .replace("-----BEGIN PRIVATE KEY-----", "")
                                            .replace("-----END PRIVATE KEY-----", "")
                                            .replaceAll("\\s", ""));
            KeyFactory kf = KeyFactory.getInstance("RSA");
            java.security.PrivateKey privateKey = kf.generatePrivate(new PKCS8EncodedKeySpec(keyBytes));

            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(null, null);
            ks.setKeyEntry(
                    "mcp",
                    privateKey,
                    new char[0],
                    new java.security.cert.Certificate[] {cert});

            KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
            kmf.init(ks, new char[0]);
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(kmf.getKeyManagers(), null, null);
            return ctx;
        } catch (Exception e) {
            throw new IOException("Failed to build SSL context from TLS files", e);
        }
    }

    // --- helpers -------------------------------------------------------------

    private static String escapeJson(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    private static ThreadFactory namedThreadFactory(String prefix) {
        AtomicInteger counter = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, prefix + "-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

}
