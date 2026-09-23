package com.micon.app.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class WithdrawalErrorMessageTest {

    @Test
    fun `current password error is recognized independently of http status`() {
        assertEquals(
            "비밀번호가 올바르지 않습니다.",
            withdrawalErrorMessage("INVALID_CURRENT_PASSWORD", "현재 비밀번호가 올바르지 않습니다."),
        )
    }

    @Test
    fun `backend message is preserved for an unexpected withdrawal failure`() {
        assertEquals(
            "개인 데이터 삭제에 실패했습니다.",
            withdrawalErrorMessage("VOICE_DATA_DELETION_FAILED", "개인 데이터 삭제에 실패했습니다."),
        )
    }
}
