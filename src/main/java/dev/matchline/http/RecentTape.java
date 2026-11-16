package dev.matchline.http;

import dev.matchline.sequencer.CommandDecision;
import dev.matchline.sequencer.SequencedResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * Strictly bounded per-process HTTP-completion tape, not a durable event log.
 * Keeps at most 2048 compact command summaries AND 2048 separate trade entries.
 * Keys are sequencer sequence/fill index, so HTTP response-thread scheduling
 * cannot reorder visible events. It need not include timed-out admissions or
 * any history before process restart.
 */
public final class RecentTape {
    public static final int MAX_COMMANDS = 2_048;
    public static final int MAX_TRADES = 2_048;
    private final NavigableMap<Long, Map<String, Object>> decisions = new TreeMap<>();
    private final NavigableMap<FillKey, Map<String, Object>> trades = new TreeMap<>();
    private final boolean durable;

    public RecentTape(boolean durable) { this.durable = durable; }

    public synchronized void record(SequencedResult result) {
        var event = ApiView.decision(result);
        event.put("durable", durable);
        if (result.decision() instanceof CommandDecision.Placed placed) {
            event.remove("trades"); // Details are in the independently capped trade tape.
            event.put("tradeCount", placed.result().trades().size());
            for (int index = 0; index < placed.result().trades().size(); index++) {
                int ordinal = index + 1;
                trades.put(new FillKey(result.sequence(), ordinal),
                        ApiView.trade(result.sequence(), ordinal, placed.result().trades().get(index)));
                while (trades.size() > MAX_TRADES) trades.pollFirstEntry();
            }
        }
        decisions.put(result.sequence(), event);
        while (decisions.size() > MAX_COMMANDS) decisions.pollFirstEntry();
    }

    public synchronized void clear() {
        decisions.clear();
        trades.clear();
    }

    public synchronized int size() { return decisions.size(); }

    public synchronized List<Object> events(int limit, boolean tradesOnly) {
        var stored = tradesOnly ? trades.values() : decisions.values();
        var all = new ArrayList<Object>(stored);
        return List.copyOf(all.subList(Math.max(0, all.size() - limit), all.size()));
    }

    private record FillKey(long sequence, int index) implements Comparable<FillKey> {
        @Override public int compareTo(FillKey other) {
            int bySequence = Long.compare(sequence, other.sequence);
            return bySequence != 0 ? bySequence : Integer.compare(index, other.index);
        }
    }
}
