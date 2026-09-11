package com.micon.backend.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class GuestCatalogSecurityIntegrationTests {

    @Autowired MockMvc mockMvc;

    @Test
    void guestCanBrowseSongCatalog() throws Exception {
        mockMvc.perform(get("/songs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"));
    }

    @Test
    void guestCannotAccessMemberPracticeAssetApi() throws Exception {
        mockMvc.perform(get("/songs/1/practice-assets"))
                .andExpect(status().isUnauthorized());
    }
}
