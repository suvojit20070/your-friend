package com.suvo.agent.launch;

import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

import com.suvo.agent.AppGraph;
import com.suvo.agent.R;
import com.suvo.agent.character.FloatingCompanionService;
import com.suvo.agent.data.Settings;

/**
 * Your Friend — Original project by Suvo.
 *
 * Quick Settings tiles (pull down the notification shade → edit → drag in): "Ask" opens the
 * assistant card to type, "Talk" opens it listening, "Ask about screen" asks about what is on
 * screen right away, "Floating companion" switches the floating character on or off, "Replia"
 * switches chat auto reply on or off without opening the app.
 */
public final class Tiles {

    private Tiles() {
    }

    /** Opens an activity from a tile and closes the shade (unlocking first when needed). */
    @SuppressWarnings("deprecation")
    static void open(TileService s, Intent i, int code) {
        Runnable go = () -> {
            try {
                if (Build.VERSION.SDK_INT >= 34) s.startActivityAndCollapse(Entry.pending(s, code, i));
                else s.startActivityAndCollapse(i);
            } catch (RuntimeException ignored) {
            }
        };
        if (s.isLocked()) s.unlockAndRun(go);
        else go.run();
    }

    static void label(TileService s, int state) {
        Tile t = s.getQsTile();
        if (t == null) return;
        t.setState(state);
        t.updateTile();
    }

    public static final class Ask extends TileService {
        @Override
        public void onStartListening() {
            label(this, Tile.STATE_INACTIVE);
        }

        @Override
        public void onClick() {
            open(this, Entry.type(this), 60);
        }
    }

    public static final class Talk extends TileService {
        @Override
        public void onStartListening() {
            label(this, Tile.STATE_INACTIVE);
        }

        @Override
        public void onClick() {
            open(this, Entry.talk(this), 61);
        }
    }

    public static final class Screen extends TileService {
        @Override
        public void onStartListening() {
            label(this, Tile.STATE_INACTIVE);
        }

        @Override
        public void onClick() {
            open(this, Entry.screen(this), 62);
        }
    }

    /** Toggle: the floating character. Switching on opens the app when Android does not let it start from here. */
    public static final class Companion extends TileService {
        @Override
        public void onStartListening() {
            boolean on;
            try {
                on = AppGraph.get().settings.bool(Settings.FLOATING_COMPANION, true) && FloatingCompanionService.canRun(this);
            } catch (RuntimeException e) {
                on = false;
            }
            label(this, on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
            Tile t = getQsTile();
            if (t != null) {
                t.setIcon(Icon.createWithResource(this, R.drawable.ic_stat_companion));
                t.updateTile();
            }
        }

        @Override
        public void onClick() {
            AppGraph g = AppGraph.get();
            boolean on = !g.settings.bool(Settings.FLOATING_COMPANION, true);
            if (on && !FloatingCompanionService.canRun(this)) {
                open(this, new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:" + getPackageName())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), 63);
                return;
            }
            g.settings.put(Settings.FLOATING_COMPANION, on);
            if (on) {
                // Android only lets the floating character start while the app is in front:
                // open it once; the character appears as soon as you leave.
                if (!FloatingCompanionService.isRunning()) {
                    open(this, new Intent(this, com.suvo.agent.ui.MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), 64);
                }
            } else {
                FloatingCompanionService.stop(this);
            }
            label(this, on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        }
    }

    /** Asks Android to redraw the Replia tile (after auto reply was switched inside the app or by the agent). */
    public static void refreshAutoReply(android.content.Context ctx) {
        try {
            TileService.requestListeningState(ctx, new android.content.ComponentName(ctx, AutoReply.class));
        } catch (RuntimeException ignored) {
        }
    }

    /**
     * Toggle: chat auto reply ("Replia"). One tap on/off from the shade. Opens the Auto reply screen
     * only when something is missing (notification access, or no app picked yet).
     */
    public static final class AutoReply extends TileService {
        @Override
        public void onStartListening() {
            update();
        }

        private void update() {
            Tile t = getQsTile();
            if (t == null) return;
            boolean on, access;
            int apps = 0;
            try {
                on = AppGraph.get().settings.bool(com.suvo.agent.autoreply.AutoReplyStore.ENABLED, false);
                access = com.suvo.agent.autoreply.AutoReplyStore.hasAccess(this);
                for (com.suvo.agent.autoreply.AutoReplyStore.Rule r : com.suvo.agent.autoreply.AutoReplyStore.get(this).rules()) {
                    if (!com.suvo.agent.autoreply.AutoReplyStore.OFF.equals(r.mode)) apps++;
                }
            } catch (RuntimeException e) {
                on = false;
                access = false;
            }
            t.setState(on && access ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
            t.setIcon(Icon.createWithResource(this, R.drawable.ic_chat));
            t.setLabel(getString(R.string.tile_auto_reply));
            if (Build.VERSION.SDK_INT >= 29) {
                String sub;
                if (!access) sub = getString(R.string.tile_auto_reply_no_access);
                else if (!on) sub = getString(R.string.ar_off);
                else sub = getResources().getQuantityString(R.plurals.tile_auto_reply_apps, apps, apps);
                t.setSubtitle(sub);
            }
            t.updateTile();
        }

        @Override
        public void onClick() {
            AppGraph g = AppGraph.get();
            boolean on = !g.settings.bool(com.suvo.agent.autoreply.AutoReplyStore.ENABLED, false);
            g.settings.put(com.suvo.agent.autoreply.AutoReplyStore.ENABLED, on);
            update();
            if (on && (!com.suvo.agent.autoreply.AutoReplyStore.hasAccess(this)
                    || com.suvo.agent.autoreply.AutoReplyStore.get(this).rules().isEmpty())) {
                open(this, new Intent(this, com.suvo.agent.ui.AutoReplyActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), 65);
            }
        }
    }
}
