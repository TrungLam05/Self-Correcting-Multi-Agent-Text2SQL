package com.project.text2sql.platform.text2sql;

import com.project.text2sql.platform.text2sql.dto.Text2SqlResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(Text2SqlController.class)
class Text2SqlControllerTest {

    @Autowired
    MockMvc mvc;

    @MockBean
    Text2SqlService text2SqlService;

    @Test
    void execute_returnsResponse() throws Exception {
        when(text2SqlService.executeQuestion(anyString()))
                .thenReturn(new Text2SqlResponse(null, java.util.List.of(), java.util.List.of(), 0.0, "not implemented", 0, 0L, "t1"));

        mvc.perform(post("/api/text2sql/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"hello\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.traceId").value("t1"));
    }

    @Test
    void execute_rejectsMissingBody() throws Exception {
        mvc.perform(post("/api/text2sql/execute"))
                .andExpect(status().isBadRequest());
    }
}
