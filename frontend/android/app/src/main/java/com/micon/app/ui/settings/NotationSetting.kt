package com.micon.app.ui.settings

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.micon.app.ui.design.molecules.NoteNames
import com.micon.app.ui.theme.Grey
import com.micon.app.ui.theme.Ink
import com.micon.app.ui.theme.Purple

private const val PREFS = "micon-settings"
private const val KEY_NOTATION = "notation"

/** 앱이 시작할 때 저장해 둔 음 이름 표기를 되살린다. [com.micon.app.MiconApplication] 에서 한 번 부른다 */
fun restoreNotation(context: Context) {
    val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_NOTATION, null) ?: return
    runCatching { NoteNames.setNotation(NoteNames.Notation.valueOf(raw)) }
}

/**
 * 음 이름 표기(도레미 ↔ CDE). 눌러서 바꾸면 앱 전체 표기가 따라 바뀌고 기기에 남는다.
 * 설정 메뉴 행과 같은 규격이되, 오른쪽에 화살표 대신 지금 값을 보여 준다.
 */
@Composable
fun NotationSettingRow() {
    val context = LocalContext.current
    val notation by NoteNames.notation.collectAsStateWithLifecycle()
    Row(
        Modifier
            .fillMaxWidth()
            .clickable {
                val next = if (notation == NoteNames.Notation.SOLFEGE) NoteNames.Notation.LETTERS else NoteNames.Notation.SOLFEGE
                NoteNames.setNotation(next)
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_NOTATION, next.name).apply()
            }
            .padding(vertical = 17.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.MusicNote, null, Modifier.size(23.dp), tint = Ink)
        Spacer(Modifier.width(16.dp))
        Text("음 이름 표기", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Ink, modifier = Modifier.weight(1f))
        Text(notation.label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Purple)
    }
}
