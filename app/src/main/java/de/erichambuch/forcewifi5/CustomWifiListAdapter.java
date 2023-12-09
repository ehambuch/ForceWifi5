package de.erichambuch.forcewifi5;

import android.graphics.drawable.AnimationDrawable;
import android.net.wifi.WifiManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

/**
 * Adapter for RecyclerView (ListView).
 */
public class CustomWifiListAdapter extends RecyclerView.Adapter<CustomWifiListAdapter.ViewHolder> {

    public static class ViewHolder extends RecyclerView.ViewHolder {
        private final TextView textViewName;
        private final TextView textViewBSSID;
        private final ImageView image1;
        private final ImageView image2;

        public ViewHolder(@NonNull View view) {
            super(view);
            textViewName = (TextView) view.findViewById(R.id.networktextviewName);
            textViewBSSID = (TextView) view.findViewById(R.id.networktextviewBSSID);
            image1 = view.findViewById(R.id.networkimage1);
            image2 = view.findViewById(R.id.networkimage2);
        }

        @NonNull
        public TextView getTextViewName() {
            return textViewName;
        }

        @NonNull
        public TextView getTextViewInformation() {
            return textViewBSSID;
        }

        @NonNull
        public ImageView getImageView1() {
            return image1;
        }

        @NonNull
        public ImageView getImageView2() {
            return image2;
        }

        public void setConnected(boolean connected) {
            image1.setVisibility(connected ? View.VISIBLE : View.INVISIBLE);
        }


        public void setRecommended(boolean i) {
            image2.setVisibility(i ? View.VISIBLE : View.INVISIBLE);
            final boolean connected = image1.getVisibility() == View.VISIBLE;
            if(connected) {
                image2.setBackgroundResource(R.drawable.baseline_high_priority_24);
            } else if (i) { // if recommended, but not connected yet -> flash red/green to indicate open change of network
                image2.setBackgroundResource(R.drawable.animated_priority);
                ((AnimationDrawable) image2.getBackground()).start();
            }
        }
    }

    private final List<MainActivity.AccessPointEntry> networkEntries;
    private final WifiManager wifiManager;

    public CustomWifiListAdapter(@NonNull List<MainActivity.AccessPointEntry> networkEntries, @NonNull WifiManager wifiManager) {
        this.networkEntries = networkEntries;
        this.wifiManager = wifiManager;
    }

    // Create new views (invoked by the layout manager)
    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup viewGroup, int viewType) {
        // Create a new view, which defines the UI of the list item
        View view = LayoutInflater.from(viewGroup.getContext())
                .inflate(R.layout.list_view_entry, viewGroup, false);

        return new ViewHolder(view);
    }

    // Replace the contents of a view (invoked by the layout manager)
    @Override
    public void onBindViewHolder(@NonNull ViewHolder viewHolder, final int position) {
        MainActivity.AccessPointEntry entry = networkEntries.get(position);
        viewHolder.getTextViewName().setText(entry.name);
        final StringBuilder text = new StringBuilder(32);
        text.append(entry.bssid).append(" - ").append(entry.frequency).append(" MHz");
        text.append(" - ").append(WifiUtils.calculateWifiLevel(wifiManager, entry.signalLevel)).append(" %");
        viewHolder.getTextViewInformation().setText(text);
        viewHolder.setConnected(entry.connected);
        viewHolder.setRecommended(entry.recommended);
    }

    // Return the size of your dataset (invoked by the layout manager)
    @Override
    public int getItemCount() {
        return networkEntries.size();
    }
}
