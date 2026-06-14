package com.screenshot_capture.screenshot_photo;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.Toast;
import androidx.core.content.ContextCompat;

public class RatingModal {
    private final Context context;
    private int selectedStars = 5;
    private final int[] starIds = {
            R.id.star1, R.id.star2, R.id.star3, R.id.star4, R.id.star5
    };

    public RatingModal(Context context) {
        this.context = context;
    }

    public void openRatingDialog() {
        final Dialog dialog = new Dialog(this.context);
        dialog.setContentView(R.layout.rating_modal);

        final ImageButton[] starButtons = new ImageButton[5];

        // Configuration des boutons d'étoiles
        for (int i = 0; i < 5; i++) {
            final int index = i; // Nécessaire pour la capture dans le listener
            starButtons[i] = dialog.findViewById(this.starIds[i]);

            starButtons[i].setOnClickListener(v -> {
                updateStarUI(starButtons, index + 1);
            });
        }

        // Bouton de validation
        Button submitButton = dialog.findViewById(R.id.submitRatingButton);
        submitButton.setOnClickListener(v -> {
            // Logique de redirection : 4-5 étoiles -> Play Store, < 4 -> Feedback Email
            if (this.selectedStars >= 4) {
                // Opening Play Store backgrounds the app → skip the next App Open ad.
                AppOpenAdManager.disableNext();
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

        // Initialisation par défaut (5 étoiles)
        updateStarUI(starButtons, this.selectedStars);

        dialog.show();
    }

    private void updateStarUI(ImageButton[] buttons, int count) {
        this.selectedStars = count;
        for (int i = 0; i < buttons.length; i++) {
            int drawableId = (i < count) ? R.drawable.baseline_star_24 : R.drawable.baseline_star_outline_24;
            buttons[i].setImageDrawable(ContextCompat.getDrawable(this.context, drawableId));
        }
    }

    private void openEmailIntent() {
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("message/rfc822");
        intent.putExtra(Intent.EXTRA_EMAIL, new String[]{"appslib.contact@gmail.com"});
        intent.putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.feedbackForApp));

        // On essaye d'ouvrir spécifiquement Gmail si présent
        intent.setPackage("com.google.android.gm");

        try {
            AppOpenAdManager.disableNext();
            context.startActivity(intent);
        } catch (Exception e) {
            // Si Gmail n'est pas installé, on retire la contrainte de package pour laisser le choix
            intent.setPackage(null);
            try {
                AppOpenAdManager.disableNext();
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

        AppOpenAdManager.disableNext();
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.shareVia)));
    }
}