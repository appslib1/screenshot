package com.screenshot_capture.screenshot_photo;

import android.Manifest;
import android.content.ContentUris;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import androidx.core.content.ContextCompat;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Charge la liste des captures d'écran à afficher : celles prises par l'appli
 * (dossier privé du cache) FUSIONNÉES avec celles présentes sur l'appareil
 * (dossier public « Screenshots », via MediaStore), triées par date décroissante.
 *
 * <p>Un item est représenté par une {@code String} :
 * <ul>
 *   <li>un chemin de fichier absolu → capture de l'appli, <b>modifiable</b> (crop/suppression) ;</li>
 *   <li>une URI {@code content://…} → capture externe (autre appli/système), <b>lecture + partage seulement</b>.</li>
 * </ul>
 * {@link com.bumptech.glide.Glide#load(String)} sait afficher les deux formes.
 */
public final class ScreenshotLoader {

    private ScreenshotLoader() {}

    /** Un item est modifiable (crop/suppression) uniquement si l'appli en est propriétaire (fichier du cache). */
    public static boolean isEditable(String item) {
        return item != null && !item.startsWith("content://");
    }

    /** Permission de lecture média requise selon la version d'Android. */
    public static String requiredPermission() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ? Manifest.permission.READ_MEDIA_IMAGES
                : Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    public static boolean hasReadPermission(Context ctx) {
        return ContextCompat.checkSelfPermission(ctx, requiredPermission())
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Retourne la liste fusionnée (cache appli + dossier Screenshots de l'appareil),
     * triée par date décroissante. Le dossier public n'est lu que si la permission
     * média est accordée ; sinon on retourne seulement les captures de l'appli.
     */
    public static List<String> loadAll(Context ctx) {
        List<Entry> entries = new ArrayList<>();
        addAppCache(ctx, entries);
        if (hasReadPermission(ctx)) {
            addDeviceScreenshots(ctx, entries);
        }
        // Tri par date décroissante (plus récent d'abord)
        Collections.sort(entries, (a, b) -> Long.compare(b.date, a.date));

        List<String> out = new ArrayList<>(entries.size());
        for (Entry e : entries) out.add(e.value);
        return out;
    }

    private static void addAppCache(Context ctx, List<Entry> out) {
        File dir = new File(ctx.getCacheDir(), "Screenshots");
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            String name = f.getName().toLowerCase();
            if (name.endsWith(".jpg") || name.endsWith(".png")) {
                out.add(new Entry(f.getAbsolutePath(), f.lastModified()));
            }
        }
    }

    private static void addDeviceScreenshots(Context ctx, List<Entry> out) {
        Uri collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        String[] projection = {
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DATE_MODIFIED
        };

        String selection;
        // API 29+ : filtre sur le chemin relatif ; sinon sur le chemin absolu (DATA).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            selection = MediaStore.Images.Media.RELATIVE_PATH + " LIKE ?";
        } else {
            selection = MediaStore.Images.Media.DATA + " LIKE ?";
        }
        String[] args = { "%Screenshots%" };
        String sortOrder = MediaStore.Images.Media.DATE_MODIFIED + " DESC";

        try (Cursor c = ctx.getContentResolver()
                .query(collection, projection, selection, args, sortOrder)) {
            if (c == null) return;
            int idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
            int dateCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED);
            while (c.moveToNext()) {
                long id = c.getLong(idCol);
                // DATE_MODIFIED est en secondes → millisecondes pour trier avec lastModified().
                long date = c.getLong(dateCol) * 1000L;
                Uri uri = ContentUris.withAppendedId(collection, id);
                out.add(new Entry(uri.toString(), date));
            }
        } catch (Exception ignored) {
            // Requête MediaStore indisponible → on garde seulement les captures de l'appli.
        }
    }

    private static final class Entry {
        final String value;
        final long date;

        Entry(String value, long date) {
            this.value = value;
            this.date = date;
        }
    }
}
