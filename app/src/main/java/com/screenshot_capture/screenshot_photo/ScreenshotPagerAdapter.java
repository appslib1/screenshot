package com.screenshot_capture.screenshot_photo;

import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import com.bumptech.glide.signature.ObjectKey;

import java.io.File;
import java.util.List;

/**
 * Adapter for swiping through full-screen screenshots inside a ViewPager2.
 * Images are cached (memory + disk); a circular loader is shown until the
 * image is ready so the user never sees a raw placeholder icon.
 */
public class ScreenshotPagerAdapter extends RecyclerView.Adapter<ScreenshotPagerAdapter.PageViewHolder> {

    private final List<String> mediaList;

    public ScreenshotPagerAdapter(List<String> mediaList) {
        this.mediaList = mediaList;
    }

    @NonNull
    @Override
    public PageViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_screenshot_page, parent, false);
        return new PageViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull PageViewHolder holder, int position) {
        String imagePath = mediaList.get(position);

        // Un item est soit un chemin de fichier (capture de l'appli), soit une URI
        // content:// (capture de l'appareil via MediaStore). Glide charge les deux ;
        // la signature de cache diffère selon la source.
        Object loadModel;
        ObjectKey signature;
        if (imagePath.startsWith("content://")) {
            loadModel = android.net.Uri.parse(imagePath);
            signature = new ObjectKey(imagePath);
        } else {
            File file = new File(imagePath);
            loadModel = file;
            // Cache by content: the key changes only when the file is modified
            // (e.g. after a crop), so cached images load instantly otherwise.
            signature = new ObjectKey(file.lastModified());
        }

        holder.progressBar.setVisibility(View.VISIBLE);

        Glide.with(holder.imageView.getContext())
                .load(loadModel)
                .signature(signature)
                .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                .error(R.drawable.baseline_error_outline_24)
                .fitCenter()
                .listener(new RequestListener<Drawable>() {
                    @Override
                    public boolean onLoadFailed(@Nullable GlideException e, Object model,
                                                Target<Drawable> target, boolean isFirstResource) {
                        holder.progressBar.setVisibility(View.GONE);
                        return false;
                    }

                    @Override
                    public boolean onResourceReady(Drawable resource, Object model,
                                                   Target<Drawable> target, DataSource dataSource,
                                                   boolean isFirstResource) {
                        holder.progressBar.setVisibility(View.GONE);
                        return false;
                    }
                })
                .into(holder.imageView);
    }

    @Override
    public int getItemCount() {
        return mediaList.size();
    }

    static class PageViewHolder extends RecyclerView.ViewHolder {
        final ImageView imageView;
        final ProgressBar progressBar;

        PageViewHolder(@NonNull View itemView) {
            super(itemView);
            imageView = itemView.findViewById(R.id.pageImageView);
            progressBar = itemView.findViewById(R.id.pageProgress);
        }
    }
}
