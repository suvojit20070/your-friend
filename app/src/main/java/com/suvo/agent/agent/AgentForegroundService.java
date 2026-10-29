package com.suvo.agent.agent;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import com.suvo.agent.AppGraph;
import com.suvo.agent.R;
import com.suvo.agent.ui.LiveAgentActivity;
import com.suvo.agent.ui.Notifications;
import com.suvo.agent.data.Settings;

/**
* Keeps the process alive while an agent task runs (a visible, user-stoppable foreground service)
* and holds a partial wake lock so the CPU keeps working with the screen off. Every task — typed,
* spoken, from the floating character or started by a schedule — goes through here. It stops
* itself when the task ends; the wake lock also has a time limit (the task's maximum duration plus
* a margin), so it can never keep the phone awake forever.
*/
public final class AgentForegroundService extends Service {
	
	public static final String ACTION_STOP_TASK = "com.suvo.agent.STOP_TASK";
	private static volatile String lastText = "";
	private android.os.PowerManager.WakeLock wakeLock;
	
	private android.os.PowerManager.WakeLock screenWakeLock;
	
	private final Settings.Listener settingsListener = key -> {
		if (Settings.KEEP_SCREEN_ON.equals(key)) {
			updateScreenWakeLock();
		}
	};
	
	public static void start(Context ctx, String text) {
		lastText = text;
		Intent i = new Intent(ctx, AgentForegroundService.class);
		try {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i);
			else ctx.startService(i);
		} catch (RuntimeException ignored) {
			// Background start restrictions: the task still runs while the process is alive.
		}
	}
	
	public static void update(Context ctx, String text) {
		lastText = text;
		NotificationManager nm = ctx.getSystemService(NotificationManager.class);
		if (nm != null) nm.notify(Notifications.ID_AGENT, build(ctx, text));
	}
	
	public static void stop(Context ctx) {
		ctx.stopService(new Intent(ctx, AgentForegroundService.class));
	}
	
	static Notification build(Context ctx, String text) {
		PendingIntent open = PendingIntent.getActivity(ctx, 10, new Intent(ctx, LiveAgentActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
		PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
		PendingIntent stop = PendingIntent.getService(ctx, 11, new Intent(ctx, AgentForegroundService.class).setAction(ACTION_STOP_TASK),
		PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
		return Notifications.builder(ctx, Notifications.CHANNEL_AGENT)
		.setSmallIcon(R.drawable.ic_stat_companion)
		.setContentTitle(ctx.getString(R.string.agent_working))
		.setContentText(text)
		.setOngoing(true)
		.setContentIntent(open)
		.addAction(new Notification.Action.Builder(null, ctx.getString(R.string.action_stop), stop).build())
		.build();
	}
	
	@Override
	public void onCreate() {
		super.onCreate();
		AppGraph.get().settings.addListener(settingsListener);
	}
	
	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		if (intent != null && ACTION_STOP_TASK.equals(intent.getAction())) {
			AppGraph.get().agent.cancel();
			stopSelf();
			return START_NOT_STICKY;
		}
		acquireWakeLock();
		Notification n = build(this, lastText);
		try {
			if (Build.VERSION.SDK_INT >= 34) {
				startForeground(Notifications.ID_AGENT, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
			} else {
				startForeground(Notifications.ID_AGENT, n);
			}
		} catch (RuntimeException e) {
			stopSelf();
		}
		return START_NOT_STICKY;
	}
	
	private void acquireWakeLock() {
		if (wakeLock != null && wakeLock.isHeld()) {
			updateScreenWakeLock();
			return;
		}
		android.os.PowerManager pm = getSystemService(android.os.PowerManager.class);
		if (pm == null) return;
		try {
			wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "YourFriend:agent-task");
			wakeLock.setReferenceCounted(false);
			long limit = AppGraph.get().settings.agentConfig().wakeLockMs();
			wakeLock.acquire(limit);
		} catch (RuntimeException ignored) {
			wakeLock = null;
		}
	}
	
	private void updateScreenWakeLock() {
		if (!AppGraph.get().settings.bool(Settings.KEEP_SCREEN_ON, false)) {
			releaseScreenWakeLock();
			return;
		}
		
		if (screenWakeLock != null && screenWakeLock.isHeld()) return;
		
		android.os.PowerManager pm =
		getSystemService(android.os.PowerManager.class);
		
		if (pm == null) return;
		
		try {
			int flags =
			android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK
			| android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP;
			
			screenWakeLock = pm.newWakeLock(
			flags,
			"YourFriend:keep-screen-on"
			);
			
			screenWakeLock.setReferenceCounted(false);
			
			long limit = AppGraph.get().settings.agentConfig().wakeLockMs();
			
			screenWakeLock.acquire(limit);
			
		} catch (RuntimeException ignored) {
			screenWakeLock = null;
		}
	}
	
	private void releaseScreenWakeLock() {
		if (screenWakeLock != null && screenWakeLock.isHeld()) {
			try {
				screenWakeLock.release();
			} catch (RuntimeException ignored) {
			}
		}
		
		screenWakeLock = null;
	}
	
	@Override
	public void onDestroy() {
		if (wakeLock != null && wakeLock.isHeld()) {
			try {
				wakeLock.release();
			} catch (RuntimeException ignored) {
			}
		}
		wakeLock = null;
		super.onDestroy();
	}
	
	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}
}
