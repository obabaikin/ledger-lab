package com.ledgerlab.control.runs;

import com.ledgerlab.control.ControlProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RunQueue {

    private static final Logger log = LoggerFactory.getLogger(RunQueue.class);

    private final RunRepository runs;
    private final ControlProperties props;

    public RunQueue(RunRepository runs, ControlProperties props) {
        this.runs = runs;
        this.props = props;
    }

    @Transactional
    public QueueStep advance(boolean bankReady) {
        runs.lockQueue();
        var expired = runs.expireQueued(props.runs().queueTimeout());
        var free = props.runs().maxActive() - runs.countIn(RunState.SLOT_STATES);
        var tpsBudget = props.runs().maxTotalTps() - runs.sumTpsIn(RunState.SLOT_STATES);
        var admitted = bankReady && free > 0 && tpsBudget > 0 ? runs.admitOldest(free, tpsBudget) : 0;
        if (expired > 0 || admitted > 0) {
            log.info("Run queue: {} expired, {} admitted", expired, admitted);
        }
        return new QueueStep(expired, admitted);
    }
}
