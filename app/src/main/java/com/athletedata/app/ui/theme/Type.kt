package com.athletedata.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val TypographyTitle = TextStyle(
    fontFamily    = FontFamily.Default,
    fontWeight    = FontWeight.Medium,
    fontSize      = 13.sp,
    letterSpacing = 0.26.sp   // 0.02em × 13sp
)

val TypographyValue = TextStyle(
    fontFamily    = FontFamily.Default,
    fontWeight    = FontWeight.Normal,
    fontSize      = 28.sp,
    letterSpacing = 0.sp
)

val TypographyMeta = TextStyle(
    fontFamily    = FontFamily.Default,
    fontWeight    = FontWeight.Normal,
    fontSize      = 12.sp,
    letterSpacing = 0.sp
)

val Typography = Typography(
    titleSmall   = TypographyTitle,
    displayLarge = TypographyValue,
    labelSmall   = TypographyMeta
)
