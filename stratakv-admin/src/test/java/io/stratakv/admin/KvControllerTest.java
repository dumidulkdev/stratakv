package io.stratakv.admin;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "stratakv.data-dir=target/admin-data")
class KvControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void kvAndStatsEndpoints() throws Exception {
        mockMvc.perform(put("/kv/demo").content("value-1"))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/kv/demo"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string("value-1"));
        mockMvc.perform(get("/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.memtableBytes").isNumber());
        mockMvc.perform(delete("/kv/demo")).andExpect(status().isNoContent());
        mockMvc.perform(post("/compact")).andExpect(status().isAccepted());
    }
}