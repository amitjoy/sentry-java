package io.sentry.connection;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.sentry.SentryClient;
import io.sentry.environment.SentryEnvironment;
import io.sentry.event.Event;

/**
 * Asynchronous usage of a connection.
 * <p>
 * Instead of synchronously sending each event to a connection, use a ThreadPool to establish the connection
 * and submit the event.
 */
public class AsyncConnection implements Connection {
    private static final Logger logger = LoggerFactory.getLogger(AsyncConnection.class);
    // CHECKSTYLE.OFF: ConstantName
    private static final Logger lockdownLogger = LoggerFactory.getLogger(SentryClient.class.getName() + ".lockdown");
    // CHECKSTYLE.ON: ConstantName
    /**
     * Timeout of the {@link #executorService}, in milliseconds.
     */
    private final long shutdownTimeout;
    /**
     * Connection used to actually send the events.
     */
    private final Connection actualConnection;
    /**
     * Executor service in charge of running the connection in separate threads.
     */
    private final ExecutorService executorService;
    /**
     * Shutdown hook used to stop the async connection properly when the JVM quits.
     */
    private final ShutDownHook shutDownHook = new ShutDownHook();
    /**
     * Boolean that represents if graceful shutdown is enabled.
     */
    private boolean gracefulShutdown;
    /**
     * Boolean used to check whether the connection is still open or not.
     */
    private volatile boolean closed;

    /**
     * Creates a connection which will rely on an executor to send events.
     * <p>
     * Will propagate the {@link #close()} operation.
     *
     * @param actualConnection connection used to send the events.
     * @param executorService executorService used to process events, if null, the executorService will automatically
     *            be set to {@code Executors.newSingleThreadExecutor()}
     * @param gracefulShutdown Indicates whether or not the shutdown operation should be managed by a ShutdownHook.
     * @param shutdownTimeout timeout for graceful shutdown of the executor, in milliseconds.
     */
    public AsyncConnection(final Connection actualConnection, final ExecutorService executorService,
            final boolean gracefulShutdown, final long shutdownTimeout) {
        this.actualConnection = actualConnection;
        if (executorService == null) {
            this.executorService = Executors.newSingleThreadExecutor();
        } else {
            this.executorService = executorService;
        }
        if (gracefulShutdown) {
            this.gracefulShutdown = gracefulShutdown;
        }
        this.shutdownTimeout = shutdownTimeout;
    }

    /**
     * {@inheritDoc}
     * <p>
     * The event will be added to a queue and will be handled by a separate {@code Thread} later on.
     */
    @Override
    public void send(final Event event) {
        if (!closed) {
            executorService.execute(new EventSubmitter(event));
        }
    }

    @Override
    public void addEventSendCallback(final EventSendCallback eventSendCallback) {
        actualConnection.addEventSendCallback(eventSendCallback);
    }

    /**
     * {@inheritDoc}.
     * <p>
     * Closing the {@link AsyncConnection} will attempt a graceful shutdown of the {@link #executorService} with a
     * timeout of {@link #shutdownTimeout}, allowing the current events to be submitted while new events will
     * be rejected.<br>
     * If the shutdown times out, the {@code executorService} will be forced to shutdown.
     */
    @Override
    public void close() throws IOException {
        if (gracefulShutdown) {
            shutDownHook.enabled = false;
        }

        doClose();
    }

    /**
     * Close the connection whether it's from the shutdown hook or not.
     *
     * @see #close()
     */
    @SuppressWarnings("checkstyle:magicnumber")
    private void doClose() throws IOException {
        logger.debug("Gracefully shutting down Sentry async threads.");
        closed = true;
        executorService.shutdown();
        try {
            if (shutdownTimeout == -1L) {
                // Block until the executor terminates, but log periodically.
                final long waitBetweenLoggingMs = 5000L;
                while (true) {
                    if (executorService.awaitTermination(waitBetweenLoggingMs, TimeUnit.MILLISECONDS)) {
                        break;
                    }
                    logger.debug("Still waiting on async executor to terminate.");
                }
            } else if (!executorService.awaitTermination(shutdownTimeout, TimeUnit.MILLISECONDS)) {
                logger.warn("Graceful shutdown took too much time, forcing the shutdown.");
                final List<Runnable> tasks = executorService.shutdownNow();
                logger.warn("{} tasks failed to execute before shutdown.", tasks.size());
            }
            logger.debug("Shutdown finished.");
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("Graceful shutdown interrupted, forcing the shutdown.");
            final List<Runnable> tasks = executorService.shutdownNow();
            logger.warn("{} tasks failed to execute before shutdown.", tasks.size());
        } finally {
            actualConnection.close();
        }
    }

    /**
     * Simple runnable using the {@link #send(io.sentry.event.Event)} method of the
     * {@link #actualConnection}.
     */
    private final class EventSubmitter implements Runnable {
        private final Event event;

        private EventSubmitter(final Event event) {
            this.event = event;
        }

        @Override
        public void run() {
            SentryEnvironment.startManagingThread();
            try {
                // The current thread is managed by sentry
                actualConnection.send(event);
            } catch (final LockedDownException e) {
                lockdownLogger.warn("The connection to Sentry is currently locked down.", e);
            } catch (final Exception e) {
                logger.error("An exception occurred while sending the event to Sentry.", e);
            } finally {
                SentryEnvironment.stopManagingThread();
            }
        }
    }

    private final class ShutDownHook extends Thread {

        /**
         * Whether or not this ShutDownHook instance will do anything when run.
         */
        private volatile boolean enabled = true;

        @Override
        public void run() {
            if (!enabled) {
                return;
            }

            SentryEnvironment.startManagingThread();
            try {
                // The current thread is managed by sentry
                doClose();
            } catch (final Exception e) {
                logger.error("An exception occurred while closing the connection.", e);
            } finally {
                SentryEnvironment.stopManagingThread();
            }
        }
    }
}
