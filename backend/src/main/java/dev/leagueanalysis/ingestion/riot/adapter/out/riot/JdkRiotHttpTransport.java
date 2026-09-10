package dev.leagueanalysis.ingestion.riot.adapter.out.riot;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public final class JdkRiotHttpTransport implements RiotHttpTransport {
    private final HttpClient client;

    public JdkRiotHttpTransport(Duration connectTimeout) {
        client = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public Response send(HttpRequest request, Duration timeout, int maxResponseBytes)
            throws IOException, InterruptedException {
        var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (var body = response.body()) {
            var accepted = body.readNBytes(maxResponseBytes + 1);
            if (accepted.length > maxResponseBytes) {
                throw new ResponseBodyLimitException();
            }
            return new Response(response.statusCode(), response.headers(), accepted);
        }
    }
}
