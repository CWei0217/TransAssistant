package com.example.myapplication

import android.content.Context
import androidx.annotation.StyleRes
import androidx.appcompat.app.AppCompatActivity

/**
 * 全局配色包（浅色/深色由资源 night 限定符与系统深浅模式共同决定）。
 * 经典 = 原有暖色稿；墨井大地 = Inkwell 四色；蜜壤釉彩 = Darlington / Beeswax / Grenadine / Cafe Latte；波普撞色 = 正红 / 青灰 / 奶油黄 / 海军蓝；绯棉深调 = 棉白 / 樱桃红 / 栗褐 / 墨黑；麦野金调 = 亚麻黄 / 金属金 / 鼠尾草 / 墨绿 / 灰褐；海港蓝调 = 真海军 / 蓝门钢蓝 / 哈珀蓝 / 爱丽丝蓝。
 */
object ThemeColorPack {

    const val KEY_UI_THEME_PACK = "ui_theme_color_pack"
    /** @deprecated 旧版 OCR 四色强调，已废弃，读取时会清除 */
    private const val LEGACY_KEY_ACCENT_PALETTE = "ui_accent_palette"

    const val PACK_CLASSIC = 0
    const val PACK_INKWELL = 1
    const val PACK_MEADOW = 2
    const val PACK_POP = 3
    const val PACK_VELVET = 4
    const val PACK_FLAX = 5
    const val PACK_HARBOR = 6

    fun migrateLegacyPrefs(context: Context) {
        val p = context.getSharedPreferences(ThemeAppearance.PREFS_NAME, Context.MODE_PRIVATE)
        if (!p.contains(LEGACY_KEY_ACCENT_PALETTE)) return
        p.edit().remove(LEGACY_KEY_ACCENT_PALETTE).apply()
    }

    fun currentPackId(context: Context): Int {
        migrateLegacyPrefs(context)
        return context.getSharedPreferences(ThemeAppearance.PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_UI_THEME_PACK, PACK_CLASSIC)
    }

    fun persistPack(context: Context, packId: Int) {
        context.getSharedPreferences(ThemeAppearance.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_UI_THEME_PACK, packId)
            .apply()
    }

    @StyleRes
    fun overlayStyleRes(packId: Int): Int? = when (packId) {
        PACK_INKWELL -> R.style.ThemeOverlayPackInkwell
        PACK_MEADOW -> R.style.ThemeOverlayPackMeadow
        PACK_POP -> R.style.ThemeOverlayPackPop
        PACK_VELVET -> R.style.ThemeOverlayPackVelvet
        PACK_FLAX -> R.style.ThemeOverlayPackFlax
        PACK_HARBOR -> R.style.ThemeOverlayPackHarbor
        else -> null
    }

    fun applyPackOverlay(activity: AppCompatActivity) {
        val resId = overlayStyleRes(currentPackId(activity)) ?: return
        activity.theme.applyStyle(resId, true)
    }
}
