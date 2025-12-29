package com.project.text2sql.platform.executor;

import com.project.text2sql.platform.executor.dto.ExecuteSqlRequest;
import com.project.text2sql.platform.executor.dto.ExecuteSqlResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/executor")
public class SqlExecutorController {
    private final SqlExecutorService executorService;

    public SqlExecutorController(SqlExecutorService executorService) {
        this.executorService = executorService;
    }

    @PostMapping("/execute")
    public ExecuteSqlResponse execute(@Valid @RequestBody ExecuteSqlRequest req) {
        return executorService.execute(req.sql());
    }
}