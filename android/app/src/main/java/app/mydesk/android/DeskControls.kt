package app.mydesk.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight

/** Shared form controls keep the same appearance in pages, sheets, and account dialogs. */
@Composable internal fun DeskTextField(
    value: String,onValueChange: (String)->Unit,modifier: Modifier=Modifier,
    enabled: Boolean=true,label: (@Composable ()->Unit)?=null,
    placeholder: (@Composable ()->Unit)?=null,suffix: (@Composable ()->Unit)?=null,trailingIcon: (@Composable ()->Unit)?=null,
    supportingText: (@Composable ()->Unit)?=null,isError: Boolean=false,
    visualTransformation: VisualTransformation=VisualTransformation.None,
    keyboardOptions: KeyboardOptions=KeyboardOptions.Default,
    singleLine: Boolean=false,maxLines: Int=if(singleLine) 1 else Int.MAX_VALUE,minLines: Int=1
) {
    OutlinedTextField(value,onValueChange,modifier=modifier,enabled=enabled,label=label,
        placeholder=placeholder,suffix=suffix,trailingIcon=trailingIcon,supportingText=supportingText,isError=isError,
        visualTransformation=visualTransformation,keyboardOptions=keyboardOptions,
        singleLine=singleLine,maxLines=maxLines,minLines=minLines,shape=RoundedCornerShape(16.dp),
        colors=OutlinedTextFieldDefaults.colors(
            focusedContainerColor=MaterialTheme.colorScheme.surface,
            unfocusedContainerColor=MaterialTheme.colorScheme.surfaceContainerLow,
            unfocusedBorderColor=MaterialTheme.colorScheme.outlineVariant))
}

@Composable internal fun DeskButton(onClick: ()->Unit,modifier: Modifier=Modifier,
    enabled: Boolean=true,content: @Composable RowScope.()->Unit) {
    Button(onClick,modifier=modifier.heightIn(min=48.dp),enabled=enabled,
        shape=RoundedCornerShape(16.dp),contentPadding=PaddingValues(horizontal=20.dp,vertical=12.dp),content=content)
}

@Composable internal fun DeskOutlinedButton(onClick: ()->Unit,modifier: Modifier=Modifier,
    enabled: Boolean=true,content: @Composable RowScope.()->Unit) {
    OutlinedButton(onClick,modifier=modifier.heightIn(min=48.dp),enabled=enabled,
        shape=RoundedCornerShape(16.dp),contentPadding=PaddingValues(horizontal=18.dp,vertical=12.dp),content=content)
}

@Composable internal fun DeskSettingRow(title: String,subtitle: String,onClick: ()->Unit,enabled: Boolean=true,
    icon: androidx.compose.ui.graphics.vector.ImageVector?=null) {
    Surface(onClick=onClick,enabled=enabled,shape=RoundedCornerShape(12.dp),
        color=if(icon==null) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surface,
        modifier=Modifier.fillMaxWidth().heightIn(min=64.dp)) {
        Row(Modifier.padding(horizontal=if(icon==null) 16.dp else 0.dp,vertical=10.dp),
            verticalAlignment=androidx.compose.ui.Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            if(icon!=null) Icon(icon,null,modifier=Modifier.size(24.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text(title,style=MaterialTheme.typography.titleSmall)
                if(subtitle.isNotEmpty()) Text(subtitle,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if(icon!=null) Icon(androidx.compose.material.icons.Icons.AutoMirrored.Filled.KeyboardArrowRight,null,
                tint=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.size(24.dp))
        }
    }
}
