package dev.leagueanalysis;

import dev.leagueanalysis.privacy.PrivacyLeaseLostEvent;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ApplicationListener;

@SpringBootApplication
public class LeagueAnalysisApplication {
    public static void main(String[] args) {
        if (args.length > 0 && args[0].equals("--player-removal")) {
            System.exit(dev.leagueanalysis.privacy.PlayerRemovalCommand.run(
                    java.util.Arrays.copyOfRange(args, 1, args.length), System.getenv(), System.out, System.err));
            return;
        }
        var application = new SpringApplication(LeagueAnalysisApplication.class);
        var exiting = new AtomicBoolean();
        // Only an executable process may exit; embedded application contexts can observe the event safely.
        application.addListeners((ApplicationListener<PrivacyLeaseLostEvent>) event -> {
            if (!exiting.compareAndSet(false, true)) return;
            Thread.ofPlatform().daemon().name("privacy-exit-deadline").start(() -> {
                try { Thread.sleep(Duration.ofSeconds(30)); }
                catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                Runtime.getRuntime().halt(1);
            });
            Thread.ofPlatform().name("privacy-process-exit").start(() -> System.exit(1));
        });
        application.run(args);
    }
}
