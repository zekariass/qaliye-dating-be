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
 * Quartz sweeper: expires PENDING video-call requests past their
 * request_expires_at window and notifies the requester.
 *
 * <p>Runs every 15 minutes.
 */
@Component
public class VideoCallRequestSweeper implements Job {

    private static final Logger log = LoggerFactory.getLogger(VideoCallRequestSweeper.class);

    @Autowired
    private VideoCallRequestService service;

    @Override
    public void execute(JobExecutionContext context) {
        try {
            List<UUID> expired = service.expireSweep();
            if (!expired.isEmpty()) {
                log.info("VideoCallRequestSweeper: expired {} requests", expired.size());
            }
        } catch (Exception e) {
            log.error("VideoCallRequestSweeper failed: {}", e.getMessage(), e);
        }
    }
}
