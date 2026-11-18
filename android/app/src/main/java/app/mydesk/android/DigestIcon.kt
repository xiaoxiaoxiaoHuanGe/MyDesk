package app.mydesk.android

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

internal val DigestIcon = ImageVector.Builder("Digest",24.dp,24.dp,24f,24f).apply {
    path(fill=SolidColor(Color.Black)) {
        moveTo(4f,4f); lineTo(20f,4f); lineTo(20f,20f); lineTo(4f,20f); close()
        moveTo(6f,6f); lineTo(6f,18f); lineTo(18f,18f); lineTo(18f,6f); close()
        moveTo(8f,8f); lineTo(16f,8f); lineTo(16f,10f); lineTo(8f,10f); close()
        moveTo(8f,12f); lineTo(11f,12f); lineTo(11f,16f); lineTo(8f,16f); close()
        moveTo(12f,12f); lineTo(16f,12f); lineTo(16f,13.5f); lineTo(12f,13.5f); close()
        moveTo(12f,14.5f); lineTo(16f,14.5f); lineTo(16f,16f); lineTo(12f,16f); close()
    }
}.build()
