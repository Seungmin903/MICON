package com.micon.backend.setting;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
	"policy.voice-data.version=2026-09-test",
	"policy.voice-data.collection-purpose=테스트 수집 목적",
	"policy.voice-data.retention-period=테스트 보관 기간",
	"policy.voice-data.deletion-policy=테스트 삭제 정책"
})
@AutoConfigureMockMvc
class VoiceDataPolicyApiIntegrationTests {

	@Autowired
	MockMvc mockMvc;

	@Test
	void returnsConfiguredVoiceDataPolicyWithoutAuthentication() throws Exception {
		mockMvc.perform(get("/policies"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.code").value("SUCCESS"))
			.andExpect(jsonPath("$.data.policyVersion").value("2026-09-test"))
			.andExpect(jsonPath("$.data.collectionPurpose").value("테스트 수집 목적"))
			.andExpect(jsonPath("$.data.retentionPeriod").value("테스트 보관 기간"))
			.andExpect(jsonPath("$.data.deletionPolicy").value("테스트 삭제 정책"));
	}
}
