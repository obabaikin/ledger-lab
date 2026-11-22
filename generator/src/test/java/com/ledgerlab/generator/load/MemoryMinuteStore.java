package com.ledgerlab.generator.load;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentSkipListMap;

public class MemoryMinuteStore implements MinuteStore {

    private final ConcurrentSkipListMap<Instant, MinuteCounts> minutes = new ConcurrentSkipListMap<>();

    @Override
    public void add(List<MinuteCounts> counts) {
        for (var it : counts) {
            minutes.merge(it.minute(), it, (a, b) -> new MinuteCounts(a.minute(), a.total() + b.total(), a.errors() + b.errors(), a.busy() + b.busy()));
        }
    }

    @Override
    public List<MinuteCounts> between(Instant first, Instant last) {
        return List.copyOf(minutes.subMap(first, true, last, true).values());
    }

    @Override
    public void dropBefore(Instant minute) {
        minutes.headMap(minute).clear();
    }
}
