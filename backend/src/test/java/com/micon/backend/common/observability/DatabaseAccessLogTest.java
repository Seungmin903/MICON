package com.micon.backend.common.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import ch.qos.logback.classic.Level;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

class DatabaseAccessLogTest {
    @Configuration(proxyBeanMethods = false)
    static class TestDatabase {
        @Bean
        DataSource dataSource() {
            var pool = new HikariDataSource();
            pool.setJdbcUrl("jdbc:h2:mem:access-" + UUID.randomUUID());
            return pool;
        }
    }

    @Test
    void decoratesPoolAndLogsRealQueriesWithoutSqlBindsOrResults() throws Exception {
        try (var logs = new AccessLogCapture(); var context = new AnnotationConfigApplicationContext()) {
            context.register(TestDatabase.class, DatabaseAccessLogConfiguration.class);
            context.refresh();
            var dataSource = context.getBean(DataSource.class);
            assertThat(dataSource).isInstanceOf(DatabaseAccessLogConfiguration.ObservedDataSource.class);
            assertThat(dataSource.unwrap(HikariDataSource.class)).isNotNull();
            MDC.put(AccessLog.TRACE_ID, UUID.randomUUID().toString());
            var jdbc = new JdbcTemplate(dataSource);
            assertThat(jdbc.queryForObject("SELECT CAST(? AS VARCHAR)", String.class, "secret-lyrics")).isEqualTo("secret-lyrics");
            assertThatThrownBy(() -> jdbc.queryForObject("SELECT missing_private_column", String.class)).isNotNull();
            assertThat(logs.text()).contains("event=db_query", "operation=SELECT", "status=ok", "status=error", "sql_state=")
                    .doesNotContain("secret-lyrics", "CAST", "missing_private_column");
        }
    }

    @Test
    void logsConnectionFailuresWithoutCredentials() throws Exception {
        try (var logs = new AccessLogCapture()) {
            var original = mock(DataSource.class);
            when(original.getConnection()).thenThrow(new SQLException("secret password", "08001"));
            var observed = new DatabaseAccessLogConfiguration.ObservedDataSource(original,
                    new DatabaseAccessLogConfiguration.Listener(1000));
            assertThatThrownBy(observed::getConnection).isInstanceOf(SQLException.class);
            assertThat(logs.text()).contains("event=db_connection", "sql_state=08001").doesNotContain("password", "secret");
        }
    }

    @Test
    void healthAndBackgroundSuccessAreDebugOnly() {
        try (var logs = new AccessLogCapture()) {
            var info = new net.ttddyy.dsproxy.ExecutionInfo();
            info.setSuccess(true);
            info.setElapsedTime(1);
            var listener = new DatabaseAccessLogConfiguration.Listener(1000);
            listener.afterQuery(info, java.util.List.of(new net.ttddyy.dsproxy.QueryInfo("SELECT 1")));
            assertThat(logs.appender.list).allMatch(event -> event.getLevel() == Level.DEBUG);
            info.setElapsedTime(1200);
            listener.afterQuery(info, java.util.List.of(new net.ttddyy.dsproxy.QueryInfo("SELECT 1")));
            assertThat(logs.appender.list.getLast().getLevel()).isEqualTo(Level.WARN);
        }
    }
}
