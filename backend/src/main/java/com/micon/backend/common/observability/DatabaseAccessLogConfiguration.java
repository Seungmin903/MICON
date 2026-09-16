package com.micon.backend.common.observability;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import net.ttddyy.dsproxy.ExecutionInfo;
import net.ttddyy.dsproxy.QueryInfo;
import net.ttddyy.dsproxy.listener.QueryExecutionListener;
import net.ttddyy.dsproxy.support.ProxyDataSourceBuilder;
import org.slf4j.MDC;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.datasource.DelegatingDataSource;

@Configuration(proxyBeanMethods = false)
public class DatabaseAccessLogConfiguration {
    @Bean
    static BeanPostProcessor databaseAccessLogPostProcessor(Environment environment) {
        boolean enabled = environment.getProperty("observability.db.enabled", Boolean.class, true);
        long slowMs = environment.getProperty("observability.db.slow-query-ms", Long.class, 1000L);
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String name) {
                if (enabled && bean instanceof DataSource dataSource && !(bean instanceof ObservedDataSource)) {
                    return new ObservedDataSource(dataSource, new Listener(slowMs));
                }
                return bean;
            }
        };
    }

    static final class Listener implements QueryExecutionListener {
        private static final Set<String> OPERATIONS = Set.of("SELECT", "INSERT", "UPDATE", "DELETE", "WITH",
                "CREATE", "ALTER", "DROP", "SET", "CALL", "SHOW", "EXPLAIN");
        private final long slowMs;

        Listener(long slowMs) { this.slowMs = slowMs; }

        @Override
        public void beforeQuery(ExecutionInfo info, List<QueryInfo> queries) { }

        @Override
        public void afterQuery(ExecutionInfo info, List<QueryInfo> queries) {
            boolean slow = info.getElapsedTime() >= slowMs;
            boolean quiet = MDC.get(AccessLog.TRACE_ID) == null || "true".equals(MDC.get(AccessLog.PROBE));
            String operations = queries.stream().map(q -> operation(q.getQuery())).distinct().sorted()
                    .collect(Collectors.joining(","));
            String queryId = AccessLog.fingerprint(queries.stream().map(QueryInfo::getQuery)
                    .collect(Collectors.joining("\n")));
            AccessLog.write("db_query", !info.isSuccess() || slow, quiet,
                    "operation", operations, "sql_id", queryId, "query_count", queries.size(),
                    "batch", info.isBatch(), "status", info.isSuccess() ? "ok" : "error",
                    "duration_ms", info.getElapsedTime(), "slow", slow,
                    "error", AccessLog.errorType(info.getThrowable()), "sql_state", AccessLog.sqlState(info.getThrowable()));
        }

        private static String operation(String query) {
            String first = query.stripLeading().split("\\s+", 2)[0].toUpperCase(Locale.ROOT);
            return OPERATIONS.contains(first) ? first : "OTHER";
        }
    }

    static final class ObservedDataSource extends DelegatingDataSource implements AutoCloseable {
        private final DataSource original;

        ObservedDataSource(DataSource original, Listener listener) {
            super(ProxyDataSourceBuilder.create(original).listener(listener).build());
            this.original = original;
        }

        @Override
        public Connection getConnection() throws SQLException {
            long started = System.nanoTime();
            try { return super.getConnection(); }
            catch (SQLException exception) { connectionFailure(started, exception); throw exception; }
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            long started = System.nanoTime();
            try { return super.getConnection(username, password); }
            catch (SQLException exception) { connectionFailure(started, exception); throw exception; }
        }

        private void connectionFailure(long started, SQLException exception) {
            AccessLog.event("db_connection", true, "status", "error", "duration_ms", AccessLog.elapsed(started),
                    "error", AccessLog.errorType(exception), "sql_state", AccessLog.sqlState(exception));
        }

        @Override
        public void close() throws Exception {
            if (original instanceof AutoCloseable closeable) closeable.close();
        }
    }
}
