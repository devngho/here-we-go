package io.github.devngho.herewego.ui.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.devngho.herewego.voice.VoiceState

/** 상단 바용 "읽어 주기" 버튼. 읽는 중에는 "멈추기"로 바뀐다. */
@Composable
fun ReadAloudButton(
    voice: VoiceState,
    onSpeak: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilledTonalButton(
        onClick = if (voice.speaking) onStop else onSpeak,
        enabled = voice.available,
        modifier = modifier,
    ) {
        Icon(
            if (voice.speaking) Icons.Outlined.Stop else Icons.AutoMirrored.Outlined.VolumeUp,
            contentDescription = null,
            modifier = Modifier.size(ButtonDefaults.IconSize),
        )
        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
        Text(if (voice.speaking) "멈추기" else "읽어 주기")
    }
    Spacer(Modifier.size(8.dp))
}
