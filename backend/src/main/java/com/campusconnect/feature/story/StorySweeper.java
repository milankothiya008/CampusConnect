package com.campusconnect.feature.story;

import com.campusconnect.feature.story.StoryService.SweepResult;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Removes expired stories and their files every five minutes (StoryService.startStorySweeper). With several
 * replicas the ShedLock lock in Redis lets exactly one of them sweep each round.
 */
@Slf4j
@Component
public class StorySweeper {

    private final StoryService stories;

    public StorySweeper(StoryService stories) {
        this.stories = stories;
    }

    @Scheduled(fixedDelay = 5 * 60 * 1000)
    @SchedulerLock(name = "storySweeper", lockAtMostFor = "PT5M", lockAtLeastFor = "PT1M")
    public SweepResult sweepExpiredStories() {
        try {
            SweepResult result = stories.sweepExpiredStories(Instant.now());
            if (result.removed() > 0) {
                log.info("Removed {} expired stor{}", result.removed(), result.removed() == 1 ? "y" : "ies");
            }
            return result;
        } catch (RuntimeException e) {
            log.error("Story sweep failed: {}", e.getMessage());
            return new SweepResult(0, 0);
        }
    }
}
