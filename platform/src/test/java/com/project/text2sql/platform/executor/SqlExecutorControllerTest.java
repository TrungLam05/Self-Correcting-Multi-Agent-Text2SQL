package com.project.text2sql.platform.executor;

import com.project.text2sql.platform.executor.dto.ExecuteSqlResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(SqlExecutorController.class)
class SqlExecutorControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private SqlExecutorService executorService;

    @Test
    void executeReturnsJson() throws Exception {
        when(executorService.execute("select 1"))
                .thenReturn(new ExecuteSqlResponse(
                        "SELECT 1 LIMIT 1",
                        List.of("col1"),
                        List.of(List.of("1")),
                        false,
                        1,
                        5
                ));

        mvc.perform(post("/api/executor/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                                                .content("{\"sql\":\"select 1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.columns[0]").value("col1"))
                .andExpect(jsonPath("$.rowCount").value(1));
    }
}