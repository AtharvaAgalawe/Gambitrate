package com.gambitrate.api;

import com.gambitrate.service.SyncJobService;
import com.gambitrate.service.openings.OpeningsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest
class RootControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OpeningsService openingsService;

    @MockitoBean
    private SyncJobService syncJobService;

    @Test
    void root_forwardsToTheDashboard() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/index.html"));
    }
}
