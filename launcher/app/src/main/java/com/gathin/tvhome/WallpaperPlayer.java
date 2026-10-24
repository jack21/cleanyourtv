package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.TransitionDrawable;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.File;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Displays and rotates a theme's local wallpaper images while Home is visible. */
final class WallpaperPlayer {
    private final FrameLayout root;
    private final ImageView image;
    private final List<File> files;
    private final long interval;
    private final String order;
    private final SharedPreferences preferences;
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private final Random random = new Random();
    private final int width;
    /** config wallpaper_true_color: ARGB_8888 instead of RGB_565. */
    private final Bitmap.Config bitmapConfig;
    private final int height;
    /** wallpaper_dim as an ARGB black, painted into each picture once instead of a full-screen shade view. */
    private final int dimColor;
    /** The picture on screen, released once the next one has been drawn. */
    private Bitmap current;
    private boolean active;
    private boolean closed;
    private boolean loading;
    private String displayed = "";
    /** wallpaper_captions: the shown picture's name from wallpapers/captions.txt; null when off or there are none. */
    private final TextView caption;
    private Map<String, String> names;
    /** wallpaper_live: the folder looked into again every minute, and the captions.txt time last read; null when off. */
    private final File liveDir;
    private final boolean captionsOn;
    private long captionsStamp = -1;
    /**
     * Pictures that failed to decode, with their file time: wallpaper_live would otherwise find them again every
     * minute. A picture written again (a new time) gets another try.
     */
    private final Map<String, Long> broken = new HashMap<>();
    /** The clock screensaver: cross-fade between pictures; Home switches at once as it always has. */
    private final boolean fade;
    /** wallpaper_captions_at_end on Home: the caption shows only while the grid is scrolled to its end. */
    private boolean revealed = true;
    private static final int FADE_MS = 1500;
    /** wallpaper_moods: the collection's groups by time of day; empty rotates through all of it. */
    private final Map<String, Set<String>> moods;
    /** config.xml latitude and longitude for the sun's times, and today's {sunrise, sunset} once worked out. */
    private final String latitude, longitude;
    private LocalDate sunDay;
    private int[] sun;

