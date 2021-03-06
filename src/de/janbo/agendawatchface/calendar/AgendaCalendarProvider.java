package de.janbo.agendawatchface.calendar;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import de.janbo.agendawatchface.api.AgendaWatchfacePlugin;

public class AgendaCalendarProvider extends AgendaWatchfacePlugin {

	@Override
	public String getPluginId() {
		return "de.janbo.agendawatchface.calendar";
	}

	@Override
	public String getPluginDisplayName() {
		return "Calendar Events";
	}

	@Override
	public void onRefreshRequest(Context context) {
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
		
		//Apply old global pref_layout_countdown setting if it's set from an old version of this app. Only done once.
		if (prefs.contains("pref_layout_countdown")) {
			boolean state = prefs.getBoolean("pref_layout_countdown", false);
			prefs.edit()
				.putBoolean("pref_layout_countdown_1", state)
				.putBoolean("pref_layout_countdown_2", state)
				.remove("pref_layout_countdown")
				.commit();
		}
		
		if (!prefs.getBoolean("pref_key_cal_activate", true))
			return;
		context.startService(new Intent(context, AgendaCalendarService.class));
	}

	@Override
	public void onShowSettingsRequest(Context context) {
		Intent intent = new Intent(context, AgendaCalendarSettingsActivity.class);
		intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
		context.startActivity(intent);
	}
}
