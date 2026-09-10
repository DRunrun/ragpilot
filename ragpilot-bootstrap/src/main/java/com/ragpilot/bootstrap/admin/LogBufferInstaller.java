package com.ragpilot.bootstrap.admin;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

/**
 * 把 Logback ROOT 输出镜像进 {@link LogRingBuffer}（ADM-10）。
 */
@Configuration
public class LogBufferInstaller {

    @Bean
    LogRingBuffer logRingBuffer() {
        return new LogRingBuffer(500);
    }

    @Bean
    @Order(5)
    ApplicationRunner installLogBufferAppender(LogRingBuffer buffer) {
        return (ApplicationArguments args) -> {
            var factory = LoggerFactory.getILoggerFactory();
            if (!(factory instanceof LoggerContext context)) {
                return;
            }
            RingAppender appender = new RingAppender(buffer);
            appender.setContext(context);
            appender.setName("RAGPILOT_RING");
            appender.start();
            Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);
            root.addAppender(appender);
        };
    }

    private static final class RingAppender extends AppenderBase<ILoggingEvent> {
        private final LogRingBuffer buffer;

        RingAppender(LogRingBuffer buffer) {
            this.buffer = buffer;
        }

        @Override
        protected void append(ILoggingEvent eventObject) {
            if (eventObject == null) {
                return;
            }
            buffer.append(eventObject.getLevel() + " [" + eventObject.getLoggerName() + "] "
                    + eventObject.getFormattedMessage());
        }
    }
}
