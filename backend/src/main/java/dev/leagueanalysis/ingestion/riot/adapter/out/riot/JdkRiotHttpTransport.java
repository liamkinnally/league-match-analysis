package dev.leagueanalysis.ingestion.riot.adapter.out.riot;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
        long timeoutNanos = timeout.toNanos();
        long started = System.nanoTime();
        var pending = client.sendAsync(request, ignored -> new BoundedBodySubscriber(maxResponseBytes));
        try {
            // The response future completes only when the bounded subscriber has
            // received the whole body. Headers do not end this deadline.
            long remaining = timeoutNanos - (System.nanoTime() - started);
            var response = pending.get(Math.max(0, remaining), TimeUnit.NANOSECONDS);
            return new Response(response.statusCode(), response.headers(), response.body());
        } catch (TimeoutException timeoutFailure) {
            pending.cancel(true);
            throw new HttpTimeoutException("Riot response did not complete within the request deadline");
        } catch (InterruptedException interrupted) {
            pending.cancel(true);
            throw interrupted;
        } catch (ExecutionException failure) {
            var cause = failure.getCause();
            if (cause instanceof IOException ioFailure) throw ioFailure;
            if (cause instanceof RuntimeException runtimeFailure) throw runtimeFailure;
            throw new IOException("Riot request failed", cause);
        }
    }

    private static final class BoundedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final int limit;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private Flow.Subscription subscription;

        private BoundedBodySubscriber(int limit) {
            this.limit = limit;
        }

        @Override public CompletionStage<byte[]> getBody() {
            return body;
        }

        @Override public void onSubscribe(Flow.Subscription incoming) {
            if (subscription != null) {
                incoming.cancel();
                return;
            }
            subscription = incoming;
            subscription.request(1);
        }

        @Override public void onNext(List<ByteBuffer> buffers) {
            if (body.isDone()) return;
            for (var buffer : buffers) {
                if (buffer.remaining() > limit - bytes.size()) {
                    body.completeExceptionally(new ResponseBodyLimitException());
                    subscription.cancel();
                    return;
                }
                var chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }

        @Override public void onError(Throwable failure) {
            body.completeExceptionally(failure);
        }

        @Override public void onComplete() {
            body.complete(bytes.toByteArray());
        }
    }
}