    WallpaperPlayer(Context context, FrameLayout root, Config config, boolean fade) {
        this.root = root;
        this.fade = fade;
        files = new ArrayList<>(config.wallpapers);
        liveDir = config.wallpaperLiveDir;
        interval = config.wallpaperRotationMinutes * 60_000L;
        bitmapConfig = config.wallpaperTrueColor ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565;
        // The dim is painted into the picture (decode), so no full-screen shade layer is drawn on every frame.
        dimColor = config.wallpaperDim > 0 ? (config.wallpaperDim * 255 / 100) << 24 : 0;
        order = config.wallpaperOrder;
        preferences = context.getSharedPreferences("wallpaper_rotation", Context.MODE_PRIVATE);
        moods = config.wallpaperMoods;
        latitude = moods.isEmpty() ? "" : context.getString(R.string.latitude);
        longitude = moods.isEmpty() ? "" : context.getString(R.string.longitude);
        // Playback state belongs to one collection. Changing the collection starts from its first
        // picture instead of resuming a filename that belonged to the previous one.
        // A live collection changes all the time; it keeps its place, and pictures already there count as seen.
        String collection = liveDir != null ? "live" : collectionKey(files);
        if (liveDir != null && !preferences.contains("live_known")) {
            Set<String> known = new HashSet<>();
            for (File file : files) known.add(file.getName());
            preferences.edit().putStringSet("live_known", known).apply();
        }
        if (!collection.equals(preferences.getString("collection", ""))) {
            preferences.edit().putString("collection", collection)
                    .remove("image").remove("changed").apply();
        }
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        width = metrics.widthPixels;
        height = metrics.heightPixels;
        image = new ImageView(context);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        captionsOn = config.wallpaperCaptions && config.wallpaperEnabled;
        names = captionsOn && !files.isEmpty()
                ? WallpaperCaptions.read(files.get(0).getParentFile()) : Collections.emptyMap();
        caption = names.isEmpty() && (liveDir == null || !captionsOn) ? null
                : makeCaption(context, root, config.wallpaperCaptionsAtEnd);
        if (config.wallpaperCaptionsAtEnd && !fade) revealed = false;
        if (config.wallpaperEnabled && (!files.isEmpty() || liveDir != null)) {
            root.addView(image, 0, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            showFirst();
        }
    }

    /**
     * The picture Home opens with is decoded right here, on the main thread, as the single wallpaper always was:
     * Home's first frame already has it instead of drawing the plain colour and the picture popping in later.
     * Only the later changes of a rotation decode in the background.
     */
    private void showFirst() {
        String previous = preferences.getString("image", "");
        List<File> playable = playable();
        File first = WallpaperRotation.find(playable, previous);
        if (first == null) first = WallpaperRotation.pick(playable, previous, order, random);
        while (first != null) {
            Bitmap bitmap = decode(first);
            if (bitmap != null) {
                show(bitmap);
                // The picture covers the whole screen: the root's plain colour under it is overdraw.
                root.setBackground(null);
                displayed = first.getName();
                showCaption(false);
                if (liveDir != null) seen(displayed);
                if (!displayed.equals(previous)) {
                    preferences.edit().putString("image", displayed)
                            .putLong("changed", System.currentTimeMillis()).apply();
                }
                return;
            }
            files.remove(first);   // unreadable or over 4K: try another, or keep the plain colour
            playable = playable();
            first = WallpaperRotation.pick(playable, previous, order, random);
        }
    }

    void start() {
        active = true;
        update();
    }

    void stop() {
        active = false;
    }

    /** The pictures the rotation picks from now: wallpaper_moods' group for the time of day, or the whole collection. */
    private List<File> playable() {
        if (moods.isEmpty()) return files;
        LocalDateTime now = LocalDateTime.now();
        if (!now.toLocalDate().equals(sunDay)) {
            sunDay = now.toLocalDate();
            sun = WallpaperMood.sun(sunDay, latitude, longitude, ZoneId.systemDefault());
        }
        return WallpaperMood.select(files, moods, WallpaperMood.timeOfDay(now.getHour() * 60 + now.getMinute(), sun[0], sun[1]));
    }

    void close() {
        active = false;
        closed = true;
        decoder.shutdownNow();
    }

    void update() {
        if (!active || closed || loading) return;
        File fresh = liveDir == null ? null : rescan();
        if (files.isEmpty()) return;
        String previous = preferences.getString("image", "");
        // A picture outside the time of day's group (the group changed) is replaced at once, not after the interval.
        List<File> playable = playable();
        File selected = WallpaperRotation.find(playable, previous);
        long changed = preferences.getLong("changed", 0);
        if (fresh != null) {
            selected = fresh;
        } else if (selected == null || WallpaperRotation.due(changed, System.currentTimeMillis(), interval)) {
            selected = WallpaperRotation.pick(playable, previous, order, random);
        }
        if (selected == null || selected.getName().equals(displayed)) return;
        File next = selected;
        loading = true;
        decoder.execute(() -> {
            Bitmap bitmap = decode(next);
            root.post(() -> {
                loading = false;
                if (closed || !active) {
                    if (bitmap != null) bitmap.recycle();
                    return;
                }
                if (bitmap == null) {
                    files.remove(next);
                    broken.put(next.getName(), next.lastModified());
                    Log.w("TvHome.Wallpaper", "Skipping unreadable image: " + next.getName());
                    update();
                    return;
                }
                if (current == null) root.setBackground(null);
                show(bitmap);
                displayed = next.getName();
                showCaption(true);
                if (liveDir != null) seen(displayed);
                if (!displayed.equals(previous) || changed == 0) {
                    preferences.edit().putString("image", displayed)
                            .putLong("changed", System.currentTimeMillis()).apply();
                }
            });
        });
    }

    /**
     * wallpaper_live: reads the folder (and a changed captions.txt) again, and returns the newest picture added since
     * Home last looked, or null. A picture written in the last few seconds waits for the next minute.
     */
    private File rescan() {
        long settled = System.currentTimeMillis() - 5_000;
        files.clear();
        for (File file : WallpaperRotation.images(liveDir)) {
            // Skip a picture still being written, by any pick, and one that already failed unless written again.
            if (file.lastModified() > settled
                    || Long.valueOf(file.lastModified()).equals(broken.get(file.getName()))) continue;
            files.add(file);
        }
        if (captionsOn) {
            long stamp = new File(liveDir, WallpaperCaptions.FILE_NAME).lastModified();
            if (stamp != captionsStamp) {
                captionsStamp = stamp;
                names = WallpaperCaptions.read(liveDir);
            }
        }
        Set<String> known = new HashSet<>(preferences.getStringSet("live_known", Collections.emptySet()));
        Set<String> present = new HashSet<>();
        for (File file : files) present.add(file.getName());
        // Forget pictures that are gone, so the list stays as small as the folder.
        boolean changed = known.retainAll(present);
        // Nothing seen is left (a theme push replaced the collection): what is there now is the collection, not news.
        if (known.isEmpty() && !present.isEmpty()) {
            known = present;
            changed = true;
        }
        if (changed) preferences.edit().putStringSet("live_known", known).apply();
        return WallpaperRotation.fresh(files, known, settled);
    }

    private void seen(String name) {
        Set<String> known = new HashSet<>(preferences.getStringSet("live_known", Collections.emptySet()));
        if (known.add(name)) preferences.edit().putStringSet("live_known", known).apply();
    }

    /**
     * Small light text under the top-right buttons, above everything else and never focusable. It sits where the
     * top bar ends in every layout, so it does not cover the clock, the stats line or the tiles.
     */
    private static TextView makeCaption(Context context, FrameLayout root, boolean bottom) {
        float dp = context.getResources().getDisplayMetrics().density;
        TextView text = new TextView(context);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        text.setTextColor(0xD9FFFFFF);
        text.setShadowLayer(6 * dp, 0, dp, 0xCC000000);
        text.setSingleLine(true);
        text.setEllipsize(TextUtils.TruncateAt.END);
        text.setMaxWidth(Math.round(520 * dp));
        text.setFocusable(false);
        text.setAlpha(0f);
        try {
            text.setTypeface(UiFont.get(context, R.string.font_regular));
        } catch (Exception ignored) {
            // the system font is fine
        }
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, (bottom ? Gravity.BOTTOM : Gravity.TOP) | Gravity.END);
        if (bottom) params.bottomMargin = Math.round(28 * dp);
        else params.topMargin = Math.round(110 * dp);
        params.setMarginEnd(Math.round(62 * dp));
        root.addView(text, params);
        return text;
    }

