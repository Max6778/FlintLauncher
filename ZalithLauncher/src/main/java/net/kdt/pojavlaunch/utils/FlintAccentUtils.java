package net.kdt.pojavlaunch.utils;

import android.content.Context;
import android.app.Dialog;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.ColorStateListDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import com.movtery.zalithlauncher.R;
import com.google.android.material.tabs.TabLayout;
import com.angcyo.tablayout.DslTabLayout;
import androidx.recyclerview.widget.RecyclerView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import java.util.WeakHashMap;

public final class FlintAccentUtils {
    private static final int DEFAULT_BLUE = Color.rgb(59, 130, 246);
    private static final int DEFAULT_LIGHT_BLUE = Color.rgb(147, 197, 253);
    private static final int[] DARK_SURFACES = {
            Color.rgb(10, 10, 12), Color.rgb(17, 17, 21), Color.rgb(24, 24, 30),
            Color.rgb(30, 30, 38), Color.rgb(35, 35, 46), Color.rgb(46, 46, 61)
    };
    private static final float[] DARK_SURFACE_BRIGHTNESS = {0.085f, 0.12f, 0.16f, 0.19f, 0.22f, 0.27f};
    private static final int[] EXTRA_DARK_ACCENT_SURFACES = {
            Color.rgb(26, 26, 46), Color.rgb(46, 74, 126), Color.rgb(30, 58, 110)
    };
    private static final int[] EXTRA_DARK_SURFACE_INDEX = {2, 4, 3};
    private static final int[] LIGHT_ACCENT_SURFACES = {
            Color.rgb(246, 247, 251), Color.WHITE,
            Color.rgb(240, 242, 247), Color.rgb(231, 234, 241),
            Color.rgb(217, 222, 232), Color.rgb(197, 204, 218),
            Color.rgb(233, 236, 242), Color.rgb(226, 230, 239),
            Color.rgb(230, 234, 242), Color.rgb(203, 215, 232), Color.rgb(184, 203, 232)
    };
    private static final float[] LIGHT_ACCENT_BLEND = {
            0.04f, 0.03f, 0.07f, 0.10f, 0.14f, 0.18f,
            0.09f, 0.12f, 0.09f, 0.15f, 0.21f
    };
    private static final WeakHashMap<View, Integer> APPLIED_COLORS = new WeakHashMap<>();
    private static final WeakHashMap<RecyclerView, Boolean> RECYCLER_LISTENERS = new WeakHashMap<>();

    private FlintAccentUtils() {}

    public static int getAccentColor(Context context) {
        return context.getSharedPreferences("flint_launcher_ui", Context.MODE_PRIVATE)
                .getInt("accent_color", DEFAULT_BLUE);
    }

    public static int getAccessibleAccentColor(int accentColor, int backgroundColor) {
        if (contrastRatio(accentColor, backgroundColor) >= 3.0) return accentColor;
        float[] hsv = new float[3];
        Color.colorToHSV(accentColor, hsv);
        boolean lightBackground = luminance(backgroundColor) > 0.5;
        for (int i = 0; i < 50; i++) {
            hsv[2] = Math.max(0f, Math.min(1f, hsv[2] + (lightBackground ? -0.02f : 0.02f)));
            int adjusted = Color.HSVToColor(Color.alpha(accentColor), hsv);
            if (contrastRatio(adjusted, backgroundColor) >= 3.0) return adjusted;
        }
        return lightBackground ? Color.BLACK : Color.WHITE;
    }

