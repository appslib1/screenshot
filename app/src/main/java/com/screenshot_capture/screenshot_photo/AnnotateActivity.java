package com.screenshot_capture.screenshot_photo;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.AppCompatImageButton;
import androidx.appcompat.widget.Toolbar;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdSize;
import com.google.android.gms.ads.AdView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Éditeur d'annotation : texte, flèche, pixelation. Pattern calqué sur {@link CropActivity}
 * pour la load/save (fichier legacy OU URI MediaStore, écrasement du contenu). Le vrai travail
 * de dessin et de touch handling est dans {@link AnnotateOverlayView}.
 */
public class AnnotateActivity extends AppCompatActivity {

    private AnnotateOverlayView annotateView;
    private File currentImageFile;
    private Uri currentImageUri;
    private Bitmap sourceBitmap;

    private AppCompatImageButton toolText, toolArrow, toolBlur;
    private View[] swatches;
    private int[] swatchColors;
    private int selectedColorIndex = 0;

    private FrameLayout adContainerView;
    private AdView adView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_annotate);

        adContainerView = findViewById(R.id.ad_view_container);
        loadBanner();

        Toolbar toolbar = findViewById(R.id.toolbar);
        LinearLayout bottomBar = findViewById(R.id.annotateBottomBar);
        LinearLayout colorBar = findViewById(R.id.colorBar);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.annotateRoot), (v, insets) -> {
            Insets sb = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            toolbar.setPadding(sb.left, sb.top, sb.right, 0);
            colorBar.setPadding(sb.left, colorBar.getPaddingTop(), sb.right, colorBar.getPaddingBottom());
            bottomBar.setPadding(sb.left, 0, sb.right, 0);
            adContainerView.setPadding(sb.left, 0, sb.right, sb.bottom);
            return insets;
        });
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.annotate);
        }

        annotateView = findViewById(R.id.annotateView);

        String imgPath = getIntent().getStringExtra("img_uri");
        if (imgPath == null) {
            finish();
            return;
        }

        if (imgPath.startsWith("content://")) {
            currentImageUri = Uri.parse(imgPath);
            try (InputStream in = getContentResolver().openInputStream(currentImageUri)) {
                sourceBitmap = BitmapFactory.decodeStream(in);
            } catch (Exception e) {
                sourceBitmap = null;
            }
        } else {
            currentImageFile = new File(imgPath);
            sourceBitmap = BitmapFactory.decodeFile(imgPath);
        }

        if (sourceBitmap == null) {
            Toast.makeText(this, R.string.cropError, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        annotateView.setBitmap(sourceBitmap);
        annotateView.setOnTextTapListener(this::promptTextInput);

        setupTools();
        setupColors();
        selectTool(AnnotateOverlayView.TOOL_ARROW);

        findViewById(R.id.undoBtn).setOnClickListener(v -> annotateView.undo());
        findViewById(R.id.saveBtn).setOnClickListener(v -> saveAnnotatedImage());
    }

    private void setupTools() {
        toolText = findViewById(R.id.toolText);
        toolArrow = findViewById(R.id.toolArrow);
        toolBlur = findViewById(R.id.toolBlur);
        toolText.setOnClickListener(v -> selectTool(AnnotateOverlayView.TOOL_TEXT));
        toolArrow.setOnClickListener(v -> selectTool(AnnotateOverlayView.TOOL_ARROW));
        toolBlur.setOnClickListener(v -> selectTool(AnnotateOverlayView.TOOL_BLUR));
    }

    private void selectTool(int tool) {
        annotateView.setTool(tool);
        toolText.setSelected(tool == AnnotateOverlayView.TOOL_TEXT);
        toolArrow.setSelected(tool == AnnotateOverlayView.TOOL_ARROW);
        toolBlur.setSelected(tool == AnnotateOverlayView.TOOL_BLUR);
        // La couleur n'a pas de sens pour la pixelation : on grise la bar pour l'indiquer.
        View colorBar = findViewById(R.id.colorBar);
        colorBar.setAlpha(tool == AnnotateOverlayView.TOOL_BLUR ? 0.35f : 1f);
        for (View swatch : swatches) swatch.setEnabled(tool != AnnotateOverlayView.TOOL_BLUR);
    }

    private void setupColors() {
        swatchColors = new int[]{
                Color.parseColor("#F5222D"),  // rouge
                Color.parseColor("#FBBC04"),  // jaune
                Color.parseColor("#397BEC"),  // bleu (primaryColor)
                Color.WHITE,
                Color.BLACK
        };
        swatches = new View[]{
                findViewById(R.id.colorRed),
                findViewById(R.id.colorYellow),
                findViewById(R.id.colorBlue),
                findViewById(R.id.colorWhite),
                findViewById(R.id.colorBlack)
        };
        for (int i = 0; i < swatches.length; i++) {
            final int idx = i;
            swatches[i].setBackground(makeSwatchDrawable(swatchColors[i], false));
            swatches[i].setOnClickListener(v -> selectColor(idx));
        }
        selectColor(0);
    }

    private void selectColor(int index) {
        selectedColorIndex = index;
        annotateView.setColor(swatchColors[index]);
        for (int i = 0; i < swatches.length; i++) {
            swatches[i].setBackground(makeSwatchDrawable(swatchColors[i], i == index));
        }
    }

    private GradientDrawable makeSwatchDrawable(int fillColor, boolean selected) {
        float density = getResources().getDisplayMetrics().density;
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(fillColor);
        if (selected) {
            d.setStroke((int) (2 * density), Color.WHITE);
        } else {
            // Petit stroke sombre pour que le swatch blanc reste visible sur fond foncé.
            d.setStroke((int) (1 * density), 0x55000000);
        }
        return d;
    }

    private void promptTextInput(float bitmapX, float bitmapY) {
        EditText input = new EditText(this);
        input.setHint(R.string.annotTextHint);
        input.setSingleLine(true);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.annotTextDialog)
                .setView(input)
                .setPositiveButton(R.string.save, (d, w) -> {
                    String text = input.getText().toString().trim();
                    if (!text.isEmpty()) {
                        annotateView.addText(bitmapX, bitmapY, text, swatchColors[selectedColorIndex]);
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .create();
        dialog.setOnShowListener(d -> {
            input.requestFocus();
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
        });
        dialog.show();
    }

    private void saveAnnotatedImage() {
        if (currentImageFile == null && currentImageUri == null) return;
        if (!annotateView.hasAnnotations()) {
            // Rien à aplatir, pas la peine de réécrire le fichier.
            finish();
            return;
        }
        Bitmap annotated = annotateView.getAnnotatedBitmap();
        if (annotated == null) {
            Toast.makeText(this, R.string.cropError, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Bitmap.CompressFormat format = isPngTarget()
                    ? Bitmap.CompressFormat.PNG
                    : Bitmap.CompressFormat.JPEG;
            try (OutputStream out = openTargetOutputStream()) {
                if (out == null) throw new java.io.IOException("null OutputStream");
                annotated.compress(format, 100, out);
                out.flush();
            }
            Toast.makeText(this, R.string.imageAnnotated, Toast.LENGTH_SHORT).show();
            setResult(RESULT_OK);
            finish();
        } catch (Exception e) {
            Toast.makeText(this, R.string.cropError, Toast.LENGTH_SHORT).show();
        } finally {
            if (annotated != null && annotated != sourceBitmap) annotated.recycle();
        }
    }

    private boolean isPngTarget() {
        if (currentImageFile != null) {
            return currentImageFile.getName().toLowerCase().endsWith(".png");
        }
        return true;
    }

    private OutputStream openTargetOutputStream() throws Exception {
        if (currentImageUri != null) {
            return getContentResolver().openOutputStream(currentImageUri, "w");
        }
        return new FileOutputStream(currentImageFile);
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    private void loadBanner() {
        if (adContainerView == null) return;
        adView = new AdView(this);
        adView.setAdUnitId(getString(R.string.banner));
        adView.setAdSize(getAdSize());
        adContainerView.removeAllViews();
        adContainerView.addView(adView);
        adView.loadAd(new AdRequest.Builder().build());
    }

    private AdSize getAdSize() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int adWidth = (int) (dm.widthPixels / dm.density);
        return AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(this, adWidth);
    }

    @Override
    protected void onPause() {
        if (adView != null) adView.pause();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (adView != null) adView.resume();
    }

    @Override
    protected void onDestroy() {
        if (adView != null) {
            adView.destroy();
            adView = null;
        }
        if (annotateView != null) annotateView.releaseResources();
        if (sourceBitmap != null && !sourceBitmap.isRecycled()) {
            sourceBitmap.recycle();
        }
        super.onDestroy();
    }
}
