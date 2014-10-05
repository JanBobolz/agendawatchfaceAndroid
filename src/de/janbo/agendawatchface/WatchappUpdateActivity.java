package de.janbo.agendawatchface;

import android.app.Activity;
import android.os.Bundle;
import android.preference.PreferenceManager;

/**
 * Very simple activity that starts, triggers the watchapp update, then finishes itself
 * @author Jan
 *
 */
public class WatchappUpdateActivity extends Activity {
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		PreferenceManager.getDefaultSharedPreferences(this).edit().putInt("tried_install_version", AgendaWatchfaceService.CURRENT_WATCHAPP_VERSION_BUNDLED).commit();
		
		WatchappHandler.install(this);
		finish();
	}
}
