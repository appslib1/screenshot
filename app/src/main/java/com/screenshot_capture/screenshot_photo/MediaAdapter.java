package com.screenshot_capture.screenshot_photo;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;

import java.util.ArrayList;

/**
 * Adaptateur pour afficher les captures d'écran dans un GridView.
 */
public class MediaAdapter extends BaseAdapter {
    private final Context context;
    private final ArrayList<String> mediaList;

    public MediaAdapter(Context context, ArrayList<String> mediaList) {
        this.context = context;
        this.mediaList = mediaList;
    }

    @Override
    public int getCount() {
        return (mediaList != null) ? mediaList.size() : 0;
    }

    @Override
    public Object getItem(int position) {
        return mediaList.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ViewHolder holder;

        if (convertView == null) {
            // Inflation du layout pour chaque item de la grille
            convertView = LayoutInflater.from(context).inflate(R.layout.grid_item_layout, parent, false);

            holder = new ViewHolder();
            holder.imageView = convertView.findViewById(R.id.imageView);

            convertView.setTag(holder);
        } else {
            // Récupération du holder existant pour éviter les appels findViewById coûteux
            holder = (ViewHolder) convertView.getTag();
        }

        // Chargement de l'image avec Glide
        String imagePath = mediaList.get(position);

        Glide.with(context)
                .load(imagePath)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .placeholder(R.drawable.baseline_image_search_24) // Image affichée pendant le chargement
                .error(R.drawable.baseline_error_outline_24)      // Image affichée en cas d'erreur
                .centerCrop()
                .into(holder.imageView);

        return convertView;
    }

    // Pattern ViewHolder pour optimiser les performances de la liste
    private static class ViewHolder {
        ImageView imageView;
    }
}