package com.qaliye.backend.moderation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.util.UUID;

@Embeddable
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode
public class ProfilePromptTranslationId implements Serializable {

    @Column(name = "prompt_id")
    private UUID promptId;

    @Column(name = "locale")
    private String locale;
}
