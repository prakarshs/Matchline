package dev.matchline.spring;

import dev.matchline.book.LimitOrderBook;
import dev.matchline.domain.InstrumentId;
import dev.matchline.sequencer.SingleWriterSequencer;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed settings for the local, volatile Spring-owned matching engine.
 *
 * <p>Bind at the application boundary, then validate against the domain's actual
 * limits before starting a writer thread. This class deliberately does not add
 * a second source of trading rules. No journal path or public-session settings
 * are silently inherited from the legacy HTTP process in S02.
 */
@ConfigurationProperties(prefix = "matchline.engine")
public final class MatchlineEngineProperties {
    private String instrument = "DEMO";
    private int queueCapacity = SingleWriterSequencer.DEFAULT_QUEUE_CAPACITY;
    private int maxRestingOrders = LimitOrderBook.DEFAULT_MAX_RESTING_ORDERS;
    private int maxOrderIds = LimitOrderBook.DEFAULT_MAX_ORDER_IDS;

    public String getInstrument() { return instrument; }
    public void setInstrument(String instrument) { this.instrument = instrument; }

    public int getQueueCapacity() { return queueCapacity; }
    public void setQueueCapacity(int queueCapacity) { this.queueCapacity = queueCapacity; }

    public int getMaxRestingOrders() { return maxRestingOrders; }
    public void setMaxRestingOrders(int maxRestingOrders) { this.maxRestingOrders = maxRestingOrders; }

    public int getMaxOrderIds() { return maxOrderIds; }
    public void setMaxOrderIds(int maxOrderIds) { this.maxOrderIds = maxOrderIds; }

    /** Parse once at startup; invalid input must fail before any writer is started. */
    public InstrumentId validatedInstrument() {
        return new InstrumentId(instrument);
    }

    public void validateCapacity() {
        if (queueCapacity < 1 || queueCapacity > SingleWriterSequencer.MAX_QUEUE_CAPACITY) {
            throw new IllegalArgumentException("matchline.engine.queue-capacity must be 1.."
                    + SingleWriterSequencer.MAX_QUEUE_CAPACITY);
        }
        if (maxRestingOrders < 1 || maxRestingOrders > LimitOrderBook.HARD_MAX_RESTING_ORDERS) {
            throw new IllegalArgumentException("matchline.engine.max-resting-orders must be 1.."
                    + LimitOrderBook.HARD_MAX_RESTING_ORDERS);
        }
        if (maxOrderIds < 1 || maxOrderIds > LimitOrderBook.HARD_MAX_ORDER_IDS) {
            throw new IllegalArgumentException("matchline.engine.max-order-ids must be 1.."
                    + LimitOrderBook.HARD_MAX_ORDER_IDS);
        }
    }
}
