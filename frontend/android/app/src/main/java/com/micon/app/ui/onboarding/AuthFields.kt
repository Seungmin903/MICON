package com.micon.app.ui.onboarding

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.micon.app.ui.theme.Grey
import com.micon.app.ui.theme.Ink
import com.micon.app.ui.theme.Purple

/**
 * 로그인·가입 화면이 쓰던 입력·버튼. 로그인·가입 자체는 [AuthScreens] 의 리디자인으로 옮겼지만,
 * 비밀번호 변경 화면(설정)이 아직 이 규격을 쓰고 있어 남겨 둔다.
 */
@Composable
internal fun AuthPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(56.dp),
        shape = RoundedCornerShape(28.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Purple,
            contentColor = Color.White,
            disabledContainerColor = Color(0xFFD9D5EA),
            disabledContentColor = Color(0xFF7C7796),
        ),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 3.dp),
    ) {
        Text(text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
internal fun AuthTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    isPassword: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    helper: String? = null,
    helperColor: Color = Grey,
) {
    Column(modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            placeholder = { Text(placeholder, color = Grey, fontSize = 15.sp) },
            singleLine = true,
            textStyle = TextStyle(fontSize = 15.sp, color = Ink),
            shape = RoundedCornerShape(16.dp),
            visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = if (isPassword) KeyboardType.Password else keyboardType),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Purple,
                unfocusedBorderColor = Color(0xFFE4E2EE),
                focusedContainerColor = Color.White,
                unfocusedContainerColor = Color.White,
                cursorColor = Purple,
            ),
        )
        helper?.let {
            Text(it, fontSize = 12.sp, color = helperColor, modifier = Modifier.padding(start = 16.dp, top = 6.dp))
        }
    }
}
