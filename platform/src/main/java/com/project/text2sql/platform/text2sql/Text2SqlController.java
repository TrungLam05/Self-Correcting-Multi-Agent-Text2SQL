package com.project.text2sql.platform.text2sql;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.project.text2sql.platform.text2sql.dto.Text2SqlRequest;
import com.project.text2sql.platform.text2sql.dto.Text2SqlResponse;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/text2sql")
public class Text2SqlController {
    private final Text2SqlService text2SqlService;

    public Text2SqlController(Text2SqlService text2SqlService) {
        this.text2SqlService = text2SqlService;
    }

    @PostMapping("/execute")
    public Text2SqlResponse execute(@Valid @RequestBody Text2SqlRequest request) {
        return text2SqlService.executeQuestion(request.question());
    }
}
