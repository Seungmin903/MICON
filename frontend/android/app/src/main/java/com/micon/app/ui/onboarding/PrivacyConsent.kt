package com.micon.app.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.micon.app.ui.common.PrimaryButton
import com.micon.app.ui.theme.Grey
import com.micon.app.ui.theme.GreyLight
import com.micon.app.ui.theme.Ink
import com.micon.app.ui.theme.Purple

/**
 * 개인정보 수집·이용 동의 항목. 전문은 `docs/PRIVACY_CONSENT.md` 를 따르며 여기 문구를 바꾸면 그 문서도 맞춘다.
 *
 * - 회원가입: [PrivacyConsentTerms.signup] 을 체크리스트로 보여주고 필수 항목이 모두 체크돼야 가입한다.
 * - 비회원 온보딩: 계정이 없어 동의를 남길 곳이 없으므로 홀로 분석에 들어올 때마다 [PrivacyConsentTerms.guest] 를 다시 받는다.
 */
data class ConsentTerm(
    val id: String,
    val label: String,
    val required: Boolean,
    val title: String,
    val body: String,
)

object PrivacyConsentTerms {
    val TERMS_OF_SERVICE = ConsentTerm(
        id = "terms", required = true,
        label = "서비스 이용약관에 동의합니다",
        title = "서비스 이용약관",
        body = """
            MICON은 목소리의 음역·음색을 분석해 부를 수 있는 노래와 추천 키를 알려 주고, 연습 중 음정 피드백을 제공하는 서비스입니다.

            1. 회원은 본인의 계정과 목소리로만 서비스를 이용해야 하며, 다른 사람의 목소리를 본인 것처럼 등록하지 않습니다.
            2. 서비스가 제공하는 음원·가사·분석 결과를 무단으로 복제·배포하지 않습니다.
            3. 분석 결과는 노래 선택과 연습을 돕기 위한 참고 정보이며, 서비스는 특정 노래를 부를 수 있음을 보증하지 않습니다.
            4. 서비스는 안정적인 운영을 위해 기능을 추가·변경·중단할 수 있으며, 중요한 변경은 앱 안에서 미리 알립니다.
            5. 회원은 언제든지 앱의 설정에서 탈퇴할 수 있으며, 탈퇴 시 계정 정보와 음성 데이터는 개인정보 수집·이용 동의에서 안내한 대로 삭제됩니다.
        """.trimIndent(),
    )

    val PERSONAL_INFO = ConsentTerm(
        id = "personal", required = true,
        label = "개인정보 수집·이용에 동의합니다",
        title = "개인정보 수집·이용 동의 (계정·서비스 운영)",
        body = """
            수집 항목
            · 이메일 주소, 비밀번호(암호화 저장), 구글 로그인 시 구글 계정 이메일
            · 기기 식별자·기기 이름(로그인 세션 관리)
            · 가입일, 최근 로그인 일시, 설정값
            · 즐겨찾기 곡, 플레이리스트, 선호 장르·가수, 연습 기록(곡·키·점수·일시)

            수집·이용 목적
            · 회원 식별과 로그인, 비밀번호 재설정 메일 발송
            · 기기별 로그인 관리와 비정상 접근 탐지
            · 개인화 추천과 연습 결과·이력 제공

            보유·이용 기간
            · 회원 탈퇴 시까지. 탈퇴하면 지체 없이 삭제하며, 관계 법령에 따라 보존이 필요한 정보는 그 기간 동안만 보관합니다.
            · 탈퇴 후에는 개인을 식별할 수 없는 기록만 통계·감사 목적으로 남깁니다.

            동의를 거부할 권리
            · 동의하지 않을 수 있습니다. 다만 위 항목은 회원가입과 서비스 제공에 꼭 필요한 최소한의 정보이므로, 동의하지 않으면 회원가입이 제한됩니다.
        """.trimIndent(),
    )

    val VOICE_DATA = ConsentTerm(
        id = "voice", required = true,
        label = "음역·음색 분석을 위한 음성 데이터 수집·이용에 동의합니다",
        title = "음성 데이터 수집·이용 동의 (음역·음색 분석)",
        body = """
            수집 항목
            · 음역 측정·음색 측정 중 마이크로 녹음한 음성
            · 음성 분석 결과(최저·최고음, 편안한 음역, 음색 특성, 유사 가수, 음질 판정)
            · 녹음 메타데이터(길이, 샘플레이트, 파일 크기, 해시, 녹음 일시)

            수집·이용 목적
            · 음역대·음색 분석, 부를 수 있는 곡과 추천 키 산출, 실시간 음정 피드백

            보유·이용 기간
            · 녹음 원본: 서버에서 분석을 마치면 즉시 삭제합니다. (원본 보관에 따로 동의한 경우 제외)
            · 분석 결과·메타데이터: 회원 탈퇴 또는 삭제 요청 시까지
            · 연습 녹음은 기기 안에만 저장되며 앱 데이터를 지우면 함께 삭제됩니다.

            안내
            · 마이크는 측정이나 녹음을 시작할 때만 사용하며, 권한을 요청하기 전에 목적을 화면에서 알립니다.
            · 음성은 개인을 식별·인증하는 생체인식 목적으로 사용하지 않습니다.
            · 저장된 녹음과 분석 결과는 설정의 음성 데이터 관리에서 언제든 삭제할 수 있습니다.

            동의를 거부할 권리
            · 동의하지 않을 수 있습니다. 다만 음성 분석은 서비스의 핵심 기능이므로, 동의하지 않으면 회원가입과 서비스 이용이 제한됩니다.
        """.trimIndent(),
    )

