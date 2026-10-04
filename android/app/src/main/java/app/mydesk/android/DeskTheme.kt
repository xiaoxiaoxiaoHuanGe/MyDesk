package app.mydesk.android

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val deskShapes=Shapes(extraSmall=RoundedCornerShape(8.dp),small=RoundedCornerShape(12.dp),
    medium=RoundedCornerShape(16.dp),large=RoundedCornerShape(22.dp),extraLarge=RoundedCornerShape(28.dp))
internal val deskTypography=Typography(
    headlineSmall=TextStyle(fontSize=25.sp,lineHeight=34.sp,fontWeight=FontWeight.SemiBold),
    titleMedium=TextStyle(fontSize=18.sp,lineHeight=26.sp,fontWeight=FontWeight.SemiBold),
    titleSmall=TextStyle(fontSize=15.sp,lineHeight=22.sp,fontWeight=FontWeight.Medium),
    bodyLarge=TextStyle(fontSize=16.sp,lineHeight=25.sp),
    bodyMedium=TextStyle(fontSize=14.sp,lineHeight=22.sp),
    bodySmall=TextStyle(fontSize=12.sp,lineHeight=19.sp),
    labelLarge=TextStyle(fontSize=14.sp,lineHeight=20.sp,fontWeight=FontWeight.Medium))

internal fun deskColors(dark: Boolean)=if(dark) darkColorScheme(
    primary=Color(0xFFBCCBDD),onPrimary=Color(0xFF243344),primaryContainer=Color(0xFF354355),onPrimaryContainer=Color(0xFFE1E8F0),
    secondary=Color(0xFFC9C4BD),onSecondary=Color(0xFF33312D),secondaryContainer=Color(0xFF46433E),onSecondaryContainer=Color(0xFFEAE5DE),
    tertiary=Color(0xFFD5C4B3),onTertiary=Color(0xFF3D3025),tertiaryContainer=Color(0xFF534336),onTertiaryContainer=Color(0xFFF0E3D6),
    background=Color(0xFF181A1E),onBackground=Color(0xFFEAE7E2),surface=Color(0xFF22252A),onSurface=Color(0xFFEAE7E2),
    surfaceVariant=Color(0xFF35383D),onSurfaceVariant=Color(0xFFC8C6C2),outline=Color(0xFF96938D),outlineVariant=Color(0xFF44464B),
    surfaceContainer=Color(0xFF282B30),surfaceContainerLow=Color(0xFF22252A),surfaceContainerHigh=Color(0xFF303338),surfaceContainerHighest=Color(0xFF35383D)
) else lightColorScheme(
    primary=Color(0xFF526274),onPrimary=Color.White,primaryContainer=Color(0xFFE2E8EF),onPrimaryContainer=Color(0xFF293749),
    secondary=Color(0xFF655F57),onSecondary=Color.White,secondaryContainer=Color(0xFFEAE5DE),onSecondaryContainer=Color(0xFF39342E),
    tertiary=Color(0xFF756350),onTertiary=Color.White,tertiaryContainer=Color(0xFFF0E5D8),onTertiaryContainer=Color(0xFF493A2D),
    background=Color(0xFFF5F3EF),onBackground=Color(0xFF292D32),surface=Color(0xFFFFFEFB),onSurface=Color(0xFF292D32),
    surfaceVariant=Color(0xFFEAE6E0),onSurfaceVariant=Color(0xFF5D6065),outline=Color(0xFF7C7E82),outlineVariant=Color(0xFFD8D4CD),
    surfaceContainer=Color(0xFFEEEBE5),surfaceContainerLow=Color(0xFFF6F3EE),surfaceContainerHigh=Color(0xFFE8E5DF),surfaceContainerHighest=Color(0xFFE2DFD9)
)

@Composable internal fun DeskHeader(title: String,subtitle: String,connected: Boolean?=null,quoteCredit: String="",onQuoteClick: (()->Unit)?=null) {
    val dark=MaterialTheme.colorScheme.background.luminance() < 0.3f
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))) {
        if(!dark) Image(painterResource(R.drawable.mydesk_paper_header),contentDescription=null,contentScale=ContentScale.Crop,modifier=Modifier.matchParentSize())
        Column(Modifier.fillMaxWidth().padding(22.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(title,modifier=Modifier.fillMaxWidth().semantics {heading()},textAlign=TextAlign.Center,style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.SemiBold)
            if(subtitle.isNotEmpty()) Column(
                Modifier.fillMaxWidth().then(if(onQuoteClick!=null) Modifier.heightIn(min=48.dp).clickable(onClickLabel="查看一言出处",onClick=onQuoteClick) else Modifier),
                verticalArrangement=Arrangement.spacedBy(4.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                Text(subtitle,modifier=Modifier.fillMaxWidth(),textAlign=TextAlign.Center,color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodyMedium)
                if(quoteCredit.isNotEmpty()) Text(quoteCredit,modifier=Modifier.fillMaxWidth(),textAlign=TextAlign.Center,color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.labelSmall)
            }
            if(connected == false) Box(Modifier.fillMaxWidth(),contentAlignment=Alignment.Center) {ConnectionStatus(false)}
        }
    }
}

