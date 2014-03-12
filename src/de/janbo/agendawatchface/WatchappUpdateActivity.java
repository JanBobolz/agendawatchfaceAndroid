package de.janbo.agendawatchface;

import android.app.Activity;
import android.os.Bundle;

/**
 * Very simple activity that starts, triggers the watchapp update, then finishes itself
 * @author Jan
 *
 */
public class WatchappUpdateActivity extends Activity {
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		
		WatchappHandler.install(this);
		finish();
	}
}
