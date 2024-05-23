package io.kestra.plugin.amqp;

import com.rabbitmq.client.CancelCallback;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DeliverCallback;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.conditions.ConditionContext;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.triggers.AbstractTrigger;
import io.kestra.core.models.triggers.RealtimeTriggerInterface;
import io.kestra.core.models.triggers.TriggerContext;
import io.kestra.core.models.triggers.TriggerOutput;
import io.kestra.core.models.triggers.TriggerService;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.amqp.models.Message;
import io.kestra.plugin.amqp.models.SerdeType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "React to and consume messages from an AMQP queue creating one executions for each message."
)
@Plugin(
    examples = {
        @Example(
            code = {
                "url: amqp://guest:guest@localhost:5672/my_vhost",
                "maxRecords: 2",
                "queue: amqpTrigger.queue"
            }
        )
    },
    beta = true
)
public class RealtimeTrigger extends AbstractTrigger implements RealtimeTriggerInterface, TriggerOutput<Message>, ConsumeBaseInterface, AmqpConnectionInterface {
    @Builder.Default
    private final Duration interval = Duration.ofSeconds(60);

    private String url;
    private String host;
    private String port;
    private String username;
    private String password;
    private String virtualHost;

    private String queue;

    @Builder.Default
    private String consumerTag = "Kestra";

    @Builder.Default
    private SerdeType serdeType = SerdeType.STRING;

    @Builder.Default
    @Getter(AccessLevel.NONE)
    private final AtomicBoolean isActive = new AtomicBoolean(true);

    @Builder.Default
    @Getter(AccessLevel.NONE)
    private final CountDownLatch waitForTermination = new CountDownLatch(1);

    @Override
    public Publisher<Execution> evaluate(ConditionContext conditionContext, TriggerContext context) throws Exception {
        Consume task = Consume.builder()
            .url(this.url)
            .host(this.host)
            .port(this.port)
            .username(this.username)
            .password(this.password)
            .virtualHost(this.virtualHost)
            .queue(this.queue)
            .consumerTag(this.consumerTag)
            .serdeType(this.serdeType)
            .build();

        return Flux.from(publisher(task, conditionContext.getRunContext()))
            .map((record) -> TriggerService.generateRealtimeExecution(this, context, record));
    }

    public Publisher<Message> publisher(final Consume task, final RunContext runContext) {
        return Flux.create(
            emitter -> {
                final AtomicReference<Throwable> error = new AtomicReference<>();
                try {
                    final String queue = runContext.render(task.getQueue());
                    final String consumerTag = runContext.render(task.getConsumerTag());

                    ConnectionFactory factory = task.connectionFactory(runContext);
                    Connection connection = factory.newConnection();
                    Channel channel = connection.createChannel();

                    final AtomicBoolean basicCancel = new AtomicBoolean(true);
                    emitter.onDispose(() -> {
                        try {
                            if (channel.isOpen() && channel.getConnection().isOpen()) {
                                if (basicCancel.compareAndSet(true, false)) {
                                    channel.basicCancel(consumerTag); // stop consuming
                                }
                                channel.close();
                            }
                            connection.close();
                        } catch (IOException | TimeoutException e) {
                            runContext.logger().warn("Error while closing channel or connection: " + e.getMessage());
                        } finally {
                            waitForTermination.countDown();
                        }
                    });

                    DeliverCallback deliverCallback = (tag, message) -> {
                        try {
                            Message output = Message.of(message.getBody(), task.getSerdeType(), message.getProperties());
                            emitter.next(output);
                            channel.basicAck(message.getEnvelope().getDeliveryTag(), false);
                        } catch (Exception e) {
                            error.set(e);
                            isActive.set(false);
                        }
                    };

                    CancelCallback cancelCallback = tag -> {
                        runContext.logger().info("Consumer {} has been cancelled", consumerTag);
                        basicCancel.set(false);
                        isActive.set(false);
                    };

                    // create basic consumer
                    channel.basicConsume(
                        queue,
                        false, // auto-ack
                        consumerTag,
                        deliverCallback,
                        cancelCallback,
                        (tag, sig) -> {}
                    );

                    // wait for consumer to be stopped
                    busyWait();

                } catch (Throwable e) {
                    error.set(e);
                } finally {
                    // dispose
                    Throwable throwable = error.get();
                    if (throwable != null) {
                        emitter.error(throwable);
                    } else {
                        emitter.complete();
                    }
                }
            });
    }

    private void busyWait() {
        while (isActive.get()) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                isActive.set(false); // proactively stop consuming
            }
        }
    }

    /**
     * {@inheritDoc}
     **/
    @Override
    public void kill() {
        stop(true);
    }

    /**
     * {@inheritDoc}
     **/
    @Override
    public void stop() {
        stop(false); // must be non-blocking
    }

    private void stop(boolean wait) {
        if (!isActive.compareAndSet(true, false)) {
            return;
        }

        if (wait) {
            try {
                this.waitForTermination.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