    /** The displayed picture's name, faded across a change; a picture without a name hides the caption. */
    private void showCaption(boolean fade) {
        if (caption == null) return;
        String name = names.get(displayed);
        caption.animate().cancel();
        if (name == null) {
            caption.setAlpha(0f);
            return;
        }
        caption.setContentDescription(name);
        if (!revealed) {
            caption.setText(name);
            caption.setAlpha(0f);
            return;
        }
        if (!fade || caption.getAlpha() == 0f) {
            caption.setText(name);
            caption.setAlpha(1f);
            return;
        }
        caption.animate().alpha(0f).setDuration(250).withEndAction(() -> {
            caption.setText(name);
            caption.animate().alpha(1f).setDuration(350).start();
        }).start();
    }

    /** wallpaper_captions_at_end: fades the caption in once Home's grid reaches its end, and out as it leaves. */
    void revealCaption(boolean atEnd) {
        if (caption == null || revealed == atEnd) return;
        revealed = atEnd;
        caption.animate().cancel();   // this drops a pending showCaption end action, so set the name here
        String name = names.get(displayed);
        if (name != null) caption.setText(name);
        caption.animate().alpha(atEnd && name != null ? 1f : 0f).setDuration(300).start();
    }

    /** Names plus sizes: enough to notice a different collection without hashing every image. */
    private static String collectionKey(List<File> images) {
        StringBuilder key = new StringBuilder();
        for (File file : images) key.append(file.getName()).append(':').append(file.length()).append(';');
        return key.toString();
    }

    /** Puts a picture on screen and releases the previous one after a frame has drawn the new one. */
    private void show(Bitmap bitmap) {
        Bitmap old = current;
        current = bitmap;
        if (!fade || old == null || old == bitmap) {
            image.setImageBitmap(bitmap);
            if (old != null && old != bitmap) image.postDelayed(old::recycle, 500);
            return;
        }
        TransitionDrawable cross = new TransitionDrawable(new Drawable[]{
                new BitmapDrawable(image.getResources(), old), new BitmapDrawable(image.getResources(), bitmap)});
        cross.setCrossFadeEnabled(true);
        image.setImageDrawable(cross);
        cross.startTransition(FADE_MS);
        // The finished fade still draws the old picture at alpha 0: hand over to the plain bitmap before recycling it.
        image.postDelayed(() -> {
            if (current == bitmap) image.setImageBitmap(bitmap);
            old.recycle();
        }, FADE_MS + 100);
    }

    private Bitmap decode(File file) {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), options);
            int width = options.outWidth, height = options.outHeight;
            if (width <= 0 || height <= 0) return null;
            int sample = WallpaperSize.sampleSize(width, height, this.width, this.height);
            if (!WallpaperSize.accepted(width / sample, height / sample)) return null;
            options.inJustDecodeBounds = false;
            options.inSampleSize = sample;
            int[] scale = WallpaperSize.coverScale(width / sample, height / sample, this.width, this.height);
            if (scale != null) {
                options.inScaled = true;
                options.inDensity = scale[0];
                options.inTargetDensity = scale[1];
            }
            options.inPreferredConfig = bitmapConfig;
            options.inMutable = dimColor != 0;
            Bitmap decoded = BitmapFactory.decodeFile(file.getPath(), options);
            if (decoded == null) return null;
            if (dimColor != 0) new Canvas(decoded).drawColor(dimColor);
            // One copy in GPU memory instead of a software bitmap plus its texture; the decode stays off the UI
            // thread for rotations, and Home's return no longer uploads 4-8 MB to the GPU.
            Bitmap gpu = decoded.copy(Bitmap.Config.HARDWARE, false);
            if (gpu == null) return decoded;
            decoded.recycle();
            return gpu;
        } catch (Exception | OutOfMemoryError failure) {
            return null;
        }
    }
}
