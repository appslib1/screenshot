package com.screenshot_capture.screenshot_photo;

import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.net.Uri;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.core.content.ContextCompat;

import com.google.android.material.bottomsheet.BottomSheetDialog;

public class RatingModal {
    private final Context context;
    // 0 = aucune note choisie : le bouton reste inactif tant que l'utilisateur n'a pas cliqué.
    private int selectedStars = 0;
    private final int[] starIds = {
            R.id.star1, R.id.star2, R.id.star3, R.id.star4, R.id.star5
    };

    private ImageView face;
    private TextView title;
    private TextView subtitle;
    private Button submitButton;

    public RatingModal(Context context) {
        this.context = context;
    }

    public void openRatingDialog() {
        final BottomSheetDialog dialog = new BottomSheetDialog(this.context);
        dialog.setContentView(R.layout.rating_modal);
        // Le conteneur du bottom sheet est laissé transparent : ce sont les coins arrondis
        // du layout qui donnent la forme de la modale.
        dialog.setOnShowListener(d -> {
            View sheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (sheet != null) sheet.setBackgroundColor(Color.TRANSPARENT);
        });

        face = dialog.findViewById(R.id.ratingFace);
        title = dialog.findViewById(R.id.ratingTitle);
        subtitle = dialog.findViewById(R.id.ratingSubtitle);

        final ImageButton[] starButtons = new ImageButton[5];

        // Configuration des boutons d'étoiles
        for (int i = 0; i < 5; i++) {
            final int index = i; // Nécessaire pour la capture dans le listener
            starButtons[i] = dialog.findViewById(this.starIds[i]);

            starButtons[i].setOnClickListener(v -> {
                updateStarUI(starButtons, index + 1);
            });
        }

        ImageButton closeButton = dialog.findViewById(R.id.closeRatingButton);
        if (closeButton != null) closeButton.setOnClickListener(v -> dialog.dismiss());

        // Bouton de validation
        submitButton = dialog.findViewById(R.id.submitRatingButton);
        submitButton.setOnClickListener(v -> {
            // Logique de redirection : 4-5 étoiles -> Play Store, < 4 -> Feedback Email
            if (this.selectedStars >= 4) {
                try {
                    context.startActivity(new Intent(Intent.ACTION_VIEW,
                            Uri.parse("market://details?id=" + context.getPackageName())));
                } catch (Exception e) {
                    context.startActivity(new Intent(Intent.ACTION_VIEW,
                            Uri.parse("https://play.google.com/store/apps/details?id=" + context.getPackageName())));
                }
            } else {
                openEmailIntent();
            }
            dialog.dismiss();
        });

        // État initial : aucune étoile sélectionnée
        updateStarUI(starButtons, this.selectedStars);

        dialog.show();
    }

    private void updateStarUI(ImageButton[] buttons, int count) {
        this.selectedStars = count;

        // Rouge tant que la note est mauvaise, ambre dès 4 étoiles.
        int filledColor = ContextCompat.getColor(this.context,
                count >= 4 ? R.color.ratingAmber : R.color.ratingRed);
        int emptyColor = ContextCompat.getColor(this.context, R.color.ratingStarEmpty);

        for (int i = 0; i < buttons.length; i++) {
            boolean filled = i < count;
            int drawableId = filled ? R.drawable.baseline_star_24 : R.drawable.baseline_star_outline_24;
            buttons[i].setImageDrawable(ContextCompat.getDrawable(this.context, drawableId));
            buttons[i].setImageTintList(ColorStateList.valueOf(filled ? filledColor : emptyColor));
        }

        updateMoodUI(count);
    }

    /** Visage, titre, sous-titre et état du bouton suivent la note sélectionnée. */
    private void updateMoodUI(int count) {
        if (count == 0) {
            face.setImageResource(R.drawable.ic_face_star);
            title.setText(R.string.rateTitleIdle);
            subtitle.setVisibility(View.GONE);
        } else if (count >= 4) {
            // Yeux en cœur réservés à la note maximale.
            face.setImageResource(count == 5 ? R.drawable.ic_face_love : R.drawable.ic_face_happy);
            title.setText(R.string.rateTitleHigh);
            subtitle.setText(R.string.rateSubtitleHigh);
            subtitle.setVisibility(View.VISIBLE);
        } else {
            face.setImageResource(count == 3 ? R.drawable.ic_face_neutral : R.drawable.ic_face_sad);
            title.setText(R.string.rateTitleLow);
            subtitle.setText(R.string.rateSubtitleLow);
            subtitle.setVisibility(View.VISIBLE);
        }
        submitButton.setEnabled(count > 0);
    }

    private void openEmailIntent() {
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("message/rfc822");
        intent.putExtra(Intent.EXTRA_EMAIL, new String[]{"appslib.contact@gmail.com"});
        intent.putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.feedbackForApp));

        // On essaye d'ouvrir spécifiquement Gmail si présent
        intent.setPackage("com.google.android.gm");

        try {
            context.startActivity(intent);
        } catch (Exception e) {
            // Si Gmail n'est pas installé, on retire la contrainte de package pour laisser le choix
            intent.setPackage(null);
            try {
                context.startActivity(Intent.createChooser(intent, "Send feedback..."));
            } catch (Exception ex) {
                Toast.makeText(context, context.getString(R.string.noEmail), Toast.LENGTH_SHORT).show();
            }
        }
    }

    public void shareApp() {
        String shareMessage = context.getString(R.string.shareApp) +
                "\n\nhttps://play.google.com/store/apps/details?id=" + context.getPackageName();

        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.checkoutApp));
        intent.putExtra(Intent.EXTRA_TEXT, shareMessage);

        context.startActivity(Intent.createChooser(intent, context.getString(R.string.shareVia)));
    }
}