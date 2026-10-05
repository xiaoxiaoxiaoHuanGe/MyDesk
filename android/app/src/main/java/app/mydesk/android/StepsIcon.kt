package app.mydesk.android

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

internal val StepsIcon=ImageVector.Builder(name="Steps",defaultWidth=24.dp,defaultHeight=24.dp,viewportWidth=24f,viewportHeight=24f).apply {
    path(fill=SolidColor(Color.Black)) {
        moveTo(8f,2f);curveTo(5f,2f,4f,5f,5f,8f);curveTo(5.6f,10f,7.4f,12f,9f,11f)
        curveTo(10.6f,10f,10f,7f,10f,5f);curveTo(10f,3f,9f,2f,8f,2f);close()
        moveTo(7f,13f);curveTo(5f,13f,5f,16f,7f,17f);curveTo(9f,18f,11f,16f,10f,14f);close()
        moveTo(16f,7f);curveTo(13f,7f,12f,10f,13f,13f);curveTo(13.6f,15f,15.4f,17f,17f,16f)
        curveTo(18.6f,15f,18f,12f,18f,10f);curveTo(18f,8f,17f,7f,16f,7f);close()
        moveTo(15f,18f);curveTo(13f,18f,13f,21f,15f,22f);curveTo(17f,23f,19f,21f,18f,19f);close()
    }
}.build()
