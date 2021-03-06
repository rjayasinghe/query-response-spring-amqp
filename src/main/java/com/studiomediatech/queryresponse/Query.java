package com.studiomediatech.queryresponse;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.type.TypeFactory;

import com.studiomediatech.queryresponse.util.Logging;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageListener;

import java.io.IOException;

import java.time.Duration;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;


/**
 * Represents an active published query, and the registered message listener for any responses, handling aggregation,
 * fallback, failure delegation and throwing, all depending on the configuration of this query.
 *
 * @param  <T>  expected type of the coerced result elements.
 */
class Query<T> implements MessageListener, Logging {

    private static final long ONE_MILLIS = 1L;

    private static final ObjectReader reader = new ObjectMapper().reader();

    private final String queueName;

    protected List<T> elements;
    protected String queryTerm;
    protected Class<T> responseType;
    protected Duration waitingFor;
    protected Supplier<Collection<T>> orDefaults;
    protected Consumer<Throwable> onError;
    protected int atLeast;
    protected int atMost;
    protected Supplier<RuntimeException> orThrows;

    /*
     * A protected boolean function, tested with an integer, and always
     * returning false, by default. This enables testability, as it's protected
     * and may compute other results.
     */
    protected Function<Long, Boolean> fail = l -> false;

    // Declared protected, for access in unit tests.
    protected Query() {

        this.queueName = UUID.randomUUID().toString();
        this.elements = new ArrayList<>();
    }

    @Override
    public void onMessage(Message message) {

        log().info("|--> Received response message: {}", message.getMessageProperties());
        handleResponseEnvelope(parseMessage(message));
    }


    private ConsumedResponseEnvelope<T> parseMessage(Message message) {

        try {
            var type = TypeFactory.defaultInstance()
                    .constructParametricType(ConsumedResponseEnvelope.class, responseType);
            ConsumedResponseEnvelope<T> response = reader.forType(type).readValue(message.getBody());
            log().debug("Received response: {}", response);

            return response;
        } catch (IOException ex) {
            if (onError != null) {
                var errorMessage = "Failed to parse response to elements of type " + responseType.getSimpleName();
                onError.accept(new IllegalArgumentException(errorMessage, ex));
            }

            log().error("Failed to parse received response.", ex);
        }

        return ConsumedResponseEnvelope.empty();
    }


    void handleResponseEnvelope(ConsumedResponseEnvelope<T> envelope) {

        if (envelope.elements.isEmpty()) {
            log().warn("Received empty response: {}", envelope);

            return;
        }

        elements.addAll(envelope.elements);
    }


    static <T> Query<T> from(QueryBuilder<T> queryBuilder) {

        Query<T> query = new Query<>();

        query.queryTerm = queryBuilder.getQueryForTerm();
        query.responseType = queryBuilder.getType();
        query.waitingFor = queryBuilder.getWaitingFor();
        query.orDefaults = queryBuilder.getOrDefaults();
        query.onError = queryBuilder.getOnError();

        query.atLeast = queryBuilder.getTakingAtLeast();

        /*
         * If there's an lower limit, we scale the elements list using a fix
         * pad, and maybe we prevent growing.
         */
        if (query.atLeast > 0) {
            query.elements = new ArrayList<>(query.atLeast + 42);
        }

        query.atMost = queryBuilder.getTakingAtMost();

        /*
         * If there's an upper limit, we can also limit the aggregate list, and
         * allow for overrun. This may prevent growing of the list.
         */
        if (query.atMost > 0) {
            query.elements = new ArrayList<>(query.atMost + 7);
        }

        query.orThrows = queryBuilder.getOrThrows();

        return query;
    }


    public String getQueueName() {

        return this.queueName;
    }


    /**
     * Accepts the RabbitMQ facade, in order to start the life-cycle, and publish this query to the broker. This method
     * always blocks on the calling thread, and the query configuration requires users (programmers) to <strong>
     * always</strong> declare a timeout.
     *
     * @param  facade  to the broker methods, used for publishing, never {@code null}
     * @param  stats  service, allows reporting of success or failure to queries and response.
     *
     * @return  the consumed response elements collection. May be empty, if the query was configured that way, but this
     *          method call never returns {@code null}.
     *
     * @throws  RuntimeException  if the query was configured that way. Please note that the internals of query
     *                            publishing, and response consumption <strong>never throws</strong>. Exceptions or
     *                            failures will be caught and logged.
     */
    public Collection<T> accept(RabbitFacade facade, Statistics stats) throws RuntimeException {

        publishQuery(facade, stats);

        /*
         * In this iteration of the Query/Response library, we block on the
         * calling thread. This is the most simple thing I could think of.
         */
        var wait = this.waitingFor.toMillis();

        /*
         * We yield on the thread only to check if the consuming side, has
         * filled up our collection. If we have to bail for other reasons, that
         * could be added to this loop.
         */
        while (wait-- > 0) {
            /*
             * Check the current aggregate. Shady thread safety on the elements
             * collection, but we know it's append only and we pick out the
             * sub-list even if overrun in size.
             */
            if (atMost > 0 && elements.size() >= atMost) {
                stats.incrementResponsesCounter();

                return this.elements.subList(0, atMost);
            }

            try {
                if (this.fail.apply(wait)) {
                    throw new InterruptedException();
                } else {
                    Thread.sleep(ONE_MILLIS);
                }
            } catch (InterruptedException e) {
                // Soft handler for the exception, may be informed.
                if (this.onError != null) {
                    onError.accept(e);
                }

                // Reset interrupted state, before moving on.
                log().error("Sleep interrupted with still " + wait + "ms to go", e);
                Thread.currentThread().interrupt();

                /*
                 * If we're configured for it, we may throw an unchecked runtime
                 * exception.
                 */
                if (this.orThrows != null) {
                    stats.incrementFallbacksCounter();

                    throw this.orThrows.get();
                }

                // Otherwise we break and accept this as a failure.
                break;
            }
        }

        /*
         * Enforced only if set to a positive integer, and not 0.
         */
        boolean notEnoughResponses = atLeast > 0 && elements.size() < atLeast;

        /*
         * Queries always either returns with the collected response elements,
         * defaults or throws by configuration of the user.
         */
        if (elements.isEmpty() || notEnoughResponses) {
            stats.incrementFallbacksCounter();

            if (this.orDefaults != null) {
                return this.orDefaults.get();
            } else if (this.orThrows != null) {
                throw this.orThrows.get();
            }
        }

        stats.incrementResponsesCounter();

        return elements;
    }


    private void publishQuery(RabbitFacade facade, Statistics stats) {

        try {
            facade.publishQuery(this.queryTerm,
                MessageBuilder.withBody("{}".getBytes()).setReplyTo(this.queueName).build());
            stats.incrementQueriesCounter();
        } catch (RuntimeException ex) {
            if (this.onError != null) {
                this.onError.accept(ex);
            }

            log().error("Failed to publish query message", ex);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class ConsumedResponseEnvelope<R> {

        @JsonProperty
        public int count;
        @JsonProperty
        public int total;
        @JsonProperty
        public Collection<R> elements = new ArrayList<>();

        public static <R> ConsumedResponseEnvelope<R> empty() {

            return new ConsumedResponseEnvelope<>();
        }


        @Override
        public String toString() {

            return "ConsumedResponseEnvelope [count=" + count + ", total=" + total + "]";
        }
    }
}
