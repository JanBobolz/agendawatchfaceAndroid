package de.janbo.agendawatchface.calendar;

import android.app.Activity;
import android.os.Bundle;

public class AgendaCalendarSettingsActivity extends Activity {
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		// Display the fragment as the main content.
		getFragmentManager().beginTransaction().replace(android.R.id.content, new AgendaCalendarSettingsFragment()).commit();
	}
}
