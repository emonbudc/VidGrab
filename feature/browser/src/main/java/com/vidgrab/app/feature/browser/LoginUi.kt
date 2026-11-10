package com.vidgrab.app.feature.browser

import androidx.compose.ui.graphics.vector.ImageVector
import com.vidgrab.app.core.common.LoginSite
import com.vidgrab.app.core.designsystem.icon.BrandIcons
import com.vidgrab.app.core.designsystem.icon.QtIcons

internal fun loginIcon(site: LoginSite): ImageVector = when (site.id) {
    "youtube" -> BrandIcons.YouTube
    "instagram" -> BrandIcons.Instagram
    "facebook" -> BrandIcons.Facebook
    "tiktok" -> BrandIcons.TikTok
    else -> QtIcons.Public
}
