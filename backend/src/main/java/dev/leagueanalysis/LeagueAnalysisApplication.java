package dev.leagueanalysis;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class LeagueAnalysisApplication {
    public static void main(String[] args) {
        if (args.length > 0 && args[0].equals("--player-removal")) {
            System.exit(dev.leagueanalysis.privacy.PlayerRemovalCommand.run(
                    java.util.Arrays.copyOfRange(args, 1, args.length), System.getenv(), System.out, System.err));
            return;
        }
        SpringApplication.run(LeagueAnalysisApplication.class, args);
    }
}
