package com.gathin.tvhome;

import android.service.dreams.DreamService;

/**
 * Screensaver: the launcher's thin clock and date on black, over a slow ambient glow or, with the theme's
 * dream_wallpaper, Home's own wallpaper (ClockScene). No network: cheap enough for a 1 GB TV.
 */
public class ClockDream extends DreamService {

    private ClockScene scene;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);   // any key wakes the TV back to where it was
        setFullscreen(true);
        setScreenBright(false);
        setContentView(R.layout.dream_clock);
        scene = new ClockScene(getWindow().getDecorView(), Config.load(this));
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        scene.start();
    }

    @Override
    public void onDreamingStopped() {
        scene.stop();
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        if (scene != null) scene.close();
        super.onDetachedFromWindow();
    }
}
