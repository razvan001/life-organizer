package com.lifeorganizer.memory.history;

import com.lifeorganizer.memory.index.MemoryIndexer;
import com.lifeorganizer.persistence.BlockRepository;
import com.lifeorganizer.persistence.PlanStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Keeps one memory entry per past day. Reads stored days only (PlanStore.findDay) and never calls
 * PlanService.getOrGenerate: indexing must not create days that were never planned.
 */
@Component
public class PlanHistoryIndexer {

    static final int DAYS_BACK = 90;

    private static final Logger log = LoggerFactory.getLogger(PlanHistoryIndexer.class);

    private final BlockRepository blocks;
    private final PlanStore planStore;
    private final MemoryIndexer indexer;
    private final Clock clock;

    public PlanHistoryIndexer(BlockRepository blocks, PlanStore planStore, MemoryIndexer indexer, Clock clock) {
        this.blocks = blocks;
        this.planStore = planStore;
        this.indexer = indexer;
        this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        try {
            indexPastDays();
        } catch (RuntimeException e) {
            log.warn("Plan history indexing at startup failed; search will miss past days until the next run", e);
        }
    }

    @Scheduled(cron = "0 5 0 * * *")
    public void nightly() {
        indexPastDays();
    }

    public int indexPastDays() {
        LocalDate today = LocalDate.now(clock);
        List<LocalDate> dates = blocks.findDistinctDatesBetween(today.minusDays(DAYS_BACK), today.minusDays(1));
        int indexed = 0;
        for (LocalDate date : dates) {
            Optional<String> summary = planStore.findDay(date).flatMap(DaySummarizer::summarize);
            if (summary.isEmpty()) {
                indexer.removeDay(date);
            } else if (indexer.indexDay(date, DaySummarizer.title(date), summary.get())) {
                indexed++;
            }
        }
        log.info("Plan history: {} past day(s) checked, {} (re)indexed", dates.size(), indexed);
        return indexed;
    }
}
