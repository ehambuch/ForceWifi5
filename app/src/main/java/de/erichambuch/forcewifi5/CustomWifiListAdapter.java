package de.erichambuch.forcewifi5;

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

        public ViewHolder(View view) {
            super(view);
            textViewName = (TextView) view.findViewById(R.id.networktextviewName);
            textViewBSSID = (TextView) view.findViewById(R.id.networktextviewBSSID);
            image1 = view.findViewById(R.id.networkimage1);
            image2 = view.findViewById(R.id.networkimage2);
        }

        public TextView getTextViewName() {
            return textViewName;
        }

        public TextView getTextViewInformation() {
            return textViewBSSID;
        }

        public ImageView getImageView1() {
            return image1;
        }

        public ImageView getImageView2() {
            return image2;
        }
    }

    private List<MainActivity.AccessPointEntry> networkEntries;

    public CustomWifiListAdapter(@NonNull List<MainActivity.AccessPointEntry> networkEntries) {
        this.networkEntries = networkEntries;
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
    public void onBindViewHolder(ViewHolder viewHolder, final int position) {
        MainActivity.AccessPointEntry entry = networkEntries.get(position);
        viewHolder.getTextViewName().setText(entry.name);
        viewHolder.getTextViewInformation().setText(entry.bssid + " - "+entry.frequency + " MHz");
        viewHolder.getImageView1().setVisibility(entry.connected ? View.VISIBLE : View.INVISIBLE);
        viewHolder.getImageView2().setVisibility(entry.recommended ? View.VISIBLE : View.INVISIBLE);
    }

    // Return the size of your dataset (invoked by the layout manager)
    @Override
    public int getItemCount() {
        return networkEntries.size();
    }
}
