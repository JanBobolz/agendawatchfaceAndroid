package de.janbo.agendawatchface.calendar;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.CheckBoxPreference;
import android.preference.PreferenceFragment;
import android.preference.PreferenceScreen;
import de.janbo.agendawatchface.CalendarInstance;
import de.janbo.agendawatchface.R;

public class AgendaCalendarSettingsFragment extends PreferenceFragment {
	/**
	 * Listener for preference changes. Will issue the Calendar provider to update its data
	 */
	SharedPreferences.OnSharedPreferenceChangeListener listener = new SharedPreferences.OnSharedPreferenceChangeListener() {
		public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
			Intent intent = new Intent(getActivity().getApplicationContext(), AgendaCalendarService.class);
			getActivity().startService(intent);
		}
	};
	
	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		// Load the preferences from an XML resource
		addPreferencesFromResource(R.xml.calpreferences);

		//Populate the screen where the calendars can be picked
		PreferenceScreen calPickScreen = (PreferenceScreen) findPreference("pref_screen_cal_pick");
		for (CalendarInstance cal : AgendaCalendarService.getCalendars(getActivity())) {
			CheckBoxPreference pref = new CheckBoxPreference(getActivity());
			pref.setDefaultValue(Boolean.TRUE);
			pref.setKey("pref_cal_"+cal.id+"_picked");
			pref.setTitle(cal.name);
			pref.setSummary(cal.account);
			
			calPickScreen.addPreference(pref);
		}
	}
	
	@Override
	public void onResume() {
		super.onResume();
		getPreferenceManager().getSharedPreferences().registerOnSharedPreferenceChangeListener(listener);
	}

	@Override
	public void onPause() {
		super.onPause();
		getPreferenceManager().getSharedPreferences().unregisterOnSharedPreferenceChangeListener(listener);
	}
}
