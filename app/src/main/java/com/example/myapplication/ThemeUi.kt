package com.example.myapplication

import android.content.Context
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt

fun Context.themeColor(@AttrRes attr: Int): Int {
    val a = obtainStyledAttributes(intArrayOf(attr))
    @ColorInt val c = a.getColor(0, 0)
    a.recycle()
    return c
}