    val GUEST_VOICE_DATA = ConsentTerm(
        id = "guest_voice", required = true,
        label = "음역·음색 분석을 위한 음성 데이터 수집·이용에 동의합니다",
        title = "음성 데이터 수집·이용 동의 (비회원 분석)",
        body = """
            로그인 없이 분석할 때는 계정이 없으므로 동의 내용을 저장하지 않고, 분석을 시작할 때마다 다시 여쭤봅니다.

            수집 항목
            · 음역 측정·음색 측정 중 마이크로 녹음한 음성
            · 음성 분석 결과(최저·최고음, 편안한 음역, 음색 특성, 유사 가수, 음질 판정)

            수집·이용 목적
            · 음역대·음색 분석과 부를 수 있는 곡·추천 키 산출

            보유·이용 기간
            · 녹음은 분석을 위해 서버로 전송되며 분석을 마치면 즉시 삭제합니다.
            · 분석 결과는 이 기기의 앱 안에만 남고 서버 계정과 연결되지 않습니다. 로그인하면 결과를 계정으로 옮길 수 없으므로 다시 측정합니다.

            안내
            · 마이크는 측정을 시작할 때만 사용합니다.
            · 음성은 개인을 식별·인증하는 생체인식 목적으로 사용하지 않습니다.

            동의를 거부할 권리
            · 동의하지 않을 수 있습니다. 다만 음성 분석은 서비스의 핵심 기능이므로, 동의하지 않으면 분석을 진행할 수 없습니다.
        """.trimIndent(),
    )

    val KEEP_ORIGINAL = ConsentTerm(
        id = "keep_original", required = false,
        label = "원본 녹음 파일을 서버에 보관하는 데 동의합니다",
        title = "원본 녹음 파일 서버 보관 동의 (선택)",
        body = """
            동의하면 음역·음색 측정에 사용한 원본 녹음 파일을 서버에 보관해, 나중에 다시 듣거나 재분석에 활용할 수 있습니다.

            보관 항목과 목적
            · 원본 녹음 파일: 녹음 다시 듣기, 분석 결과 재산출, 측정 이력 비교

            보유·이용 기간
            · 직접 삭제하거나 회원을 탈퇴할 때까지

            안내
            · 기본값은 꺼짐입니다. 동의하지 않아도 서비스 이용에 제한이 없으며, 원본은 분석 완료 후 삭제됩니다.
            · 언제든지 설정의 원본 녹음 저장에서 끄거나 켤 수 있습니다. 끄면 이후 녹음부터 적용됩니다.
            · 보관 파일은 비공개 저장소(대한민국 서울 리전)에 저장되며, 짧은 시간만 유효한 주소로 본인만 재생할 수 있습니다.

            동의를 거부할 권리
            · 동의하지 않아도 서비스 이용에 불이익이 없습니다.
        """.trimIndent(),
    )

    val MODEL_TRAINING = ConsentTerm(
        id = "training", required = false,
        label = "음성 데이터를 서비스 개선·모델 학습에 이용하는 데 동의합니다",
        title = "서비스 개선·모델 학습 이용 동의 (선택)",
        body = """
            동의하면 음성 데이터와 분석 결과를 서비스 품질 개선과 음성 분석 모델의 성능 향상에 활용합니다.

            이용 항목과 목적
            · 음성 녹음, 음성 분석 결과, 연습 결과: 음역·음색 분석 정확도 개선, 추천 알고리즘 개선, 분석 모델 학습·평가

            보유·이용 기간
            · 동의 철회 또는 회원 탈퇴 시까지. 이미 학습에 반영된 모델 결과물에서 개별 데이터를 분리해 삭제하는 것은 불가능할 수 있습니다.

            안내
            · 학습에 사용할 때는 계정 정보와 분리(가명처리)해 데이터만으로는 누구의 목소리인지 알 수 없도록 합니다.
            · 언제든지 설정에서 철회할 수 있으며, 철회 이후에는 새로운 학습에 사용하지 않습니다.
            · 학습 목적으로 제3자에게 원본 음성을 제공하지 않습니다.

            동의를 거부할 권리
            · 동의하지 않아도 서비스 이용에 불이익이 없습니다.
        """.trimIndent(),
    )

    /** 회원가입 화면 순서 그대로. */
    val signup: List<ConsentTerm> = listOf(TERMS_OF_SERVICE, PERSONAL_INFO, VOICE_DATA, KEEP_ORIGINAL, MODEL_TRAINING)