    public static void applyAccentColor(View root, int accentColor) {
        if (root == null) return;
        boolean darkMode = (root.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        int previousColor;
        synchronized (APPLIED_COLORS) {
            Integer previous = APPLIED_COLORS.get(root);
            previousColor = previous == null ? DEFAULT_BLUE : previous;
        }
        if (root instanceof TextView) {
            TextView text = (TextView) root;
            ColorStateList colors = mapAccentColors(text.getTextColors(), previousColor, accentColor);
            if (colors != null) text.setTextColor(colors);
            if (text.getId() == R.id.play_button) {
                text.setTextColor(contrastTextColor(accentColor));
            }
        }
        if (root instanceof ImageView) {
            ImageView image = (ImageView) root;
            ColorStateList tint = mapAccentColors(image.getImageTintList(), previousColor, accentColor);
            if (tint != null) image.setImageTintList(tint);
            if (image.getId() == R.id.favorite) {
                image.setImageTintList(ColorStateList.valueOf(accentColor));
            }
            if (image.getId() == R.id.download_button || image.getId() == R.id.donate_button
                    || image.getId() == R.id.setting_button || image.getId() == R.id.progress_flip_arrow
                    || image.getId() == R.id.controller_mapper_expand_button) {
                int surface = Color.WHITE;
                if (image.getParent() instanceof View) {
                    Drawable parentBackground = ((View) image.getParent()).getBackground();
                    if (parentBackground instanceof ColorDrawable) {
                        surface = ((ColorDrawable) parentBackground).getColor();
                    } else {
                        surface = image.getContext().getColor(R.color.background_menu_top);
                    }
                }
                image.setImageTintList(ColorStateList.valueOf(getAccessibleAccentColor(accentColor, surface)));
            }
        }
        if (root instanceof CompoundButton) {
            CompoundButton button = (CompoundButton) root;
            ColorStateList buttonTint = mapAccentColors(button.getButtonTintList(), previousColor, accentColor);
            if (buttonTint != null) button.setButtonTintList(buttonTint);
        }
        if (root instanceof TabLayout) {
            TabLayout tabs = (TabLayout) root;
            tabs.setSelectedTabIndicatorColor(accentColor);
            tabs.setTabRippleColor(ColorStateList.valueOf(withAlpha(accentColor, 0x40)));
        }
        if (root instanceof DslTabLayout) {
            ((DslTabLayout) root).getTabIndicator().setIndicatorColor(accentColor);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tintScrollbar(root, true, accentColor);
            tintScrollbar(root, false, accentColor);
        }
        if (root instanceof ProgressBar) {
            ProgressBar progress = (ProgressBar) root;
            ColorStateList progressTint = mapAccentColors(progress.getProgressTintList(), previousColor, accentColor);
            ColorStateList indeterminateTint = mapAccentColors(progress.getIndeterminateTintList(), previousColor, accentColor);
            if (progressTint != null) progress.setProgressTintList(progressTint);
            if (indeterminateTint != null) progress.setIndeterminateTintList(indeterminateTint);
        }
        ColorStateList backgroundTint = mapAccentColors(root.getBackgroundTintList(), previousColor, accentColor);
        if (backgroundTint != null) root.setBackgroundTintList(backgroundTint);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            ColorStateList foregroundTint = mapAccentColors(root.getForegroundTintList(), previousColor, accentColor);
            if (foregroundTint != null) root.setForegroundTintList(foregroundTint);
        }
        recolorDrawable(root.getBackground(), previousColor, accentColor, darkMode);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            recolorDrawable(root.getForeground(), previousColor, accentColor, darkMode);
        }

