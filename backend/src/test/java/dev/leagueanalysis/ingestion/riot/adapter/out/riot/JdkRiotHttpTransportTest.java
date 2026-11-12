package dev.leagueanalysis.ingestion.riot.adapter.out.riot;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdkRiotHttpTransportTest {
    private static final int LIMIT = 16 * 1024 * 1024;

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void doesNotFollowRedirects() throws Exception {
        var targetHits = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "/target");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/target", exchange -> {
            targetHits.incrementAndGet();
            exchange.sendResponseHeaders(200, 0);
            exchange.close();
        });
        server.start();

        var response = transport().send(request("/redirect"), Duration.ofSeconds(2), LIMIT);

        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(targetHits).hasValue(0);
    }

    @Test
    void acceptsExactlySixteenMiBAndRejectsTheNextByte() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/exact", exchange -> writeBytes(exchange, LIMIT));
        server.createContext("/over", exchange -> writeBytes(exchange, LIMIT + 1));
        server.start();

        var exact = transport().send(request("/exact"), Duration.ofSeconds(5), LIMIT);

        assertThat(exact.body()).hasSize(LIMIT);
        assertThatThrownBy(() -> transport().send(request("/over"), Duration.ofSeconds(5), LIMIT))
                .isInstanceOf(ResponseBodyLimitException.class);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void stalledResponseReleasesWorkerForNextJobAndClosesExchange(boolean sendPartialBody) throws Exception {
        var stalled = new StalledResponseServer(sendPartialBody ? "x" : null);
        var worker = Executors.newSingleThreadExecutor();
        try {
            var failure = worker.submit(() -> sendFailure(stalled.request(), Duration.ofMillis(400), LIMIT));
            stalled.requestReceived.get(3, TimeUnit.SECONDS);
            var nextJob = worker.submit(() -> "next job completed");

            assertThat(nextJob.get(3, TimeUnit.SECONDS)).isEqualTo("next job completed");
            assertThat(failure.get(1, TimeUnit.SECONDS)).isInstanceOf(HttpTimeoutException.class);
            stalled.disconnected.get(3, TimeUnit.SECONDS);
        } finally {
            stalled.close();
            worker.shutdownNow();
            worker.awaitTermination(3, TimeUnit.SECONDS);
        }
    }

    @Test
    void interruptionCancelsStalledResponseAndIsPropagated() throws Exception {
        var stalled = new StalledResponseServer("x");
        var failure = new CompletableFuture<Throwable>();
        var worker = Thread.ofPlatform().start(() ->
                failure.complete(sendFailure(stalled.request(), Duration.ofSeconds(30), LIMIT)));
        try {
            stalled.requestReceived.get(3, TimeUnit.SECONDS);

            worker.interrupt();

            assertThat(failure.get(3, TimeUnit.SECONDS)).isInstanceOf(InterruptedException.class);
            stalled.disconnected.get(3, TimeUnit.SECONDS);
        } finally {
            stalled.close();
            worker.interrupt();
            worker.join(3000);
        }
    }

    @Test
    void exceedingByteLimitCancelsResponseBeforeEndOfBody() throws Exception {
        var stalled = new StalledResponseServer("12345");
        var worker = Executors.newSingleThreadExecutor();
        try {
            var failure = worker.submit(() -> sendFailure(stalled.request(), Duration.ofSeconds(10), 4));

            assertThat(failure.get(3, TimeUnit.SECONDS)).isInstanceOf(ResponseBodyLimitException.class);
            stalled.disconnected.get(3, TimeUnit.SECONDS);
        } finally {
            stalled.close();
            worker.shutdownNow();
            worker.awaitTermination(3, TimeUnit.SECONDS);
        }
    }

    private Throwable sendFailure(HttpRequest request, Duration timeout, int limit) {
        try {
            transport().send(request, timeout, limit);
            return new AssertionError("The incomplete response unexpectedly completed");
        } catch (IOException | InterruptedException failure) {
            return failure;
        }
    }

    /** A real HTTP/1.1 peer that never completes its headers or declared body. */
    private static final class StalledResponseServer implements AutoCloseable {
        private final ServerSocket listener = new ServerSocket();
        private final CompletableFuture<Void> requestReceived = new CompletableFuture<>();
        private final CompletableFuture<Void> disconnected = new CompletableFuture<>();
        private final Thread serverThread;
        private volatile Socket socket;

        private StalledResponseServer(String partialBody) throws IOException {
            listener.bind(new InetSocketAddress("127.0.0.1", 0));
            serverThread = Thread.ofPlatform().daemon().start(() -> {
                try (var accepted = listener.accept()) {
                    socket = accepted;
                    accepted.setSoTimeout(10000);
                    var input = new BufferedReader(new InputStreamReader(
                            accepted.getInputStream(), StandardCharsets.US_ASCII));
                    String line;
                    do {
                        line = input.readLine();
                        if (line == null) throw new IOException("Request ended before its headers");
                    } while (!line.isEmpty());
                    if (partialBody != null) {
                        var response = "HTTP/1.1 200 OK\r\nContent-Length: 100\r\nConnection: close\r\n\r\n" + partialBody;
                        accepted.getOutputStream().write(response.getBytes(StandardCharsets.US_ASCII));
                        accepted.getOutputStream().flush();
                    }
                    requestReceived.complete(null);
                    try {
                        if (input.read() != -1) throw new IOException("Unexpected bytes after the request");
                    } catch (SocketException cancelled) {
                        // A reset, like EOF, proves cancellation closed this exchange.
                    }
                    disconnected.complete(null);
                } catch (Throwable failure) {
                    requestReceived.completeExceptionally(failure);
                    disconnected.completeExceptionally(failure);
                }
            });
        }

        private HttpRequest request() {
            return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + listener.getLocalPort() + "/stalled"))
                    .version(java.net.http.HttpClient.Version.HTTP_1_1).GET().build();
        }

        @Override public void close() throws Exception {
            listener.close();
            var accepted = socket;
            if (accepted != null) accepted.close();
            serverThread.join(3000);
        }
    }

    private JdkRiotHttpTransport transport() {
        return new JdkRiotHttpTransport(Duration.ofSeconds(2));
    }

    private HttpRequest request(String path) {
        return HttpRequest.newBuilder(URI.create(
                        "http://127.0.0.1:" + server.getAddress().getPort() + path))
                .GET()
                .build();
    }

    private void writeBytes(com.sun.net.httpserver.HttpExchange exchange, int count) throws IOException {
        exchange.sendResponseHeaders(200, count);
        try (var output = exchange.getResponseBody()) {
            var chunk = new byte[8192];
            var remaining = count;
            while (remaining > 0) {
                var written = Math.min(remaining, chunk.length);
                output.write(chunk, 0, written);
                remaining -= written;
            }
        }
    }
}
