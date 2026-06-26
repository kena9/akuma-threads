package com.akumathreads.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;

import java.lang.reflect.Method;

/**
 * Configures async exception handling (P2-4 fix).
 *
 * <p>Without this, exceptions thrown inside {@code @Async} methods are silently
 * swallowed by the default {@link java.util.concurrent.ThreadPoolExecutor} —
 * the calling thread never sees them and no log entry is written.
 *
 * <p>By implementing {@link AsyncConfigurer#getAsyncUncaughtExceptionHandler()},
 * we install a handler that logs every uncaught async exception at ERROR level
 * with the class name, method name, and full stack trace, making email/notification
 * failures visible in the server logs.
 *
 * <p>{@code @EnableAsync} is declared on {@code AkumaThreadsApplication}.
 */
@Configuration
@Slf4j
public class AsyncConfig implements AsyncConfigurer {

    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return new LoggingAsyncExceptionHandler();
    }

    private static class LoggingAsyncExceptionHandler implements AsyncUncaughtExceptionHandler {
        @Override
        public void handleUncaughtException(Throwable ex, Method method, Object... params) {
            log.error("[Async] Uncaught exception in {}.{}(): {}",
                    method.getDeclaringClass().getSimpleName(),
                    method.getName(),
                    ex.getMessage(),
                    ex);
        }
    }
}
