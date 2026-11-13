package com.raunakpatil.roniepocket

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.raunakpatil.roniepocket.models.DeviceProfile
import com.raunakpatil.roniepocket.models.Fit
import com.raunakpatil.roniepocket.models.ImageGenTier
import com.raunakpatil.roniepocket.models.ModelStore
import com.raunakpatil.roniepocket.models.Tier
import com.raunakpatil.roniepocket.models.TierChoice
import com.raunakpatil.roniepocket.models.TierOption
import com.raunakpatil.roniepocket.models.TierPolicy
import com.raunakpatil.roniepocket.ui.Ic
import com.raunakpatil.roniepocket.ui.Mood
import com.raunakpatil.roniepocket.ui.PillButton
import com.raunakpatil.roniepocket.ui.R
import com.raunakpatil.roniepocket.ui.StatusPill

/**
 * Pixel-perfect match of the user-provided reference mockup:
 * - Fluid purple-sunset ambient rim/orb lighting in the top-right and lower canvas
 * - Crisp status pills with circular icons and settings gear button
 * - Hardware chip + Storage database icon badges
 * - Sparkle banner with exact 2-line wrapped amber highlight
 * - Amber glowing recommendation card with translucent gradient specs inner bar
 * - Alternative engines (Standard, Lite) with hollow circular radio pickers
 * - All 3 Image Generation options (SANA-Sprint, SD-Turbo, Nitro-E) selectable
 * - Solid high-contrast white download pill CTA with download icon
 */
