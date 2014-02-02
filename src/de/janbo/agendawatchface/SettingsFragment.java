package de.janbo.agendawatchface;

import android.os.Bundle;
import android.preference.CheckBoxPreference;
import android.preference.PreferenceFragment;
import android.preference.PreferenceScreen;

public class SettingsFragment extends PreferenceFragment {
	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		// Load the preferences from an XML resource
		addPreferencesFromResource(R.xml.preferences);

		//Populate the screen where the calendars can be picked
		PreferenceScreen calPickScreen = (PreferenceScreen) findPreference("pref_screen_cal_pick");
		for (CalendarInstance cal : CalendarReader.getCalendars(getActivity())) {
			CheckBoxPreference pref = new CheckBoxPreference(getActivity());
			pref.setDefaultValue(Boolean.TRUE);
			pref.setKey("pref_cal_"+cal.id+"_picked");
			pref.setTitle(cal.name);
			pref.setSummary(cal.account);
			
			calPickScreen.addPreference(pref);
		}
	}
}
