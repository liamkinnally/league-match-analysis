package dev.leagueanalysis.privacy;

import org.springframework.context.ApplicationEvent;

/** The process may no longer serve or reuse workers and caches, even if the database recovers. */
public final class PrivacyLeaseLostEvent extends ApplicationEvent {
    public PrivacyLeaseLostEvent(Object source) { super(source); }
}
