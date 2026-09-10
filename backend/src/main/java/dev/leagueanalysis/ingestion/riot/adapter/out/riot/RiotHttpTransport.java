package dev.leagueanalysis.ingestion.riot.adapter.out.riot;

import java.io.IOException;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.time.Duration;

public interface RiotHttpTransport {
    Response send(HttpRequest request, Duration timeout, int maxResponseBytes)
            throws IOException, InterruptedException;

    record Response(int statusCode, HttpHeaders headers, byte[] body) {
        public Response {
            body = body.clone();
        }

        @Override
        public byte[] body() {
            return body.clone();
        }
    }
}
