package com.qaliye.backend.moderation.rekognition;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.rekognition.model.ModerationLabel;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Analyzes profile photos using Rekognition DetectModerationLabels.
 * <p>
 * Only nudity and sexual-content labels are evaluated; violence, disturbing
 * content, and other unrelated categories are ignored so that non-nudity labels
 * can never trigger a rejection.
 * </p>
 *
 * <p><b>Label policy:</b></p>
 * <ul>
 *   <li>Explicit nudity or explicit sexual activity → REJECTED.</li>
 *   <li>Suggestive content → action determined by {@code suggestiveContentAction}
 *       (APPROVE / MANUAL_REVIEW / REJECT).</li>
 *   <li>All other labels → ignored.</li>
 * </ul>
 */
@Service
public class NudityModerationService {

    private static final Logger log = LoggerFactory.getLogger(NudityModerationService.class);

    // Top-level or child label names that indicate explicit nudity / sexual content.
    // Either the label name itself or its parent name matching any entry here → REJECT.
    private static final Set<String> EXPLICIT_LABELS = Set.of(
            "Explicit Nudity",
            "Nudity",
            "Graphic Male Nudity",
            "Graphic Female Nudity",
            "Sexual Activity",
            "Illustrated Explicit Nudity",
            "Graphic Sexual Activity"
    );

    // Label names that indicate suggestive (non-explicit) content.
    private static final Set<String> SUGGESTIVE_LABELS = Set.of(
            "Suggestive",
            "Partial Nudity",
            "Revealing Clothes",
            "Female Swimwear Or Underwear",
            "Male Swimwear Or Underwear",
            "Barechested Male",
            "Sexual Situations"
    );

    private final ImageModerationProperties props;
    private final RekognitionImageClient rekognitionClient;

    public NudityModerationService(ImageModerationProperties props,
                                   RekognitionImageClient rekognitionClient) {
        this.props = props;
        this.rekognitionClient = rekognitionClient;
    }

    public NudityAnalysisResult analyze(byte[] imageBytes) {
        ImageModerationProperties.NudityModeration cfg = props.getNudityModeration();
        float minConf = (float) cfg.getMinConfidence();

        List<ModerationLabel> labels = rekognitionClient.detectModerationLabels(imageBytes, minConf);
        log.debug("DetectModerationLabels returned {} label(s) above {}% confidence",
                labels.size(), minConf);

        boolean nudityDetected = false;
        boolean sexualDetected = false;
        boolean suggestiveHit  = false;
        List<String> triggered = new ArrayList<>();
        List<String> failures  = new ArrayList<>();

        for (ModerationLabel label : labels) {
            String name   = label.name();
            String parent = label.parentName();

            if (isExplicit(name, parent)) {
                nudityDetected = true;
                if (isExplicitSexual(name, parent)) sexualDetected = true;
                triggered.add(name);
                failures.add("EXPLICIT_CONTENT_DETECTED");
            } else if (isSuggestive(name, parent)) {
                suggestiveHit = true;
                triggered.add(name);
            }
        }

        // Explicit → always REJECT
        if (nudityDetected) {
            return new NudityAnalysisResult(false, ImageModerationStatus.REJECTED,
                    true, sexualDetected, triggered, failures,
                    "This photo could not be approved because it may contain nudity or sexual content.");
        }

        // Suggestive → apply configured action
        if (suggestiveHit) {
            SuggestiveContentAction action = cfg.getSuggestiveContentAction();
            return switch (action) {
                case REJECT -> new NudityAnalysisResult(false, ImageModerationStatus.REJECTED,
                        false, false, triggered, List.of("SUGGESTIVE_CONTENT_REJECTED"),
                        "This photo could not be approved because it may contain nudity or sexual content.");
                case MANUAL_REVIEW -> new NudityAnalysisResult(false, ImageModerationStatus.MANUAL_REVIEW,
                        false, false, triggered, List.of("SUGGESTIVE_CONTENT_REVIEW"),
                        "This photo requires additional review.");
                case APPROVE -> new NudityAnalysisResult(true, ImageModerationStatus.APPROVED,
                        false, false, triggered, List.of(), null);
            };
        }

        return new NudityAnalysisResult(true, ImageModerationStatus.APPROVED,
                false, false, List.of(), List.of(), null);
    }

    private boolean isExplicit(String name, String parent) {
        return EXPLICIT_LABELS.contains(name) || EXPLICIT_LABELS.contains(parent);
    }

    private boolean isExplicitSexual(String name, String parent) {
        return "Sexual Activity".equals(name) || "Graphic Sexual Activity".equals(name)
                || "Sexual Activity".equals(parent);
    }

    private boolean isSuggestive(String name, String parent) {
        return SUGGESTIVE_LABELS.contains(name) || SUGGESTIVE_LABELS.contains(parent);
    }
}
