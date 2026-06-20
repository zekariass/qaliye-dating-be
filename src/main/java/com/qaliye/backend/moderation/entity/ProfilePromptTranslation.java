package com.qaliye.backend.moderation.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "profile_prompt_translations")
@Getter
@Setter
@NoArgsConstructor
public class ProfilePromptTranslation {

    @EmbeddedId
    private ProfilePromptTranslationId id;

    @Column(name = "prompt_text", nullable = false)
    private String promptText;
}
