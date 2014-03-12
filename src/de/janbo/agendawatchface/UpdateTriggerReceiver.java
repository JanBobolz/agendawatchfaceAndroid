package de.janbo.agendawatchface;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Broadcast Receiver for any broadcasts that should trigger the service to refresh its data
 * @author Jan
 *
 */
public class UpdateTriggerReceiver extends BroadcastReceiver {
	private static String REGULAR_ALARM_ACTION = "de.janbo.agendawatchface.intent.action.regularalarm";
	
	@Override
	public void onReceive(Context context, Intent intent) {
		if (!REGULAR_ALARM_ACTION.equals(intent.getAction()))
			setRegularAlarm(context);
		
		Intent serviceIntent = new Intent(context, AgendaWatchfaceService.class);
		serviceIntent.setAction(AgendaWatchfaceService.INTENT_ACTION_REFRESH_PLUGIN_DATA);
		context.startService(serviceIntent);
	}
	
	public static void setRegularAlarm(Context context) {
		AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
		Intent intent =  new Intent(context, UpdateTriggerReceiver.class);
		intent.setAction(REGULAR_ALARM_ACTION);
		PendingIntent pendingIntent = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_CANCEL_CURRENT);
		alarmManager.setInexactRepeating(AlarmManager.RTC_WAKEUP, System.currentTimeMillis()+AgendaWatchfaceService.PLUGIN_SYNC_INTERVAL*1000*60, AgendaWatchfaceService.PLUGIN_SYNC_INTERVAL*1000*60, pendingIntent);
	}
}
