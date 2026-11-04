package com.hvkeyn.ceditneuro.background;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;

import java.io.File;
import java.io.FileWriter;

/**
 * Runs through app_process as the shell user. It creates a public, off-screen display
 * so apps can be launched with `am start --display` without taking over the phone screen.
 */
public final class AgentDisplayMain {
    private AgentDisplayMain() {}

    public static void main(String[] args) {
        int width = clamp(parse(args, 0, 1080), 480, 4096);
        int height = clamp(parse(args, 1, 2400), 480, 4096);
        int dpi = clamp(parse(args, 2, 420), 120, 640);
        File out = new File("/data/local/tmp/ceditneuro-agent-display.id");
        File log = new File("/data/local/tmp/ceditneuro-agent-display.log");
        VirtualDisplay display = null;
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object thread = at.getMethod("systemMain").invoke(null);
            Context context = (Context) at.getMethod("getSystemContext").invoke(thread);
            DisplayManager manager = context.getSystemService(DisplayManager.class);
            int flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
                    | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY;
            display = manager.createVirtualDisplay(
                    "CEditNeuro Agent", width, height, dpi, null, flags);
            int id = display.getDisplay().getDisplayId();
            write(out, Integer.toString(id));
            append(log, "display=" + id + " " + width + "x" + height + " dpi=" + dpi + "\n");
            while (true) Thread.sleep(1000L);
        } catch (Throwable error) {
            append(log, "error=" + error.getClass().getName() + ": " + error.getMessage() + "\n");
            write(out, "-1");
            if (display != null) display.release();
        }
    }

    private static int parse(String[] args, int index, int fallback) {
        try { return Integer.parseInt(args[index]); } catch (Exception ignored) { return fallback; }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static void write(File file, String text) {
        try (FileWriter writer = new FileWriter(file, false)) { writer.write(text); }
        catch (Exception ignored) { }
    }

    private static void append(File file, String text) {
        try (FileWriter writer = new FileWriter(file, true)) { writer.write(text); }
        catch (Exception ignored) { }
    }
}