    /** 비회원 온보딩. 계정이 없으므로 선택 항목은 묻지 않는다. */
    val guest: List<ConsentTerm> = listOf(GUEST_VOICE_DATA)

    fun requiredAccepted(terms: List<ConsentTerm>, accepted: Set<String>): Boolean =
        terms.filter { it.required }.all { it.id in accepted }
}

/** 체크된 항목 id 집합. 화면 회전에는 살아남고 화면을 떠나면 사라진다. */
@Composable
fun rememberAcceptedTerms(): MutableState<Set<String>> = rememberSaveable(
    saver = listSaver(save = { it.value.toList() }, restore = { mutableStateOf(it.toSet()) }),
) { mutableStateOf(emptySet()) }

/**
 * 체크리스트. 전체 동의는 선택 항목까지 켜고, 해제하면 모두 끈다.
 * 각 줄의 "보기"는 전문을 바텀시트로 띄운다. 체크 상태는 호출자가 가진다.
 */
@Composable
fun ConsentChecklist(
    terms: List<ConsentTerm>,
    accepted: Set<String>,
    onAcceptedChange: (Set<String>) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var viewing by remember { mutableStateOf<ConsentTerm?>(null) }
    val allChecked = terms.all { it.id in accepted }
    Column(modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(16.dp)).padding(horizontal = 4.dp, vertical = 6.dp)) {
        if (terms.size > 1) {
            ConsentRow(
                checked = allChecked,
                onCheckedChange = { onAcceptedChange(if (it) terms.map { t -> t.id }.toSet() else emptySet()) },
                label = "전체 동의", emphasized = true, enabled = enabled,
                tag = "consent_all", onView = null,
            )
            HorizontalDivider(Modifier.padding(horizontal = 12.dp), color = GreyLight)
        }
        terms.forEach { term ->
            ConsentRow(
                checked = term.id in accepted,
                onCheckedChange = { onAcceptedChange(if (it) accepted + term.id else accepted - term.id) },
                label = (if (term.required) "[필수] " else "[선택] ") + term.label,
                emphasized = false, enabled = enabled,
                tag = "consent_" + term.id, onView = { viewing = term },
            )
        }
    }
    viewing?.let { term -> ConsentDetailSheet(term, onClose = { viewing = null }) }
}

@Composable
private fun ConsentRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    label: String,
    emphasized: Boolean,
    enabled: Boolean,
    tag: String,
    onView: (() -> Unit)?,
) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled) { onCheckedChange(!checked) }.padding(end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked, onCheckedChange = onCheckedChange, enabled = enabled,
            modifier = Modifier.testTag(tag),
            colors = CheckboxDefaults.colors(checkedColor = Purple),
        )
        Text(
            label, modifier = Modifier.weight(1f).padding(vertical = 8.dp),
            fontSize = if (emphasized) 15.sp else 13.sp, lineHeight = 19.sp,
            fontWeight = if (emphasized) FontWeight.Bold else FontWeight.Normal, color = Ink,
        )
        if (onView != null) {
            Spacer(Modifier.width(4.dp))
            Text("보기", fontSize = 12.sp, color = Grey,
                modifier = Modifier.clickable(onClick = onView).padding(horizontal = 8.dp, vertical = 10.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConsentDetailSheet(term: ConsentTerm, onClose: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color.White) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
            Text(term.title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Ink)
            Spacer(Modifier.height(12.dp))
            Text(term.body, fontSize = 13.5.sp, lineHeight = 21.sp, color = Ink,
                modifier = Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()))
            Spacer(Modifier.height(20.dp))
            PrimaryButton("닫기", onClose, Modifier.fillMaxWidth())
        }
    }
}

/**
 * 비회원 온보딩 동의. 홀로 분석 화면에 들어올 때마다 다시 띄우며, 필수 항목에 동의해야 측정을 시작한다.
 * 체크 상태는 화면 회전에만 살아남고, 화면을 떠나면 사라져서 다음 진입 때 다시 묻는다.
 */
@Composable
fun GuestConsentDialog(onAgree: () -> Unit, onDismiss: () -> Unit) {
    val terms = PrivacyConsentTerms.guest
    var accepted by rememberAcceptedTerms()
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        title = { Text("분석을 시작하기 전에", fontWeight = FontWeight.Bold, color = Ink) },
        text = {
            Column {
                Text("로그인 없이 분석하면 동의 내용을 저장하지 않아서, 분석할 때마다 다시 확인해요.",
                    fontSize = 13.sp, lineHeight = 19.sp, color = Grey)
                Spacer(Modifier.height(12.dp))
                ConsentChecklist(terms, accepted, onAcceptedChange = { accepted = it })
            }
        },
        confirmButton = {
            TextButton(onClick = onAgree, enabled = PrivacyConsentTerms.requiredAccepted(terms, accepted),
                modifier = Modifier.testTag("guest_consent_agree")) { Text("동의하고 시작") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}
