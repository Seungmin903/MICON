package com.micon.backend.common.observability;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

final class AccessLogCapture implements AutoCloseable {
    final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger("micon.access");
    private final Level previous = logger.getLevel();

    AccessLogCapture() {
        logger.setLevel(Level.DEBUG);
        appender.start();
        logger.addAppender(appender);
    }

    String text() {
        return String.join("\n", appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList());
    }

    @Override
    public void close() {
        logger.detachAppender(appender);
        logger.setLevel(previous);
        appender.stop();
        MDC.clear();
    }
}