        synchronized (APPLIED_COLORS) {
            APPLIED_COLORS.put(root, accentColor);
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            if (group instanceof RecyclerView) {
                RecyclerView recyclerView = (RecyclerView) group;
                boolean register;
                synchronized (RECYCLER_LISTENERS) {
                    register = !RECYCLER_LISTENERS.containsKey(recyclerView);
                    if (register) RECYCLER_LISTENERS.put(recyclerView, Boolean.TRUE);
                }
                if (register) {
                    recyclerView.addOnChildAttachStateChangeListener(new RecyclerView.OnChildAttachStateChangeListener() {
                        @Override
                        public void onChildViewAttachedToWindow(View child) {
                            applyAccentColor(child, getAccentColor(child.getContext()));
                        }

                        @Override
                        public void onChildViewDetachedFromWindow(View child) {}
                    });
                }
            }
            for (int i = 0; i < group.getChildCount(); i++) {
                applyAccentColor(group.getChildAt(i), accentColor);
            }
        }
    }

    private static void tintScrollbar(View view, boolean vertical, int accentColor) {
        Drawable thumb = vertical ? view.getVerticalScrollbarThumbDrawable()
                : view.getHorizontalScrollbarThumbDrawable();
        if (thumb == null) return;
        thumb = thumb.mutate();
        thumb.setTint(accentColor);
        if (vertical) view.setVerticalScrollbarThumbDrawable(thumb);
        else view.setHorizontalScrollbarThumbDrawable(thumb);
    }

    public static void applyAccentToDialog(Dialog dialog) {
        if (dialog == null || dialog.getWindow() == null) return;
        View decor = dialog.getWindow().getDecorView();
        decor.post(() -> applyAccentColor(decor, getAccentColor(dialog.getContext())));
    }

    private static ColorStateList mapAccentColors(ColorStateList source, int previousColor, int accentColor) {
        if (source == null) return null;
        int[] colors = source.getColors();
        int[][] states = source.getStates();
        int[] mappedColors = new int[colors.length];
        boolean changed = false;
        for (int i = 0; i < colors.length; i++) {
            mappedColors[i] = mapAccentColor(colors[i], previousColor, accentColor);
            changed |= mappedColors[i] != colors[i];
        }
        return changed ? new ColorStateList(states, mappedColors) : null;
    }

    private static int mapAccentColor(int color, int previousColor, int accentColor) {
        int rgb = color & 0x00FFFFFF;
        if (rgb == (DEFAULT_BLUE & 0x00FFFFFF)
                || rgb == (DEFAULT_LIGHT_BLUE & 0x00FFFFFF)
                || rgb == (previousColor & 0x00FFFFFF)) {
            return withAlpha(color, accentColor);
        }
        return color;
    }

    private static void recolorDrawable(Drawable drawable, int previousColor, int accentColor, boolean darkMode) {
        if (drawable == null) return;
        Drawable target = drawable.mutate();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && target instanceof ColorStateListDrawable) {
            ColorStateListDrawable colorStates = (ColorStateListDrawable) target;
            ColorStateList mapped = mapDrawableColors(
                    colorStates.getColorStateList(), previousColor, accentColor, darkMode);
            if (mapped != null) colorStates.setColorStateList(mapped);
        } else if (target instanceof RippleDrawable) {
            RippleDrawable ripple = (RippleDrawable) target;
            ripple.setColor(ColorStateList.valueOf(withAlpha(accentColor, 0x40)));
            for (int i = 0; i < ripple.getNumberOfLayers(); i++) {
                recolorDrawable(ripple.getDrawable(i), previousColor, accentColor, darkMode);
            }
        } else if (target instanceof StateListDrawable) {
            StateListDrawable states = (StateListDrawable) target;
            for (int i = 0; i < states.getStateCount(); i++) {
                recolorDrawable(states.getStateDrawable(i), previousColor, accentColor, darkMode);
            }
        } else if (target instanceof ColorDrawable) {
            ColorDrawable colorDrawable = (ColorDrawable) target;
            colorDrawable.setColor(mapDrawableColor(
                    colorDrawable.getColor(), previousColor, accentColor, darkMode));
        } else if (target instanceof GradientDrawable) {
            GradientDrawable gradient = (GradientDrawable) target;
            ColorStateList fill = gradient.getColor();
            if (fill != null) {
                ColorStateList mapped = mapDrawableColors(fill, previousColor, accentColor, darkMode);
                if (mapped != null) gradient.setColor(mapped);
            }
        }
        if (target instanceof LayerDrawable) {
            LayerDrawable layers = (LayerDrawable) target;
            for (int i = 0; i < layers.getNumberOfLayers(); i++) {
                recolorDrawable(layers.getDrawable(i), previousColor, accentColor, darkMode);
            }
        } else if (target instanceof InsetDrawable) {
            recolorDrawable(((InsetDrawable) target).getDrawable(), previousColor, accentColor, darkMode);
        }
    }

    private static ColorStateList mapDrawableColors(ColorStateList source, int previousColor,
                                                     int accentColor, boolean darkMode) {
        if (source == null) return null;
        int[] colors = source.getColors();
        int[][] states = source.getStates();
        int[] mappedColors = new int[colors.length];
        boolean changed = false;
        for (int i = 0; i < colors.length; i++) {
            mappedColors[i] = mapDrawableColor(colors[i], previousColor, accentColor, darkMode);
            changed |= mappedColors[i] != colors[i];
        }
        return changed ? new ColorStateList(states, mappedColors) : null;
    }

    private static int mapDrawableColor(int color, int previousColor, int accentColor, boolean darkMode) {
        int rgb = color & 0x00FFFFFF;
        if (darkMode) {
            for (int i = 0; i < DARK_SURFACES.length; i++) {
                if (rgb == (DARK_SURFACES[i] & 0x00FFFFFF)
                        || rgb == (darkSurfaceColor(previousColor, i) & 0x00FFFFFF)) {
                    return withAlpha(color, darkSurfaceColor(accentColor, i));
                }
            }
            for (int i = 0; i < EXTRA_DARK_ACCENT_SURFACES.length; i++) {
                int index = EXTRA_DARK_SURFACE_INDEX[i];
                if (rgb == (EXTRA_DARK_ACCENT_SURFACES[i] & 0x00FFFFFF)
                        || rgb == (darkSurfaceColor(previousColor, index) & 0x00FFFFFF)) {
                    return withAlpha(color, darkSurfaceColor(accentColor, index));
                }
            }
        } else {
            for (int i = 0; i < LIGHT_ACCENT_SURFACES.length; i++) {
                if (rgb == (LIGHT_ACCENT_SURFACES[i] & 0x00FFFFFF)
                        || rgb == (lightAccentSurfaceColor(previousColor, i) & 0x00FFFFFF)) {
                    return withAlpha(color, lightAccentSurfaceColor(accentColor, i));
                }
            }
        }
        return mapAccentColor(color, previousColor, accentColor);
    }

    private static int darkSurfaceColor(int accentColor, int index) {
        float[] hsv = new float[3];
        Color.colorToHSV(accentColor, hsv);
        if (hsv[1] < 0.08f) {
            int gray = Math.round(DARK_SURFACE_BRIGHTNESS[index] * 255f);
            return Color.rgb(gray, gray, gray);
        }
        hsv[1] = Math.max(0.42f, Math.min(hsv[1], 0.78f));
        hsv[2] = DARK_SURFACE_BRIGHTNESS[index];
        return Color.HSVToColor(hsv);
    }

    private static int lightAccentSurfaceColor(int accentColor, int index) {
        float amount = LIGHT_ACCENT_BLEND[index];
        return Color.rgb(
                Math.round(255 + (Color.red(accentColor) - 255) * amount),
                Math.round(255 + (Color.green(accentColor) - 255) * amount),
                Math.round(255 + (Color.blue(accentColor) - 255) * amount));
    }

    private static int contrastTextColor(int backgroundColor) {
        double red = linear(Color.red(backgroundColor) / 255.0);
        double green = linear(Color.green(backgroundColor) / 255.0);
        double blue = linear(Color.blue(backgroundColor) / 255.0);
        double luminance = 0.2126 * red + 0.7152 * green + 0.0722 * blue;
        return luminance > 0.42 ? Color.BLACK : Color.WHITE;
    }

    private static double linear(double channel) {
        return channel <= 0.04045 ? channel / 12.92 : Math.pow((channel + 0.055) / 1.055, 2.4);
    }

    private static double luminance(int color) {
        return 0.2126 * linear(Color.red(color) / 255.0)
                + 0.7152 * linear(Color.green(color) / 255.0)
                + 0.0722 * linear(Color.blue(color) / 255.0);
    }

    private static double contrastRatio(int first, int second) {
        double a = luminance(first);
        double b = luminance(second);
        return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
    }

    private static int withAlpha(int source, int replacement) {
        return Color.argb(Color.alpha(source), Color.red(replacement),
                Color.green(replacement), Color.blue(replacement));
    }
}
