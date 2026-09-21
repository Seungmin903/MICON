package com.micon.app.ui.design.atoms

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.micon.app.ui.design.tokens.MiconShape
import com.micon.app.ui.design.tokens.MiconSpace

/**
 * 입력 필드 — 프로토타입 `.field`. 높이 52 · 반경 16 · 흰 배경 · 1px 테두리, 포커스 시 보라 테두리 + 3px 링.
 * 라벨은 두지 않는다(placeholder 가 라벨). 검색은 [MiconSearchField].
 */
@Composable
fun MiconField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    leading: ImageVector? = null,
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    onImeAction: () -> Unit = {},
    singleLine: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val outline = if (focused) scheme.primary else scheme.outline
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = MiconSpace.fieldHeight)
            .then(if (focused) Modifier.border(3.dp, scheme.primary.copy(alpha = 0.14f), MiconShape.field).padding(2.dp) else Modifier)
            .background(scheme.surface, MiconShape.field)
            .border(1.dp, outline, MiconShape.field)
            .padding(horizontal = MiconSpace.md),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (leading != null) {
                Icon(leading, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(MiconSpace.xs))
            }
            Box(Modifier.weight(1f)) {
                if (value.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant.copy(alpha = 0.8f), maxLines = 1)
                BasicTextField(
                    value = value, onValueChange = onValueChange, singleLine = singleLine,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface),
                    cursorBrush = SolidColor(scheme.primary),
                    visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
                    keyboardActions = KeyboardActions(onAny = { onImeAction() }),
                    modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
                )
            }
            trailing?.invoke()
        }
    }
}

/** 돋보기 아이콘이 달린 검색 필드 */
@Composable
fun MiconSearchField(value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier, onSearch: () -> Unit = {}) =
    MiconField(value, onValueChange, placeholder, modifier, leading = Icons.Outlined.Search, imeAction = ImeAction.Search, onImeAction = onSearch)
