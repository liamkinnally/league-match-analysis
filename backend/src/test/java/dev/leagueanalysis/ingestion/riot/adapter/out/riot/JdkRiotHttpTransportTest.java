package dev.leagueanalysis.ingestion.riot.adapter.out.riot;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

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
