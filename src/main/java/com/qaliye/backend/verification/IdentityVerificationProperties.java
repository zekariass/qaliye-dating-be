package com.qaliye.backend.verification;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "identity.verification")
@Validated
public class IdentityVerificationProperties {

    @DecimalMin("0") @DecimalMax("100")
    private double similarityThreshold = 80.0;

    public double getSimilarityThreshold() { return similarityThreshold; }
    public void setSimilarityThreshold(double similarityThreshold) { this.similarityThreshold = similarityThreshold; }
}
