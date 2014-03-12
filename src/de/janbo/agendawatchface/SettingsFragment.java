package de.janbo.agendawatchface;

import java.util.HashSet;
import java.util.Set;

import de.janbo.agendawatchface.api.AgendaWatchfacePlugin;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.Preference.OnPreferenceClickListener;
import android.preference.PreferenceCategory;
import android.preference.PreferenceFragment;
import android.util.Log;

public class SettingsFragment extends PreferenceFragment {
	protected Set<String> plugins = new HashSet<String>();
	
	BroadcastReceiver discoverer = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			final String pluginId = intent.getStringExtra(AgendaWatchfacePlugin.MAIN_SERVICE_INTENT_EXTRA_PLUGIN_ID);
			String pluginDisplayName = intent.getStringExtra(AgendaWatchfacePlugin.MAIN_SERVICE_INTENT_EXTRA_PLUGIN_NAME);
			int version = intent.getIntExtra(AgendaWatchfacePlugin.MAIN_SERVICE_INTENT_EXTRA_PLUGIN_VERSION, -1);
			if (version != AgendaWatchfacePlugin.PLUGIN_PROTOCOL_VERSION) {
				Log.e("Agenda Watchface Settings", pluginId+" doesn't match expected protocol version, ignoring it");
				return;
			}
			
			if (plugins.contains(pluginId))
				return;
			
			plugins.add(pluginId);
			
			Preference pref = new Preference(context);
			pref.setTitle(pluginDisplayName);
			pref.setOnPreferenceClickListener(new OnPreferenceClickListener() {
				@Override
				public boolean onPreferenceClick(Preference preference) {
					Intent settingBroadcastIntent = new Intent(AgendaWatchfacePlugin.INTENT_ACTION_AGENDA_PROVIDER);
					settingBroadcastIntent.putExtra(AgendaWatchfacePlugin.INTENT_EXTRA_REQUEST_TYPE, AgendaWatchfacePlugin.REQUEST_TYPE_SHOW_SETTINGS);
					settingBroadcastIntent.putExtra(AgendaWatchfacePlugin.INTENT_EXTRA_REQUEST_PLUGIN_ID, pluginId);
					settingBroadcastIntent.putExtra(AgendaWatchfacePlugin.INTENT_EXTRA_PROTOCOL_VERSION, AgendaWatchfacePlugin.PLUGIN_PROTOCOL_VERSION);
					
					getActivity().sendBroadcast(settingBroadcastIntent);
					return true;
				}
			});
			
			((PreferenceCategory) getPreferenceScreen().findPreference("pref_plugins_category")).addPreference(pref);
		}
	};
	
	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		// Load the preferences from an XML resource
		addPreferencesFromResource(R.xml.preferences);
		
		//Discover plugins
		plugins.clear();
		IntentFilter filter = new IntentFilter();
		filter.addAction(AgendaWatchfacePlugin.INTENT_ACTION_ACCEPT_DISCOVER);
		getActivity().registerReceiver(discoverer, filter);
		
		Intent intent = new Intent(AgendaWatchfacePlugin.INTENT_ACTION_AGENDA_PROVIDER);
		intent.putExtra(AgendaWatchfacePlugin.INTENT_EXTRA_PROTOCOL_VERSION, AgendaWatchfacePlugin.PLUGIN_PROTOCOL_VERSION);
		intent.putExtra(AgendaWatchfacePlugin.INTENT_EXTRA_REQUEST_TYPE, AgendaWatchfacePlugin.REQUEST_TYPE_DISCOVER);
		getActivity().sendBroadcast(intent);
	}
	
	@Override
	public void onDestroy() {
		super.onDestroy();
		
		getActivity().unregisterReceiver(discoverer);
	}
}
