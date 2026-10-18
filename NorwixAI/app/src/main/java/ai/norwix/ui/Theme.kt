package ai.norwix.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.norwix.domain.ThemeMode

private val night = darkColorScheme(
    primary = Color(0xFFC4B5FF), onPrimary = Color(0xFF191126),
    secondary = Color(0xFF9AE5DE), onSecondary = Color(0xFF072D2B),
    background = Color(0xFF090D17), onBackground = Color(0xFFF2F1FA),
    surface = Color(0xFF131A29), onSurface = Color(0xFFF2F1FA),
    surfaceVariant = Color(0xFF222B40), onSurfaceVariant = Color(0xFFB7BBD0),
    outline = Color(0xFF414C65), error = Color(0xFFFFB4BA)
)
private val pearl = lightColorScheme(
    primary = Color(0xFF57449A), onPrimary = Color.White,
    secondary = Color(0xFF176C68), onSecondary = Color.White,
    background = Color(0xFFF1F2F8), onBackground = Color(0xFF14182B),
    surface = Color(0xFFFAFAFE), onSurface = Color(0xFF14182B),
    surfaceVariant = Color(0xFFE5E7F2), onSurfaceVariant = Color(0xFF4E566D),
    outline = Color(0xFF9EA6BC), error = Color(0xFFAE2439)
)

@Composable
fun NorwixTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = mode == ThemeMode.MIDNIGHT || (mode == ThemeMode.SYSTEM && isSystemInDarkTheme())
    MaterialTheme(colorScheme = if (dark) night else pearl,
        shapes = Shapes(small = RoundedCornerShape(14.dp), medium = RoundedCornerShape(22.dp), large = RoundedCornerShape(30.dp)),
        content = content)
}

@Composable
fun GlassPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(26.dp)
    Column(modifier.shadow(14.dp, shape, ambientColor = Color.Black.copy(alpha = .12f), spotColor = Color.Black.copy(alpha = .18f))
        .background(MaterialTheme.colorScheme.surface.copy(alpha = .93f), shape)
        .border(1.dp, Brush.verticalGradient(listOf(MaterialTheme.colorScheme.onSurface.copy(alpha = .14f),
            MaterialTheme.colorScheme.onSurface.copy(alpha = .035f))), shape).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

@Composable
fun AmbientBackground(modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    Canvas(modifier) {
        drawCircle(Brush.radialGradient(listOf(primary.copy(alpha = .09f), Color.Transparent),
            center = Offset(size.width * .9f, size.height * .12f), radius = size.width * .8f),
            radius = size.width * .8f, center = Offset(size.width * .9f, size.height * .12f))
        drawCircle(Brush.radialGradient(listOf(secondary.copy(alpha = .055f), Color.Transparent),
            center = Offset(0f, size.height * .7f), radius = size.width * .7f),
            radius = size.width * .7f, center = Offset(0f, size.height * .7f))
    }
}

@Composable
fun NorwixOrb(reduceMotion: Boolean, modifier: Modifier = Modifier) {
    val pulse = if (reduceMotion) 1f else {
        val transition = rememberInfiniteTransition(label = "Orb breathing")
        transition.animateFloat(.97f, 1.025f,
            infiniteRepeatable(tween(3300, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "Orb scale").value
    }
    Box(modifier.size(156.dp).scale(pulse), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2, size.height / 2)
            drawCircle(Brush.radialGradient(listOf(Color(0xFFAD94FF).copy(alpha = .22f), Color.Transparent), center, size.width / 2),
                size.width / 2, center)
            drawCircle(Brush.radialGradient(listOf(Color(0xFFE4DEFF), Color(0xFFBEA4F6), Color(0xFF6356A0)),
                Offset(size.width * .38f, size.height * .32f), size.width * .64f), size.width * .33f, center)
            drawCircle(Color.White.copy(alpha = .45f), size.width * .065f, Offset(size.width * .39f, size.height * .31f))
            drawCircle(Brush.radialGradient(listOf(Color(0xFFEDFFF9), Color(0xFF85D9D2), Color(0xFF398880)),
                Offset(size.width * .8f, size.height * .65f), size.width * .18f), size.width * .1f,
                Offset(size.width * .8f, size.height * .65f))
            drawCircle(Color(0xFFE0D6FF), size.width * .035f, Offset(size.width * .2f, size.height * .26f))
        }
        Text("N", fontSize = 49.sp, fontWeight = FontWeight.Medium, color = Color(0xFF352A63), letterSpacing = (-3).sp)
    }
}
