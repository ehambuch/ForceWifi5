package de.erichambuch.forcewifi5;

import android.content.ClipData;
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

        private final ImageView networkImage;

        public ViewHolder(@NonNull View view) {
            super(view);
            textViewName = (TextView) view.findViewById(R.id.networktextviewName);
            textViewBSSID = (TextView) view.findViewById(R.id.networktextviewBSSID);
            networkImage = (ImageView) view.findViewById(R.id.networkimage1);
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
        public ImageView getNetworkImage() { return networkImage; }
    }

    /**
     * This class is used as LocalState to pass information for Drag&Drop.
     */
    static class WifiLocalState {
        final MainActivity.AccessPointEntry accessPointEntry;
        final View listView;

        WifiLocalState(View v, MainActivity.AccessPointEntry entry) {
            this.listView = v;
            this.accessPointEntry = entry;
        }
    }

    private final List<MainActivity.AccessPointEntry> networkEntries;
    private final WifiManager wifiManager;

    public CustomWifiListAdapter(@NonNull List<MainActivity.AccessPointEntry> networkEntries,
                                 @NonNull WifiManager wifiManager) {
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
        viewHolder.getTextViewName().setText(String.format("%s - %s", WifiUtils.unquoteSSid(entry.name), entry.bssid));
        final StringBuilder text = new StringBuilder(32);
        if(entry.frequencies != null)
            text.append(entry.frequencies);
        if(entry.signalLevel >= 0)
            text.append(" - ").append(WifiUtils.calculateWifiLevel(wifiManager, entry.signalLevel)).append(" %");
        viewHolder.getTextViewInformation().setText(text);
        int imageRes = R.drawable.android_wifi_3_bar_off_24px;
        if(entry.recommended) {
            imageRes = R.drawable.baseline_high_priority_24;
        } else {
            if (entry.signalLevel >= -50) {
                imageRes = R.drawable.android_wifi_4_bar_24px;
            } else if (entry.signalLevel >= -70) {
                imageRes = R.drawable.android_wifi_3_bar_24px;
            } else if (entry.signalLevel >= -80) {
                imageRes = R.drawable.wifi_2_bar_24px;
            } else if (entry.signalLevel >= -100) {
                imageRes = R.drawable.wifi_1_bar_24px;
            }
        }
        viewHolder.getNetworkImage().setImageResource(imageRes);
        viewHolder.itemView.setTag(entry);
        // for drag & drop
        viewHolder.itemView.setOnLongClickListener(v -> {
            View.DragShadowBuilder myShadow = new View.DragShadowBuilder(v);
            // we pass the AccessPointEntry object directly with drag&drop as LocalState
            v.startDragAndDrop(ClipData.newPlainText(entry.name,""), myShadow,
                    new WifiLocalState(v, (MainActivity.AccessPointEntry) v.getTag()), 0);
            return true;
        });
    }

    // Return the size of your dataset (invoked by the layout manager)
    @Override
    public int getItemCount() {
        return networkEntries.size();
    }

    void removeItem(MainActivity.AccessPointEntry item) {
        int position = networkEntries.indexOf(item);
        if (position != -1) {
            networkEntries.remove(position);
            notifyItemRemoved(position);
        }
    }

    void addItem(MainActivity.AccessPointEntry item) {
        networkEntries.add(item);
        notifyItemInserted(networkEntries.size() - 1);
    }
}
