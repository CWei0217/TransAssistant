package com.example.myapplication

import android.app.Dialog
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider

/**
 * 字幕模式胶囊菜单：字号/描边宽用滑条；颜色为预设 + RGB 调色。
 * 对话框必须设为 TYPE_APPLICATION_OVERLAY，否则在 Service 环境下易导致异常或焦点错乱、overlay 被系统收掉。
 */
object SubtitleStyleDialogs {

    private fun dialogContext(context: Context): Context =
        ContextThemeWrapper(context, R.style.Theme_MyApplication)

    private fun Dialog.applyOverlayWindowType() {
        val w = window ?: return
        w.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val attrs = w.attributes
            attrs.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            w.attributes = attrs
        }
    }

    private fun MaterialAlertDialogBuilder.showOverlayDialog() {
        val dlg = create()
        dlg.applyOverlayWindowType()
        dlg.show()
    }

    private val presetColors: IntArray = intArrayOf(
        Color.WHITE, Color.BLACK, Color.YELLOW, Color.CYAN,
        0xFFFF9800.toInt(), 0xFFE91E63.toInt(), 0xFF4CAF50.toInt(), 0xFF2196F3.toInt(),
        0xFFFFEB3B.toInt(), 0xFFFFFFFF.toInt(), 0xFF9E9E9E.toInt(), 0xFF795548.toInt()
    )

    fun showFontSizeDialog(context: Context, prefs: SharedPreferences, onApplied: () -> Unit) {
        val dc = dialogContext(context)
        val padding = (16 * dc.resources.displayMetrics.density).toInt()
        val slider = Slider(dc).apply {
            valueFrom = 10f
            valueTo = 30f
            stepSize = 1f
            value = prefs.getFloat("result_font_size", 15f)
        }
        val layout = LinearLayout(dc).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            addView(TextView(dc).apply { text = "字体大小（与全局设置共用）" })
            addView(slider)
        }
        MaterialAlertDialogBuilder(dc)
            .setTitle("字号")
            .setView(layout)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                prefs.edit().putFloat("result_font_size", slider.value).apply()
                onApplied()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showOverlayDialog()
    }

    fun showStrokeToggleDialog(context: Context, prefs: SharedPreferences, onApplied: () -> Unit) {
        val dc = dialogContext(context)
        val check = android.widget.CheckBox(dc).apply {
            text = "启用字体描边"
            isChecked = prefs.getBoolean("subtitle_stroke_enabled", true)
        }
        val padding = (16 * dc.resources.displayMetrics.density).toInt()
        val wrap = LinearLayout(dc).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            addView(check)
        }
        MaterialAlertDialogBuilder(dc)
            .setTitle("描边")
            .setView(wrap)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                prefs.edit().putBoolean("subtitle_stroke_enabled", check.isChecked).apply()
                onApplied()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showOverlayDialog()
    }

    fun showStrokeWidthDialog(context: Context, prefs: SharedPreferences, onApplied: () -> Unit) {
        val dc = dialogContext(context)
        val padding = (16 * dc.resources.displayMetrics.density).toInt()
        val slider = Slider(dc).apply {
            valueFrom = 0f
            valueTo = 8f
            stepSize = 0.5f
            value = prefs.getFloat("subtitle_stroke_width_dp", 2f).coerceIn(0f, 8f)
        }
        val layout = LinearLayout(dc).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            addView(TextView(dc).apply { text = "描边宽度 (dp)，0 为关闭描边" })
            addView(slider)
        }
        MaterialAlertDialogBuilder(dc)
            .setTitle("描边宽度")
            .setView(layout)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                prefs.edit().putFloat("subtitle_stroke_width_dp", slider.value).apply()
                onApplied()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showOverlayDialog()
    }

    fun showTextColorDialog(context: Context, prefs: SharedPreferences, onApplied: () -> Unit) {
        val key = "subtitle_text_color"
        val initial = prefs.getInt(key, Color.WHITE)
        showColorPickerDialog(context, prefs, key, initial, "字色", onApplied)
    }

    fun showStrokeColorDialog(context: Context, prefs: SharedPreferences, onApplied: () -> Unit) {
        val key = "subtitle_stroke_color"
        val initial = prefs.getInt(key, Color.BLACK)
        showColorPickerDialog(context, prefs, key, initial, "描边颜色", onApplied)
    }

    private fun showColorPickerDialog(
        context: Context,
        prefs: SharedPreferences,
        prefKey: String,
        initialColor: Int,
        title: String,
        onApplied: () -> Unit
    ) {
        val dc = dialogContext(context)
        val density = dc.resources.displayMetrics.density
        val padding = (16 * density).toInt()
        var selected = initialColor

        val preview = TextView(dc).apply {
            text = "预览 Aa 字幕"
            textSize = 18f
            setPadding(padding, padding, padding, padding)
            gravity = Gravity.CENTER
            setBackgroundColor(Color.argb(80, 0, 0, 0))
        }
        fun applyPreview() {
            preview.setTextColor(selected)
        }
        applyPreview()

        val grid = LinearLayout(dc).apply { orientation = LinearLayout.VERTICAL }
        var row: LinearLayout? = null
        presetColors.forEachIndexed { i, c ->
            if (i % 4 == 0) {
                row = LinearLayout(dc).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                }
                grid.addView(row)
            }
            val btnSize = (40 * density).toInt()
            val btn = TextView(dc).apply {
                layoutParams = LinearLayout.LayoutParams(btnSize, btnSize).apply {
                    marginEnd = (8 * density).toInt()
                    bottomMargin = (8 * density).toInt()
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(c)
                    setStroke((2 * density).toInt(), Color.WHITE)
                }
                setOnClickListener {
                    selected = c
                    applyPreview()
                }
            }
            row?.addView(btn)
        }

        val r = SeekBar(dc).apply {
            max = 255
            progress = Color.red(initialColor)
        }
        val g = SeekBar(dc).apply {
            max = 255
            progress = Color.green(initialColor)
        }
        val b = SeekBar(dc).apply {
            max = 255
            progress = Color.blue(initialColor)
        }
        selected = initialColor
        applyPreview()
        val updateRgb = {
            selected = Color.rgb(r.progress, g.progress, b.progress)
            applyPreview()
        }
        r.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateRgb()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        g.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateRgb()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        b.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateRgb()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        val scroll = ScrollView(dc).apply {
            val root = LinearLayout(dc).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(padding, padding, padding, padding)
                addView(preview)
                addView(TextView(dc).apply { text = "预设" })
                addView(grid)
                addView(TextView(dc).apply { text = "RGB 调色" })
                addView(TextView(dc).apply { text = "R" })
                addView(r)
                addView(TextView(dc).apply { text = "G" })
                addView(g)
                addView(TextView(dc).apply { text = "B" })
                addView(b)
            }
            addView(root)
        }

        MaterialAlertDialogBuilder(dc)
            .setTitle(title)
            .setView(scroll)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                prefs.edit().putInt(prefKey, selected).apply()
                onApplied()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showOverlayDialog()
    }
}
