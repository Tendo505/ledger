package io.github.tendo505.ledger.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Releases the reserved funds of holds that were neither captured nor voided in time. */
@Component
public class HoldSweeper {
    private static final Logger log = LoggerFactory.getLogger(HoldSweeper.class);
    private final LedgerService ledger;

    public HoldSweeper(LedgerService ledger) {
        this.ledger = ledger;
    }

    @Scheduled(fixedDelayString = "${ledger.hold-sweep-interval}")
    public void sweep() {
        int expired = ledger.expireDue();
        if (expired > 0) log.info("expired {} holds", expired);
    }
}
