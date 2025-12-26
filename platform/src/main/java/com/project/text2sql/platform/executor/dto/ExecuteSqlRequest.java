package com.project.text2sql.platform.executor.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ExecuteSqlRequest(
        @NotBlank
        @Size(max = 20000)
        String sql
) {}