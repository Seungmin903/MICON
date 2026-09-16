package com.micon.backend.common.observability;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/** Metadata only: never pass bodies, SQL, bind values, headers, URLs or exception messages. */
public final class AccessLog {
    public static final String TRACE_ID = "traceId";
    public static final String PROBE = "accessProbe";
    private static final Logger LOG = LoggerFactory.getLogger("micon.access");

    private AccessLog() { }

    public static long elapsed(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }

    public static void event(String event, boolean failed, Object... fields) {
        write(event, failed, false, fields);
    }

    public static void write(String event, boolean failed, boolean quiet, Object... fields) {
        var line = new StringBuilder("event=").append(token(event))
                .append(" trace_id=").append(token(MDC.get(TRACE_ID)));
        for (int i = 0; i + 1 < fields.length; i += 2) {
            line.append(' ').append(token(fields[i])).append('=').append(token(fields[i + 1]));
        }
        if (failed) LOG.warn("{}", line);
        else if (quiet) LOG.debug("{}", line);
        else LOG.info("{}", line);
    }

    public static String token(Object value) {
        if (value == null) return "-";
        String text = String.valueOf(value);
        return text.substring(0, Math.min(text.length(), 160)).replaceAll("[^a-zA-Z0-9_./:{}@+,-]", "_");
    }

    public static String fingerprint(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8))).substring(0, 16);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    public static String errorType(Throwable failure) {
        return failure == null ? "-" : failure.getClass().getSimpleName();
    }

    public static String sqlState(Throwable failure) {
        for (int i = 0; failure != null && i < 12; i++, failure = failure.getCause()) {
            if (failure instanceof SQLException sql) return token(sql.getSQLState());
        }
        return "-";
    }
}
