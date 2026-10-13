package com.campusconnect.core.jobs;

import com.campusconnect.core.config.AppProperties;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.ArrayList;
import java.util.List;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Declares the job queues (idempotent on every start) and the JSON message format. */
@Configuration
@ConditionalOnProperty(name = "app.jobs.mode", havingValue = "rabbit", matchIfMissing = true)
public class RabbitJobsConfig {

    @Bean
    MessageConverter jobMessageConverter() {
        JacksonJsonMessageConverter converter = new JacksonJsonMessageConverter("com.campusconnect.*");
        converter.setAlwaysConvertToInferredType(true);
        return converter;
    }

    @Bean
    Declarables jobQueues(AppProperties properties) {
        List<Declarable> queues = new ArrayList<>();
        queues.add(QueueBuilder.durable(JobTopology.MAIL).maxPriority(10).deadLetterExchange("").deadLetterRoutingKey(JobTopology.DEAD).build());
        queues.add(QueueBuilder.durable(JobTopology.MAIL_URGENT).deadLetterExchange("").deadLetterRoutingKey(JobTopology.DEAD).build());
        for (Duration delay : new LinkedHashSet<>(properties.getJobs().getMailRetryDelays())) {
            queues.add(QueueBuilder.durable(JobTopology.mailRetryQueue(delay))
                    .ttl((int) delay.toMillis())
                    .deadLetterExchange("")
                    .deadLetterRoutingKey(JobTopology.MAIL)
                    .build());
        }
        // Permanently failed e-mails are kept 30 days for troubleshooting (like the EmailJob clean-up index).
        // x-message-ttl as a long: 30 days in milliseconds does not fit in an int (RabbitMQ allows up to 2^32-1).
        queues.add(QueueBuilder.durable(JobTopology.MAIL_DEAD).withArgument("x-message-ttl", Duration.ofDays(30).toMillis()).build());
        queues.add(QueueBuilder.durable(JobTopology.PUSH).deadLetterExchange("").deadLetterRoutingKey(JobTopology.DEAD).build());
        queues.add(QueueBuilder.durable(JobTopology.BROADCAST).deadLetterExchange("").deadLetterRoutingKey(JobTopology.DEAD).build());
        queues.add(QueueBuilder.durable(JobTopology.DEAD).withArgument("x-message-ttl", Duration.ofDays(7).toMillis()).build());
        return new Declarables(queues);
    }
}
