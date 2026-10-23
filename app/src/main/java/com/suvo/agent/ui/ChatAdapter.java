package com.suvo.agent.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.suvo.agent.R;

import java.util.ArrayList;
import java.util.List;

public class ChatAdapter extends RecyclerView.Adapter<ChatAdapter.VH> {

    private final List<ChatMsg> items = new ArrayList<>();

    public void add(ChatMsg m) {
        items.add(m);
        notifyItemInserted(items.size() - 1);
    }

    /** Append/replace streaming text on the last Aura bubble. */
    public void updateLastAura(String text) {
        for (int i = items.size() - 1; i >= 0; i--) {
            if (items.get(i).type == ChatMsg.AURA) {
                items.get(i).text = text;
                notifyItemChanged(i);
                return;
            }
        }
    }

    public int size() { return items.size(); }

    @Override public int getItemViewType(int pos) { return items.get(pos).type; }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        int layout = viewType == ChatMsg.USER ? R.layout.item_msg_user
                : viewType == ChatMsg.TOOL ? R.layout.item_msg_tool
                : R.layout.item_msg_aura;
        View v = LayoutInflater.from(parent.getContext()).inflate(layout, parent, false);
        return new VH(v);
    }

    @Override public void onBindViewHolder(@NonNull VH h, int pos) {
        h.text.setText(items.get(pos).text);
    }

    @Override public int getItemCount() { return items.size(); }

    static class VH extends RecyclerView.ViewHolder {
        final TextView text;
        VH(View v) { super(v); text = v.findViewById(R.id.msgText); }
    }
}
