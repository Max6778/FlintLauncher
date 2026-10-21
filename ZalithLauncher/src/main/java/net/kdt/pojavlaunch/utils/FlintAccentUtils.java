package net.kdt.pojavlaunch.utils;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import java.util.WeakHashMap;

public final class FlintAccentUtils {
    private static final int DEFAULT_BLUE = Color.rgb(59, 130, 246);
    private static final int DEFAULT_LIGHT_BLUE = Color.rgb(147, 197, 253);
    private static final WeakHashMap<View, Integer> APPLIED_COLORS = new WeakHashMap<>();

    private FlintAccentUtils() {}

    public static int getAccentColor(Context context) {
        return context.getSharedPreferences("flint_launcher_ui", Context.MODE_PRIVATE)
                .getInt("accent_color", DEFAULT_BLUE);
    }

    public static void applyAccentColor(View root, int accentColor) {
        if (root == null) return;
        int previousColor;
        synchronized (APPLIED_COLORS) {
            Integer previous = APPLIED_COLORS.get(root);
            previousColor = previous == null ? DEFAULT_BLUE : previous;
        }
        if (root instanceof TextView) {
            TextView text = (TextView) root;
            ColorStateList colors = mapColors(text.getTextColors(), previousColor, accentColor);
            if (colors != null) text.setTextColor(colors);
        }
        if (root instanceof ImageView) {
            ImageView image = (ImageView) root;
            ColorStateList tint = mapColors(image.getImageTintList(), previousColor, accentColor);
            if (tint != null) image.setImageTintList(tint);
        }
        if (root instanceof ProgressBar) {
            ProgressBar progress = (ProgressBar) root;
            ColorStateList progressTint = mapColors(progress.getProgressTintList(), previousColor, accentColor);
            ColorStateList indeterminateTint = mapColors(progress.getIndeterminateTintList(), previousColor, accentColor);
            if (progressTint != null) progress.setProgressTintList(progressTint);
            if (indeterminateTint != null) progress.setIndeterminateTintList(indeterminateTint);
        }
        ColorStateList backgroundTint = mapColors(root.getBackgroundTintList(), previousColor, accentColor);
        if (backgroundTint != null) root.setBackgroundTintList(backgroundTint);
        recolorDrawable(root.getBackground(), previousColor, accentColor);

        synchronized (APPLIED_COLORS) {
            APPLIED_COLORS.put(root, accentColor);
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                applyAccentColor(group.getChildAt(i), accentColor);
            }
        }
    }

    private static ColorStateList mapColors(ColorStateList source, int previousColor, int accentColor) {
        if (source == null) return null;
        int current = source.getDefaultColor();
        int mapped = mapColor(current, previousColor, accentColor);
        return mapped == current ? null : ColorStateList.valueOf(mapped);
    }

    private static int mapColor(int color, int previousColor, int accentColor) {
        int rgb = color & 0x00FFFFFF;
        if (rgb == (DEFAULT_BLUE & 0x00FFFFFF)
                || rgb == (DEFAULT_LIGHT_BLUE & 0x00FFFFFF)
                || rgb == (previousColor & 0x00FFFFFF)) {
            return Color.argb(Color.alpha(color), Color.red(accentColor),
                    Color.green(accentColor), Color.blue(accentColor));
        }
        return color;
    }

    private static void recolorDrawable(Drawable drawable, int previousColor, int accentColor) {
        if (drawable == null) return;
        Drawable target = drawable.mutate();
        if (target instanceof ColorDrawable) {
            ColorDrawable colorDrawable = (ColorDrawable) target;
            colorDrawable.setColor(mapColor(colorDrawable.getColor(), previousColor, accentColor));
        } else if (target instanceof GradientDrawable) {
            GradientDrawable gradient = (GradientDrawable) target;
            ColorStateList fill = gradient.getColor();
            if (fill != null) {
                ColorStateList mapped = mapColors(fill, previousColor, accentColor);
                if (mapped != null) gradient.setColor(mapped);
            }
        }
        if (target instanceof RippleDrawable) {
            RippleDrawable ripple = (RippleDrawable) target;
            ColorStateList mapped = mapColors(ripple.getColor(), previousColor, accentColor);
            if (mapped != null) ripple.setColor(mapped);
        }
        if (target instanceof LayerDrawable) {
            LayerDrawable layers = (LayerDrawable) target;
            for (int i = 0; i < layers.getNumberOfLayers(); i++) {
                recolorDrawable(layers.getDrawable(i), previousColor, accentColor);
            }
        } else if (target instanceof InsetDrawable) {
            recolorDrawable(((InsetDrawable) target).getDrawable(), previousColor, accentColor);
        }
    }
}
