package com.flintlauncher.ui.view

import android.animation.AnimatorInflater
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.RippleDrawable
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatButton
import androidx.core.content.res.ResourcesCompat
import com.flintlauncher.R
import net.kdt.pojavlaunch.utils.FlintAccentUtils
import net.kdt.pojavlaunch.Tools

open class AnimButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.buttonStyle
) : AppCompatButton(context, attrs, defStyleAttr) {
    init {
        isAllCaps = false
        setRipple()
        stateListAnimator = AnimatorInflater.loadStateListAnimator(context, R.xml.anim_scale)
        translationZ = Tools.dpToPx(4f)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        post {
            pivotX = width / 2f
            pivotY = height / 2f
        }
    }

    private fun setRipple() {
        val rippleDrawable = RippleDrawable(
            ColorStateList.valueOf((FlintAccentUtils.getAccentColor(context) and 0x00FFFFFF) or 0x40000000),
            ResourcesCompat.getDrawable(resources, R.drawable.button_background, context.theme),
            null
        )

        background = rippleDrawable
    }
}