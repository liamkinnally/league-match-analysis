package dev.leagueanalysis.ingestion.riot.adapter.out.persistence;

import dev.leagueanalysis.privacy.PrivacyRuntimeGuard;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class JdbcIngestionLeaseGuardTest {
    @Test void lostLifetimeLeaseStopsEveryStorageEntryPointBeforeItTouchesDatabase() {
        var jdbc = mock(JdbcTemplate.class);
        var transactions = mock(PlatformTransactionManager.class);
        var store = new JdbcRiotIngestionStore(jdbc, transactions, new ObjectMapper());
        var guard = mock(PrivacyRuntimeGuard.class);
        doThrow(new IllegalStateException("PRIVACY_RUNTIME_LEASE_LOST")).when(guard).requireHealthy();
        store.setPrivacyRuntimeGuard(guard);

        var entryPoints = Arrays.stream(JdbcRiotIngestionStore.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(method -> !method.getName().equals("setPrivacyRuntimeGuard")).toList();
        assertThat(entryPoints).isNotEmpty();
        for (var method : entryPoints) {
            var arguments = new Object[method.getParameterCount()];
            for (int index = 0; index < arguments.length; index++) {
                if (method.getParameterTypes()[index] == int.class) arguments[index] = 0;
            }
            assertThatThrownBy(() -> method.invoke(store, arguments)).as(method.getName())
                    .isInstanceOfSatisfying(InvocationTargetException.class, exception ->
                            assertThat(exception.getCause()).isInstanceOf(IllegalStateException.class)
                                    .hasMessage("PRIVACY_RUNTIME_LEASE_LOST"));
        }
        verifyNoInteractions(jdbc, transactions);
    }
}
