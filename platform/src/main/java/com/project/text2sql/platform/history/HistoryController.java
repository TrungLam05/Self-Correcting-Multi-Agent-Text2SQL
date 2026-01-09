package com.project.text2sql.platform.history;

import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/history")
@CrossOrigin(origins = "*")
public class HistoryController {

    private final HistoryRepository repository;

    public HistoryController(HistoryRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<QueryHistory> getHistory() {
        return repository.findRecent();
    }

    @PostMapping
    public Map<String, String> saveEntry(@RequestBody Map<String, Object> body) {
        String nlQuery = (String) body.get("natural_language_query");
        String sql = (String) body.get("generated_sql");

        Integer timeMs = 0;
        if (body.get("execution_time_ms") instanceof Number) {
            timeMs = ((Number) body.get("execution_time_ms")).intValue();
        }

        repository.save(nlQuery, sql, timeMs);

        return Map.of("message", "History saved successfully");
    }
}