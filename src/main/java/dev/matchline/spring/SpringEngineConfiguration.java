package dev.matchline.spring;

import dev.matchline.domain.InstrumentId;
import dev.matchline.sequencer.SingleWriterSequencer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring owns the writer's lifecycle; the writer still exclusively owns the book.
 *
 * <p>One singleton per ApplicationContext. Construction starts one writer; closing
 * the context stops admission, drains accepted work and closes the writer through
 * {@link SingleWriterSequencer#close()}. Do not add @Component to the sequencer
 * or construct a second instance from a controller.
 *
 * <p>S02 is deliberately VOLATILE and LOCAL. Durable recovery and per-visitor
 * public-demo sessions still belong to the verified legacy adapter. Port 8082
 * is not the public Render entrypoint during this migration phase.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MatchlineEngineProperties.class)
public class SpringEngineConfiguration {
    @Bean(destroyMethod = "close")
    SingleWriterSequencer matchlineSequencer(MatchlineEngineProperties properties) {
        // Validate everything before allocating/starting the writer thread.
        InstrumentId instrument = properties.validatedInstrument();
        properties.validateCapacity();
        return new SingleWriterSequencer(instrument, properties.getQueueCapacity(),
                properties.getMaxRestingOrders(), properties.getMaxOrderIds());
    }
}