@Composable
fun OnboardingScreen(choice: TierChoice, onDownload: (Tier, Boolean, Boolean, ImageGenTier) -> Unit) {
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    var selected by remember { mutableStateOf(choice.tier) }

    val ctx = androidx.compose.ui.platform.LocalContext.current
    val devProfile = remember { DeviceProfile.read(ctx) }
    val imageRec = remember { TierPolicy.recommendImageGen(devProfile) }
    var selectedImageTier by remember { mutableStateOf(imageRec.recommendedTier) }
    var downloadDrawing by remember { mutableStateOf(imageRec.isDeviceCapable && selected != Tier.LITE) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF060608))
            // Exact ambient backdrop lighting matching reference
            .drawBehind {
                // Top-right vivid purple-sunset orb
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color(0x55B565A7),
                            Color(0x356C3B9E),
                            Color(0x18341E5C),
                            Color.Transparent
                        ),
                        center = Offset(size.width * 0.95f, size.height * 0.08f),
                        radius = size.width * 0.85f
                    )
                )
                // Mid-right subtle sunset curve glow
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color(0x30E07A5F),
                            Color(0x159B5DE5),
                            Color.Transparent
                        ),
                        center = Offset(size.width * 1.05f, size.height * 0.45f),
                        radius = size.width * 0.7f
                    )
                )
                // Bottom-right gentle violet ambient
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0x226C3B9E), Color.Transparent),
                        center = Offset(size.width * 0.95f, size.height * 0.85f),
                        radius = size.width * 0.6f
                    )
                )
            }
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header: Status Badge (Settings button removed)
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // "PRIVATE · ON-DEVICE" pill
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(percent = 50))
                        .background(Color(0xFF14131A))
                        .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(percent = 50))
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFFF9E3D))
                    )
                    Text(
                        "PRIVATE · ON-DEVICE",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.1.sp,
                        color = Color(0xFFE2E2EA)
                    )
                }
            }

            // Title & Subtitle
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Hey, I'm ",
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Light,
                        color = Color(0xFFE5E5EB)
                    )
                    Text(
                        "Ronie.",
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
                Text(
                    "I live entirely inside your phone. Choose a neural engine to download—once installed, everything stays offline.",
                    fontSize = 14.sp,
                    color = Color(0xFF8E8E9F),
                    lineHeight = 20.sp
                )
            }

            // Device Memory & Storage Capsule Grid
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Device Memory Capsule
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(22.dp))
                        .background(Color(0xFF121219))
                        .border(1.dp, Color(0x24FFFFFF), RoundedCornerShape(22.dp))
                        .padding(14.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Chip Icon Box
                        Box(
                            Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0x18FFFFFF)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("⚡", fontSize = 16.sp)
                        }
                        Column {
                            Text(
                                "DEVICE MEMORY",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.8.sp,
                                color = Color(0xFF88889A)
                            )
                            Text(
                                "${Math.round(devProfile.totalRamBytes / (1024.0 * 1024.0 * 1024.0))} GB",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                "System RAM",
                                fontSize = 11.sp,
                                color = Color(0xFF7E7E90)
                            )
                        }
                    }
                }

                // Free Storage Capsule
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(22.dp))
                        .background(Color(0xFF121219))
                        .border(1.dp, Color(0x24FFFFFF), RoundedCornerShape(22.dp))
                        .padding(14.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Storage Icon Box
                        Box(
                            Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0x18FFFFFF)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("💾", fontSize = 16.sp)
                        }
                        Column {
                            Text(
                                "FREE STORAGE",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.8.sp,
                                color = Color(0xFF88889A)
                            )
                            Text(
                                "%.1f GB".format(devProfile.freeStorageBytes / (1024.0 * 1024.0 * 1024.0)),
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                "Internal Space",
                                fontSize = 11.sp,
                                color = Color(0xFF7E7E90)
                            )
                        }
                    }
                }
            }

            // Sparkle Banner for Real Device Hardware
            val ramGbRounded = Math.round(devProfile.totalRamBytes / (1024.0 * 1024.0 * 1024.0))
            val deviceHardwareDescription = when {
                devProfile.hasVulkan11 -> "$ramGbRounded GB RAM\nand a capable GPU."
                devProfile.hasDotProd -> "$ramGbRounded GB RAM\nand a modern CPU."
                else -> "$ramGbRounded GB RAM."
            }

            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0xFF121219))
                    .border(1.dp, Color(0x22FFFFFF), RoundedCornerShape(18.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("✨", fontSize = 15.sp)
                    Text(
                        buildAnnotatedString {
                            append("Picked for your ${devProfile.model}: ")
                            withStyle(
                                SpanStyle(
                                    color = Color(0xFFFF9E3D),
                                    fontWeight = FontWeight.Bold
                                )
                            ) {
                                append(deviceHardwareDescription)
                            }
                        },
                        fontSize = 12.sp,
                        color = Color(0xFFC7C7D4),
                        lineHeight = 17.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Box(
                        Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .border(1.dp, Color(0x33FFFFFF), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("i", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF8E8E9F))
                    }
                }
            }

            if (choice.tier == null) {
                Text(choice.reason, color = R.text, fontSize = 14.sp)
                return@Column
            }

            // Recommended Model Card (Full Tier)
            val recommendedOpt = choice.options.firstOrNull { it.fit == Fit.RECOMMENDED } ?: choice.options.first()
            val isFullSelected = selected == recommendedOpt.tier

            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(
                        if (isFullSelected) Color(0xFF18151D) else Color(0xFF121219)
                    )
                    .border(
                        1.5.dp,
                        if (isFullSelected) Brush.linearGradient(
                            listOf(Color(0xFFFF9E3D), Color(0xFFE8488A), Color(0xFF7E42A6))
                        ) else Brush.linearGradient(listOf(Color(0x22FFFFFF), Color(0x11FFFFFF))),
                        RoundedCornerShape(24.dp)
                    )
                    .clickable { selected = recommendedOpt.tier }
                    .padding(18.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(7.dp)
                        ) {
                            Box(
                                Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFFFF9E3D))
                            )
                            Text(
                                "RECOMMENDED FOR YOUR PHONE",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.9.sp,
                                color = Color(0xFFFF9E3D)
                            )
                        }

                        // Orange checkmark capsule
                        Box(
                            Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(if (isFullSelected) Color(0xFFFF9E3D) else Color.Transparent)
                                .then(if (!isFullSelected) Modifier.border(1.5.dp, Color(0x44FFFFFF), CircleShape) else Modifier),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isFullSelected) {
                                Icon(Ic.Check, contentDescription = "Selected", tint = Color.Black, modifier = Modifier.size(15.dp))
                            }
                        }
                    }

                    Text(
                        recommendedOpt.tier.label,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )

                    Text(
                        recommendedOpt.tier.tagline,
                        fontSize = 13.sp,
                        color = Color(0xFFB0B0C4)
                    )

                    // Embedded specs capsule
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0x22FFFFFF))
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(32.dp)
                    ) {
                        Column {
                            Text("DOWNLOAD", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF88889C))
                            Text(recommendedOpt.tier.model.sizeLabel, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                        Column {
                            Text("RAM ACTIVE", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF88889C))
                            Text(recommendedOpt.tier.model.ramLabel, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("⚡", fontSize = 12.sp)
                        Text(
                            recommendedOpt.note,
                            fontSize = 12.sp,
                            color = Color(0xFF9E9EAF)
                        )
                    }
                }
            }

            // Alternative Engines Section Title
            Text(
                "ALTERNATIVE ENGINES",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp,
                color = Color(0xFF88889C),
                modifier = Modifier.padding(top = 4.dp)
            )

            // Standard and Lite Cards
            choice.options.filter { it.fit != Fit.RECOMMENDED }.forEach { opt ->
                val isThisSelected = selected == opt.tier
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(22.dp))
                        .background(Color(0xFF121219))
                        .border(
                            1.dp,
                            if (isThisSelected) Color(0xFFFF9E3D) else Color(0x22FFFFFF),
                            RoundedCornerShape(22.dp)
                        )
                        .clickable(enabled = opt.selectable) { selected = opt.tier }
                        .padding(18.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                opt.tier.label,
                                fontSize = 19.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            // Hollow radio circle
                            Box(
                                Modifier
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .background(if (isThisSelected) Color(0xFFFF9E3D) else Color.Transparent)
                                    .then(if (!isThisSelected) Modifier.border(1.5.dp, Color(0x44FFFFFF), CircleShape) else Modifier),
                                contentAlignment = Alignment.Center
                            ) {
                                if (isThisSelected) {
                                    Icon(Ic.Check, contentDescription = "Selected", tint = Color.Black, modifier = Modifier.size(15.dp))
                                }
                            }
                        }

                        Text(
                            opt.tier.tagline,
                            fontSize = 13.sp,
                            color = Color(0xFF9E9EAF)
                        )

                        // Embedded specs capsule
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0x18FFFFFF))
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(32.dp)
                        ) {
                            Column {
                                Text("DOWNLOAD", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF88889C))
                                Text(opt.tier.model.sizeLabel, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            }
                            Column {
                                Text("RAM ACTIVE", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF88889C))
                                Text(opt.tier.model.ramLabel, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            }
                        }

                        Text(
                            opt.note,
                            fontSize = 12.sp,
                            color = Color(0xFF7E7E90)
                        )
                    }
                }
            }

            // Image Generation Section Title
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "IMAGE GENERATION",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.2.sp,
                    color = Color(0xFF88889C)
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Text(
                        if (imageRec.isDeviceCapable) "HARDWARE MATCH" else "LIMITED MEMORY",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp,
                        color = if (imageRec.isDeviceCapable) Color(0xFF26E59A) else Color(0xFFFF9E3D)
                    )
                    Box(
                        Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(if (imageRec.isDeviceCapable) Color(0xFF26E59A) else Color(0xFFFF9E3D))
                    )
                }
            }

            // Flagship Image Gen Container with all 3 Model Options Selectable
            val pick = selected ?: return@Column
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .background(Color(0xFF121219))
                    .border(1.dp, Color(0x22FFFFFF), RoundedCornerShape(22.dp))
                    .padding(18.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        imageRec.headline,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )

                    Text(
                        imageRec.explanation,
                        fontSize = 12.sp,
                        color = Color(0xFF8E8E9F),
                        lineHeight = 17.sp
                    )

                    // Render image model options (SANA-Sprint and SD-Turbo; Nitro-E excluded)
                    imageRec.options.filter { it.tier != ImageGenTier.NITRO_E }.forEach { opt ->
                        val t = opt.tier
                        val isChosen = t == selectedImageTier
                        val isFlagship = t == ImageGenTier.SANA_SPRINT

                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(
                                    if (isChosen && isFlagship) {
                                        Brush.linearGradient(
                                            listOf(Color(0x4000C896), Color(0x25008080), Color(0x18121219))
                                        )
                                    } else if (isChosen) {
                                        Brush.linearGradient(
                                            listOf(Color(0x303B4268), Color(0x1C20283C), Color(0x18121219))
                                        )
                                    } else {
                                        Brush.linearGradient(
                                            listOf(Color(0x10FFFFFF), Color(0x08FFFFFF))
                                        )
                                    }
                                )
                                .border(
                                    1.dp,
                                    if (isChosen && isFlagship) Color(0x4400E5FF)
                                    else if (isChosen) Color(0x448E8EA8)
                                    else Color(0x14FFFFFF),
                                    RoundedCornerShape(16.dp)
                                )
                                .clickable {
                                    if (t.isAvailableNow) {
                                        selectedImageTier = t
                                    }
                                }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            // Model icon badge
                            Box(
                                Modifier
                                    .size(44.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(
                                        if (isFlagship) {
                                            Brush.linearGradient(listOf(Color(0xFF0F3B3B), Color(0xFF08222A)))
                                        } else {
                                            Brush.linearGradient(listOf(Color(0xFF1E202E), Color(0xFF141520)))
                                        }
                                    )
                                    .border(
                                        1.dp,
                                        if (isFlagship) Color(0x4400E5FF) else Color(0x22FFFFFF),
                                        RoundedCornerShape(12.dp)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    if (isFlagship) "✦" else "⚡",
                                    fontSize = 20.sp,
                                    color = if (isFlagship) Color(0xFF6CF2D6) else Color(0xFFC7C7D8)
                                )
                            }

                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(
                                    t.title,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                opt.recommendationBadge?.let { badge ->
                                    Text(
                                        badge,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = if (isFlagship) Color(0xFF4EE2B8) else Color(0xFFFF9E3D)
                                    )
                                }
                                Text(
                                    "${t.resolution} · ${t.stepsLabel} · ${t.qualityMetric} · ${t.downloadSizeLabel}",
                                    fontSize = 11.sp,
                                    color = Color(0xFF8A9A9A),
                                    lineHeight = 15.sp
                                )
                            }

                            // Selection indicator
                            Box(
                                Modifier
                                    .size(20.dp)
                                    .clip(CircleShape)
                                    .background(if (isChosen) (if (isFlagship) Color(0xFF26E59A) else Color(0xFFFF9E3D)) else Color.Transparent)
                                    .then(if (!isChosen) Modifier.border(1.5.dp, Color(0x44FFFFFF), CircleShape) else Modifier),
                                contentAlignment = Alignment.Center
                            ) {
                                if (isChosen) {
                                    Icon(Ic.Check, contentDescription = "Selected", tint = Color.Black, modifier = Modifier.size(13.dp))
                                }
                            }
                        }
                    }

                    // Checkbox for Drawing Brain
                    if (imageRec.isDeviceCapable && pick != Tier.LITE) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { downloadDrawing = !downloadDrawing }
                                .padding(top = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                Modifier
                                    .size(20.dp)
                                    .clip(RoundedCornerShape(5.dp))
                                    .background(if (downloadDrawing) Color(0xFF26E59A) else Color.Transparent)
                                    .border(1.5.dp, if (downloadDrawing) Color(0xFF26E59A) else Color(0x66FFFFFF), RoundedCornerShape(5.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                if (downloadDrawing) {
                                    Icon(Ic.Check, contentDescription = "Checked", tint = Color.Black, modifier = Modifier.size(14.dp))
                                }
                            }
                            Text(
                                "Download drawing brain (${selectedImageTier.downloadSizeLabel})",
                                fontSize = 13.sp,
                                color = Color.White
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(4.dp))

            // Primary Solid White Download CTA Button
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(Color.White)
                    .clickable {
                        if (Build.VERSION.SDK_INT >= 33) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                        onDownload(pick, true, downloadDrawing, selectedImageTier)
                    },
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("↓", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                    Text(
                        "Download ${pick.label} on Wi-Fi · ${pick.model.sizeLabel}",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )
                }
            }

            // Secondary Mobile Data Button
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(Color(0xFF14131A))
                    .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(percent = 50))
                    .clickable { onDownload(pick, false, downloadDrawing, selectedImageTier) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Use mobile data instead",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White
                )
            }

            // Footer disclaimer with info badge
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    Modifier
                        .padding(top = 2.dp)
                        .size(16.dp)
                        .clip(CircleShape)
                        .border(1.dp, Color(0x44FFFFFF), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text("i", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF7E7E90))
                }
                Text(
                    "Only the selected model is downloaded directly from Hugging Face and verified with SHA-256 before launch. You can switch models anytime.",
                    fontSize = 12.sp,
                    color = Color(0xFF7E7E90),
                    lineHeight = 17.sp
                )
            }

            Spacer(Modifier.height(20.dp))
        }
    }
}

