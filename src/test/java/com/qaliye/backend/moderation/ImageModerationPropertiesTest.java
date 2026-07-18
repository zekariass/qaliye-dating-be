package com.qaliye.backend.moderation;

import com.qaliye.backend.moderation.rekognition.ImageModerationProperties;
import com.qaliye.backend.moderation.rekognition.SuggestiveContentAction;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ImageModerationPropertiesTest {

    @Test
    void defaults_are_sensible() {
        ImageModerationProperties props = new ImageModerationProperties();

        assertThat(props.isEnabled()).isTrue();
        assertThat(props.getMaxRetries()).isEqualTo(3);

        ImageModerationProperties.FaceDetection fd = props.getFaceDetection();
        assertThat(fd.isEnabled()).isTrue();
        assertThat(fd.getMinConfidence()).isEqualTo(95.0);
        assertThat(fd.getMinBrightness()).isEqualTo(25.0);
        assertThat(fd.getMinSharpness()).isEqualTo(40.0);
        assertThat(fd.getMinSizePercent()).isEqualTo(20.0);
        assertThat(fd.isOcclusionCheckEnabled()).isFalse();

        ImageModerationProperties.NudityModeration nm = props.getNudityModeration();
        assertThat(nm.isEnabled()).isTrue();
        assertThat(nm.getMinConfidence()).isEqualTo(90.0);
        assertThat(nm.getSuggestiveContentAction()).isEqualTo(SuggestiveContentAction.MANUAL_REVIEW);
    }

    @Test
    void configVersion_changes_when_thresholds_change() {
        ImageModerationProperties a = new ImageModerationProperties();
        ImageModerationProperties b = new ImageModerationProperties();
        b.getFaceDetection().setMinConfidence(80.0);

        assertThat(a.configVersion()).isNotEqualTo(b.configVersion());
    }

    @Test
    void configVersion_changes_when_module_toggled() {
        ImageModerationProperties enabled  = new ImageModerationProperties();
        ImageModerationProperties disabled = new ImageModerationProperties();
        disabled.getFaceDetection().setEnabled(false);

        assertThat(enabled.configVersion()).isNotEqualTo(disabled.configVersion());
    }

    @Test
    void configVersion_stable_for_identical_config() {
        ImageModerationProperties a = new ImageModerationProperties();
        ImageModerationProperties b = new ImageModerationProperties();

        assertThat(a.configVersion()).isEqualTo(b.configVersion());
    }
}
