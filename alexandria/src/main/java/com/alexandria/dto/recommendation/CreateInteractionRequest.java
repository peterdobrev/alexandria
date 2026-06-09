package com.alexandria.dto.recommendation;

import com.alexandria.entity.InteractionKind;
import jakarta.validation.constraints.NotNull;

public record CreateInteractionRequest(@NotNull InteractionKind kind) {}
