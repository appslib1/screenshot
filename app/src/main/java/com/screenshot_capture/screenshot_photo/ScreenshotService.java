package com.screenshot_capture.screenshot_photo;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.AudioManager;
import android.media.MediaActionSound;
import android.media.MediaScannerConnection;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public class ScreenshotService extends Service {

    private static final String TAG = "ScreenshotService";

    public static final String EXTRA_RESULT_CODE = "extra_result_code";
    public static final String EXTRA_RESULT_DATA = "extra_result_data";

    /** Init a fresh session : intent porte RESULT_CODE + RESULT_DATA du consent MediaProjection. */
    public static final String ACTION_INIT = "screenshot.action.INIT";
    /** Déclenche une capture en réutilisant la MediaProjection déjà consentie. */
    public static final String ACTION_TRIGGER = "screenshot.action.TRIGGER";
    /** Termine la session : libère la projection et arrête le service. */
    public static final String ACTION_STOP = "screenshot.action.STOP";

    private static final String CHANNEL_ID = "screenshot_silent_v1";
    private static final int NOTIFICATION_ID = 101;
    /** Petit délai avant capture pour laisser la barre de notifs se replier proprement. */
    private static final long CAPTURE_DELAY_MS = 500L;
    private static final String PREFS_NAME = "app_settings";
    private static final String KEY_SOUND = "sound";

    /**
     * Vrai tant qu'une MediaProjection est vivante et prête à être réutilisée.
     * Lu par {@link CaptureTriggerActivity} pour court-circuiter la demande de consent.
     */
    private static volatile boolean sessionAlive = false;

    public static boolean isSessionAlive() { return sessionAlive; }

    private MediaProjection projection;
    private HandlerThread handlerThread;
    private Handler handler;
    private boolean capturing;
    private String mode = CaptureTriggerActivity.MODE_NOTIFICATION;
    private MediaActionSound shutterSound;

    /**
     * Surface persistante pour toute la session. On ne peut PAS appeler
     * {@code createVirtualDisplay} plusieurs fois sur la même MediaProjection sur Android 14+,
     * donc on crée le reader + le VD une seule fois et on filtre les frames dans le listener.
     */
    private ImageReader reader;
    private VirtualDisplay virtualDisplay;
    private int screenWidth, screenHeight, screenDensity;
    /** True le temps qu'une capture soit demandée mais pas encore sauvegardée. */
    private final AtomicBoolean captureRequested = new AtomicBoolean(false);
    /** Cache de la dernière frame reçue, réutilisée quand l'écran devient statique. */
    private Image latestImage;

    @Override
    public void onCreate() {
        super.onCreate();
        ensureChannel();
        handlerThread = new HandlerThread("ScreenshotCapture");
        handlerThread.start();
        handler = new Handler(handlerThread.getLooper());
        shutterSound = new MediaActionSound();
        shutterSound.load(MediaActionSound.SHUTTER_CLICK);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        Log.d(TAG, "onStartCommand action=" + action
                + " projection=" + (projection != null) + " capturing=" + capturing);

        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_STOP.equals(action)) {
            endSession();
            return START_NOT_STICKY;
        }

        String m = intent.getStringExtra(CaptureTriggerActivity.EXTRA_MODE);
        if (m != null) mode = m;

        if (ACTION_TRIGGER.equals(action)) {
            if (!sessionAlive || projection == null) {
                Log.w(TAG, "trigger received but no live projection");
                broadcastResult(null);
                return START_NOT_STICKY;
            }
            if (capturing) {
                Log.w(TAG, "trigger ignored - capture already in flight");
                return START_NOT_STICKY;
            }
            capturing = true;
            scheduleCapture();
            return START_NOT_STICKY;
        }

        // ACTION_INIT (default) : consommer RESULT_CODE + RESULT_DATA. Remplace toute session morte.
        if (capturing) {
            Log.w(TAG, "init ignored - capture in flight");
            return START_NOT_STICKY;
        }
        if (projection != null) {
            // Session précédente pas complètement nettoyée : on force la libération avant de repartir.
            try { projection.stop(); } catch (Exception ignored) {}
            projection = null;
        }

        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent data = intent.getParcelableExtra(EXTRA_RESULT_DATA);
        if (data == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        startForegroundCompat();

        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        projection = mpm.getMediaProjection(resultCode, data);
        if (projection == null) {
            Log.e(TAG, "getMediaProjection returned null");
            invalidateSession();
            return START_NOT_STICKY;
        }
        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                // Système ou utilisateur (« Stop sharing ») a arrêté la projection.
                // On invalide la session mais on garde la notification pour permettre un retap.
                Log.i(TAG, "MediaProjection.onStop()");
                new Handler(Looper.getMainLooper()).post(ScreenshotService.this::invalidateSession);
            }
        }, handler);

        if (!setupPersistentCapture()) {
            Log.e(TAG, "setupPersistentCapture failed");
            invalidateSession();
            return START_NOT_STICKY;
        }

        sessionAlive = true;
        capturing = true;
        scheduleCapture();

        return START_NOT_STICKY;
    }

    /**
     * Crée l'ImageReader et la VirtualDisplay UNE fois pour toute la session.
     * Aucun listener persistant : le producer remplit le buffer (maxImages=2) puis se bloque
     * quand l'écran est statique. À chaque capture on drain le buffer pour débloquer le
     * producer et on installe un listener one-shot qui attrape la prochaine frame.
     */
    private boolean setupPersistentCapture() {
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        DisplayMetrics metrics = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(metrics);
        screenWidth = metrics.widthPixels;
        screenHeight = metrics.heightPixels;
        screenDensity = metrics.densityDpi;

        reader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2);
        reader.setOnImageAvailableListener(cachingListener, handler);

        try {
            virtualDisplay = projection.createVirtualDisplay(
                    "ScreenshotCapture",
                    screenWidth, screenHeight, screenDensity,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.getSurface(), null, handler);
            Log.d(TAG, "persistent VD created " + screenWidth + "x" + screenHeight + "@" + screenDensity);
        } catch (Exception e) {
            Log.e(TAG, "createVirtualDisplay failed", e);
            try { reader.close(); } catch (Exception ignored) {}
            reader = null;
            return false;
        }
        return true;
    }

    private final Runnable captureTimeout = () -> {
        if (captureRequested.compareAndSet(true, false)) {
            Log.w(TAG, "capture timeout — no frame delivered");
            notifyMain(R.string.captureFailed);
            invalidateSession();
            broadcastResult(null);
        }
    };

    private void onCaptureFrameReady(Image img) {
        playShutterIfEnabled();
        String savedPath = null;
        try {
            savedPath = save(img, screenWidth, screenHeight);
        } catch (OutOfMemoryError oom) {
            // Devices à faible RAM (ex: Yoga Tab 3 1 Go) peuvent échouer sur l'alloc du bitmap.
            // On évite le crash hard : la capture est perdue, la session invalidée, l'user retente.
            Log.e(TAG, "OOM during save()", oom);
            notifyMain(R.string.captureFailed);
        } catch (Exception e) {
            Log.e(TAG, "save() threw", e);
            notifyMain(R.string.captureFailed);
        }
        if (savedPath == null) {
            invalidateSession();
        }
        broadcastResult(savedPath);
    }

    /**
     * Termine complètement la session : libère la projection, cache la notification et arrête
     * le service. Utilisé quand l'utilisateur tape le bouton « Stop ».
     */
    private void endSession() {
        Log.i(TAG, "endSession()");
        invalidateSession();
        stopForeground(true);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(NOTIFICATION_ID);
        stopSelf();
    }

    /**
     * Rend la MediaProjection inutilisable pour la prochaine capture, tout en gardant le
     * service et sa notification actifs — la prochaine capture repassera par un consent frais
     * (slow path dans {@link CaptureTriggerActivity}).
     */
    private void invalidateSession() {
        Log.i(TAG, "invalidateSession()");
        sessionAlive = false;
        capturing = false;
        captureRequested.set(false);
        if (handler != null) handler.removeCallbacks(captureTimeout);
        if (latestImage != null) {
            try { latestImage.close(); } catch (Exception ignored) {}
            latestImage = null;
        }
        if (virtualDisplay != null) {
            try { virtualDisplay.release(); } catch (Exception ignored) {}
            virtualDisplay = null;
        }
        if (reader != null) {
            try { reader.close(); } catch (Exception ignored) {}
            reader = null;
        }
        if (projection != null) {
            try { projection.stop(); } catch (Exception ignored) {}
            projection = null;
        }
        try {
            updateNotificationText(getString(R.string.clickToTakeScreenshot));
        } catch (Exception ignored) {}
    }

    private void stopForegroundDetach() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_DETACH);
        } else {
            stopForeground(false);
        }
    }

    private void scheduleCapture() {
        Log.d(TAG, "scheduleCapture projection=" + (projection != null));
        if (projection == null) {
            Log.w(TAG, "scheduleCapture: projection null, invalidating");
            invalidateSession();
            broadcastResult(null);
            return;
        }
        // Court délai avant de tirer la frame : le temps que la barre de notifs se replie
        // (le tap dessus déclenche une animation ~200 ms) et éviter de capturer le shade.
        new Handler(Looper.getMainLooper())
                .postDelayed(() -> handler.post(this::capture), CAPTURE_DELAY_MS);
    }

    private void capture() {
        Log.d(TAG, "capture() sessionAlive=" + sessionAlive
                + " reader=" + (reader != null) + " vd=" + (virtualDisplay != null)
                + " cached=" + (latestImage != null));
        if (!sessionAlive || projection == null || reader == null || virtualDisplay == null) {
            Log.w(TAG, "capture: session/surface dead");
            notifyMain(R.string.captureFailed);
            invalidateSession();
            broadcastResult(null);
            return;
        }
        captureRequested.set(true);
        handler.removeCallbacks(captureTimeout);
        handler.postDelayed(captureTimeout, 3000L);

        // Si on a déjà une frame cachée (le listener l'a stockée à la dernière animation
        // d'écran), on livre immédiatement — l'écran statique correspond à cette frame.
        handler.post(() -> {
            if (latestImage != null && captureRequested.compareAndSet(true, false)) {
                handler.removeCallbacks(captureTimeout);
                onCaptureFrameReady(latestImage);
            }
            // Sinon : le listener livrera la prochaine frame poussée (fin d'animation, etc.).
        });
    }

    /**
     * Listener persistant : cache la dernière frame reçue et livre immédiatement si une
     * capture a été demandée pendant qu'aucune frame n'était encore disponible.
     */
    private final ImageReader.OnImageAvailableListener cachingListener = new ImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(ImageReader r) {
            Image acquired = null;
            try {
                acquired = r.acquireLatestImage();
                if (acquired == null) return;
                // Remplace le cache : ancienne image close, nouvelle image conservée.
                Image previous = latestImage;
                latestImage = acquired;
                acquired = null; // ownership transféré au cache
                if (previous != null) {
                    try { previous.close(); } catch (Exception ignored) {}
                }
                // Livraison si une capture était en attente d'une frame.
                if (captureRequested.compareAndSet(true, false)) {
                    handler.removeCallbacks(captureTimeout);
                    onCaptureFrameReady(latestImage);
                }
            } catch (Exception e) {
                Log.e(TAG, "cachingListener error", e);
                if (captureRequested.compareAndSet(true, false)) {
                    handler.removeCallbacks(captureTimeout);
                    notifyMain(R.string.captureFailed);
                    invalidateSession();
                    broadcastResult(null);
                }
            } finally {
                if (acquired != null) {
                    try { acquired.close(); } catch (Exception ignored) {}
                }
            }
        }
    };

    private void broadcastResult(String pathOrNull) {
        Intent done = new Intent(CaptureTriggerActivity.ACTION_CAPTURE_DONE)
                .setPackage(getPackageName());
        if (pathOrNull != null) {
            done.putExtra(CaptureTriggerActivity.EXTRA_PATH, pathOrNull);
        }
        sendBroadcast(done);
        Log.d(TAG, "broadcast CAPTURE_DONE path=" + pathOrNull + " mode=" + mode);
        capturing = false;
        // Tear down la session après chaque capture pour que l'indicateur système
        // d'enregistrement d'écran (chip persistante avec timer) disparaisse. La notif reste
        // affichée pour que l'user retape → nouveau consent → nouvelle capture.
        // Trade-off : un consent par capture, mais plus d'indicateur permanent qui inquiète
        // les users et vide la batterie. Pattern forcé de toute façon par Android 14+.
        teardownAfterCapture();
    }

    private void teardownAfterCapture() {
        // 1. Libère VD + reader + projection → l'indicateur système disparaît
        invalidateSession();
        // 2. Repost la notif en mode « idle » (sans bouton Stop puisque la session est morte)
        //    avant de sortir de foreground, pour qu'elle reste affichée en tant que notif normale.
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIFICATION_ID, buildIdleNotification());
        } catch (Exception ignored) {}
        // 3. Sort du FGS tout en gardant la notif visible, puis stoppe le service.
        //    Prochaine capture = CaptureTriggerActivity relance tout proprement via le consent.
        stopForegroundDetach();
        stopSelf();
    }

    private Notification buildIdleNotification() {
        Intent trigger = new Intent(this, CaptureTriggerActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent triggerPi = PendingIntent.getActivity(this, 1, trigger,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.baseline_crop_free_24)
                .setContentTitle(getString(R.string.shortAppName))
                .setContentText(getString(R.string.clickToTakeScreenshot))
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setOngoing(true)
                .setContentIntent(triggerPi)
                .build();
    }

    private String save(Image image, int width, int height) {
        Bitmap bitmap = toBitmap(image, width, height);
        if (bitmap == null) {
            notifyMain(R.string.captureFailed);
            return null;
        }

        String name = "Screenshot_"
                + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date())
                + ".png";

        String identifier = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                ? saveViaMediaStore(bitmap, name)
                : saveToPublicPictures(bitmap, name);

        boolean willShowPreview = identifier != null && canShowOverlay();
        if (willShowPreview) {
            // Preview flottante style iOS : miniature indépendante créée avant de recycler le
            // bitmap plein. L'overlay garde ownership du thumb et le recycle à son dismiss.
            postPreviewOverlay(bitmap, identifier);
        }
        bitmap.recycle();

        // La preview flottante EST le feedback visuel « screenshot saved » : si elle s'affiche,
        // on skip le toast pour ne pas doubler. Si pas d'overlay perm (user en mode notif
        // uniquement, ou perm refusée), le toast reste la seule confirmation.
        if (identifier == null) {
            notifyMain(R.string.captureFailed);
        } else if (!willShowPreview) {
            notifyMain(R.string.screenshotSaved);
        }
        return identifier;
    }

    private boolean canShowOverlay() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this);
    }

    private void postPreviewOverlay(Bitmap source, String identifier) {
        int targetWidth = (int) (120 * getResources().getDisplayMetrics().density);
        Bitmap thumb;
        if (source.getWidth() <= targetWidth) {
            // Source déjà plus petite que la cible : on copie pour ne pas partager la ref
            // avec le bitmap qui sera recyclé dans save().
            thumb = source.copy(source.getConfig(), false);
        } else {
            float ratio = (float) source.getHeight() / source.getWidth();
            int targetHeight = Math.max(1, Math.round(targetWidth * ratio));
            Bitmap scaled = Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true);
            // createScaledBitmap renvoie source si les dims matchent : on force une copie dans ce cas.
            thumb = (scaled == source) ? source.copy(source.getConfig(), false) : scaled;
        }
        new Handler(Looper.getMainLooper()).post(() ->
                PreviewThumbnail.show(getApplicationContext(), thumb, identifier));
    }

    /**
     * Convertit la frame {@link ImageReader} en bitmap aux dimensions exactes du screen.
     * Fast path (rowPadding == 0, cas courant 1280×800 et autres résolutions alignées) :
     * copie directe en un seul coup, un seul bitmap vivant.
     * Slow path (buffer paddé par le pilote) : copie ligne par ligne dans un bitmap pré-dimensionné
     * via {@link Bitmap#setPixels}, ce qui évite de garder un bitmap intermédiaire paddé vivant
     * en même temps que le final — pic mémoire divisé par deux sur devices 1 Go.
     */
    private Bitmap toBitmap(Image image, int width, int height) {
        Image.Plane[] planes = image.getPlanes();
        ByteBuffer buffer = planes[0].getBuffer();
        buffer.rewind(); // l'image peut être réutilisée (cache) → position doit être à 0.
        int pixelStride = planes[0].getPixelStride();
        int rowStride = planes[0].getRowStride();
        int rowPadding = rowStride - pixelStride * width;

        if (rowPadding == 0) {
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            bitmap.copyPixelsFromBuffer(buffer);
            return bitmap;
        }

        // RGBA_8888 = pixelStride 4. Si le pilote renvoie autre chose on ne gère pas → fallback nul.
        if (pixelStride != 4) {
            Log.w(TAG, "unexpected pixelStride=" + pixelStride);
            return null;
        }

        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        IntBuffer intBuffer = buffer.asIntBuffer();
        int bufferRowInts = rowStride / 4;
        int[] rowPixels = new int[width];
        for (int y = 0; y < height; y++) {
            intBuffer.position(y * bufferRowInts);
            intBuffer.get(rowPixels, 0, width);
            // Bytes RGBA en mémoire → int little-endian 0xAABBGGRR.
            // setPixels attend Color 0xAARRGGBB → swap R et B.
            for (int x = 0; x < width; x++) {
                int p = rowPixels[x];
                rowPixels[x] = (p & 0xFF00FF00)
                        | ((p & 0x000000FF) << 16)
                        | ((p & 0x00FF0000) >>> 16);
            }
            bitmap.setPixels(rowPixels, 0, width, 0, y, width, 1);
        }
        return bitmap;
    }

    /** Q+ : insertion MediaStore dans Pictures/Screenshots avec le pattern IS_PENDING. */
    private String saveViaMediaStore(Bitmap bitmap, String displayName) {
        ContentResolver resolver = getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, displayName);
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
        values.put(MediaStore.Images.Media.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES + "/Screenshots");
        values.put(MediaStore.Images.Media.IS_PENDING, 1);

        Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) {
            Log.e(TAG, "MediaStore insert returned null");
            return null;
        }

        try (OutputStream out = resolver.openOutputStream(uri)) {
            if (out == null || !bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                throw new java.io.IOException("compress failed");
            }
        } catch (Exception e) {
            Log.e(TAG, "writing PNG via MediaStore failed", e);
            try { resolver.delete(uri, null, null); } catch (Exception ignored) {}
            return null;
        }

        values.clear();
        values.put(MediaStore.Images.Media.IS_PENDING, 0);
        try { resolver.update(uri, values, null, null); } catch (Exception ignored) {}

        Log.d(TAG, "save ok uri=" + uri);
        return uri.toString();
    }

    /** Pré-Q : écriture directe dans Pictures/Screenshots puis scan MediaStore. */
    private String saveToPublicPictures(Bitmap bitmap, String displayName) {
        File dir = new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                "Screenshots");
        if (!dir.exists() && !dir.mkdirs()) {
            Log.e(TAG, "cannot create " + dir);
            return null;
        }
        File out = new File(dir, displayName);
        try (FileOutputStream fos = new FileOutputStream(out)) {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)) {
                throw new java.io.IOException("compress failed");
            }
        } catch (Exception e) {
            Log.e(TAG, "writing PNG to public Pictures failed", e);
            return null;
        }

        MediaScannerConnection.scanFile(this,
                new String[]{out.getAbsolutePath()},
                new String[]{"image/png"},
                null);

        Log.d(TAG, "save ok path=" + out.getAbsolutePath());
        return out.getAbsolutePath();
    }

    private void notifyMain(int stringRes) {
        new Handler(Looper.getMainLooper()).post(() ->
                Toast.makeText(this, stringRes, Toast.LENGTH_SHORT).show());
    }

    private void playShutterIfEnabled() {
        SharedPreferences p = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        if (!p.getBoolean(KEY_SOUND, false) || shutterSound == null) return;

        // MediaActionSound passe par STREAM_SYSTEM_ENFORCED : sur certains appareils il sonne
        // même en silencieux ou en vibreur. On aligne donc explicitement le déclenchement sur
        // le profil sonore et le volume système de l'appareil.
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (am != null && (am.getRingerMode() != AudioManager.RINGER_MODE_NORMAL
                || am.getStreamVolume(AudioManager.STREAM_SYSTEM) == 0)) {
            return;
        }

        try { shutterSound.play(MediaActionSound.SHUTTER_CLICK); } catch (Exception ignored) {}
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Screenshot channel", NotificationManager.IMPORTANCE_LOW);
            channel.setSound(null, null);
            channel.enableVibration(false);
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text) {
        Intent trigger = new Intent(this, CaptureTriggerActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent triggerPi = PendingIntent.getActivity(this, 1, trigger, piFlags);

        Intent stop = new Intent(this, ScreenshotService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 2, stop, piFlags);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.baseline_crop_free_24)
                .setContentTitle(getString(R.string.shortAppName))
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setOngoing(true)
                .setContentIntent(triggerPi)
                .addAction(0, getString(R.string.stop), stopPi)
                .build();
    }

    private void startForegroundCompat() {
        Notification n = buildNotification(getString(R.string.clickToTakeScreenshot));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
    }

    private void updateNotificationText(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification(text));
    }

    @Override
    public void onDestroy() {
        sessionAlive = false;
        capturing = false;
        captureRequested.set(false);
        if (handler != null) handler.removeCallbacks(captureTimeout);
        if (latestImage != null) {
            try { latestImage.close(); } catch (Exception ignored) {}
            latestImage = null;
        }
        if (virtualDisplay != null) {
            try { virtualDisplay.release(); } catch (Exception ignored) {}
            virtualDisplay = null;
        }
        if (reader != null) {
            try { reader.close(); } catch (Exception ignored) {}
            reader = null;
        }
        if (projection != null) {
            try { projection.stop(); } catch (Exception ignored) {}
            projection = null;
        }
        if (handlerThread != null) handlerThread.quitSafely();
        if (shutterSound != null) {
            try { shutterSound.release(); } catch (Exception ignored) {}
            shutterSound = null;
        }
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public static Intent initIntent(Context ctx, int resultCode, Intent data) {
        Intent i = new Intent(ctx, ScreenshotService.class);
        i.setAction(ACTION_INIT);
        i.putExtra(EXTRA_RESULT_CODE, resultCode);
        i.putExtra(EXTRA_RESULT_DATA, data);
        return i;
    }

    public static Intent triggerIntent(Context ctx, String mode) {
        Intent i = new Intent(ctx, ScreenshotService.class);
        i.setAction(ACTION_TRIGGER);
        if (mode != null) i.putExtra(CaptureTriggerActivity.EXTRA_MODE, mode);
        return i;
    }
}
