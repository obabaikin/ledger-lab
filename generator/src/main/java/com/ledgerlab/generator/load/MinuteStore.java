package com.ledgerlab.generator.load;

import java.time.Instant;
import java.util.List;

public interface MinuteStore {

    /** Adds the counts to what the minutes hold already. */
    void add(List<MinuteCounts> counts);

    /** Minutes from {@code first} to {@code last}, both included, oldest first. */
    List<MinuteCounts> between(Instant first, Instant last);

    void dropBefore(Instant minute);
}
