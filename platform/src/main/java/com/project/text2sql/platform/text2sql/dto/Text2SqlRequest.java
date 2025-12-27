package com.project.text2sql.platform.text2sql.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record Text2SqlRequest(
        @NotBlank
        @Size(max = 2000)
        String question
) {}