/** Download Screen matching the same spatial palette */
@Composable
fun DownloadScreen(tier: Tier, p: ModelStore.Progress, onRetry: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF060608))
            .drawBehind {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0x35B565A7), Color(0x186C3B9E), Color.Transparent),
                        center = Offset(size.width * 0.5f, size.height * 0.35f),
                        radius = size.width * 0.85f
                    )
                )
            }
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Text(
                "Awakening Ronie…",
                fontSize = 28.sp,
                fontWeight = FontWeight.Light,
                letterSpacing = (-0.5).sp,
                color = Color.White
            )

            Text(
                "Downloading ${tier.label} engine (${tier.model.sizeLabel}). You can exit the app anytime—download resumes seamlessly in the background.",
                color = Color(0xFFA5A5BA),
                fontSize = 14.sp,
                lineHeight = 20.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )

            when (p) {
                is ModelStore.Progress.Running -> {
                    val frac = if (p.total > 0) p.done.toFloat() / p.total else 0f
                    Spacer(Modifier.height(12.dp))

                    Text(
                        "%.0f%%".format(frac * 100),
                        fontSize = 80.sp,
                        fontWeight = FontWeight.ExtraLight,
                        letterSpacing = (-2).sp,
                        color = Color.White
                    )

                    com.raunakpatil.roniepocket.ui.GradientBar(frac, R.sunset, height = 8.dp)

                    if (p.verifying) {
                        StatusPill("Verifying SHA-256 signature…", Mood.WORKING)
                    } else {
                        Text(
                            "%.2f of %.2f GB".format(p.done / 1e9, p.total / 1e9),
                            color = Color(0xFF9091A8),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
                is ModelStore.Progress.Waiting -> {
                    StatusPill("Waiting for network…", Mood.SEARCHING)
                    Text(p.reason, color = Color(0xFFA5A5BA), fontSize = 13.sp)
                }
                is ModelStore.Progress.Failed -> {
                    Text(p.message, color = R.red, fontSize = 14.sp)
                    PillButton("Try again", onRetry)
                }
                ModelStore.Progress.Done -> StatusPill("Initializing engine…", Mood.THINKING)
                ModelStore.Progress.Idle -> StatusPill("Connecting to Hugging Face…", Mood.SEARCHING)
            }
        }
    }
}
