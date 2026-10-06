package com.qaliye.backend.videocall.worker;

import com.qaliye.backend.videocall.service.VideoCallRequestService;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Quartz sweeper: completes ACCEPTED video-call requests whose
 * {@code call_deadline_at} has elapsed — the max-duration enforcement path.
 * Token expiry already drops participants from the Agora channel; this is
 * state hygiene. Both parties get the {@code *_CALL_ENDED_TIME_LIMIT} alert.
 *
 * <p>Runs every 30 seconds.
 */
@Component
public class VideoCallDeadlineSweeper implements Job {

    private static final Logger log = LoggerFactory.getLogger(VideoCallDeadlineSweeper.class);

    @Autowired
    private VideoCallRequestService service;

    @Override
    public void execute(JobExecutionContext context) {
        try {
            List<UUID> ended = service.deadlineSweep();
            if (!ended.isEmpty()) {
                log.info("VideoCallDeadlineSweeper: ended {} calls at the time limit", ended.size());
            }
        } catch (Exception e) {
            log.error("VideoCallDeadlineSweeper failed: {}", e.getMessage(), e);
        }
    }
}
