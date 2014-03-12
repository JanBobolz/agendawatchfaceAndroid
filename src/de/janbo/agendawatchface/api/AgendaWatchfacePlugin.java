package de.janbo.agendawatchface.api;

import java.util.ArrayList;
import java.util.List;


import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Parcelable;
import android.util.Log;

public abstract class AgendaWatchfacePlugin extends BroadcastReceiver {
	/**
	 * The intent action that this receiver must be registered for
	 */
	public static final String INTENT_ACTION_AGENDA_PROVIDER = "de.janbo.agendawatchface.intent.action.provider";
	
	//Internal constants
	public static final String INTENT_EXTRA_PROTOCOL_VERSION = "de.janbo.agendawatchface.intent.extra.protversion";
	public static final String INTENT_EXTRA_REQUEST_TYPE = "de.janbo.agendawatchface.intent.extra.requesttype";
	public static final int REQUEST_TYPE_DISCOVER = 1;
	public static final int REQUEST_TYPE_REFRESH = 2;
	public static final int REQUEST_TYPE_SHOW_SETTINGS = 3;
	public static final String INTENT_EXTRA_REQUEST_PLUGIN_ID = "de.janbo.agendwatchface.intent.extra.requestpluginid";
	//Intent actions for the main service
	public static final String INTENT_ACTION_ACCEPT_DISCOVER = "de.janbo.agendawatchface.intent.action.acceptdiscovery";
	public static final String MAIN_SERVICE_INTENT_EXTRA_PLUGIN_ID = "de.janbo.agendawatchface.intent.extra.pluginid";
	public static final String MAIN_SERVICE_INTENT_EXTRA_PLUGIN_NAME = "de.janbo.agendawatchface.intent.extra.pluginname";
	public static final String MAIN_SERVICE_INTENT_EXTRA_PLUGIN_VERSION = "de.janbo.agendawatchface.intent.extra.pluginprotocolversion";
	public static final String MAIN_SERVICE_INTENT_ACTION_ACCEPT_DATA = "de.janbo.agendawatchface.intent.action.acceptdata";
	public static final String MAIN_SERVICE_INTENT_EXTRA_DATA = "de.janbo.agendawatchface.intent.extra.plugindata";

	
	/**
	 * Version of the plugin protocol implemented
	 */
	public static final int PLUGIN_PROTOCOL_VERSION = 1;
	
	public AgendaWatchfacePlugin() {
		super();
	}
	
	@Override
	public void onReceive(Context context, Intent intent) {
		if (INTENT_ACTION_AGENDA_PROVIDER.equals(intent.getAction())) {
			if (intent.getIntExtra(INTENT_EXTRA_PROTOCOL_VERSION, 0) != PLUGIN_PROTOCOL_VERSION) {
				Log.e("AgendaWatchfacePlugin", getPluginId()+" or the app seem to be outdated");
				return;
			}
			
			switch (intent.getIntExtra(INTENT_EXTRA_REQUEST_TYPE, -1)) {
			case REQUEST_TYPE_DISCOVER:
				Log.d("AgendaWatchfacePlugin", "Discovering "+getPluginId());
				Intent reply = new Intent(INTENT_ACTION_ACCEPT_DISCOVER);
				//reply.setClassName("de.janbo.agendawatchface", "AgendaWatchfaceService");
				reply.putExtra(MAIN_SERVICE_INTENT_EXTRA_PLUGIN_ID, getPluginId());
				reply.putExtra(MAIN_SERVICE_INTENT_EXTRA_PLUGIN_NAME, getPluginDisplayName());
				reply.putExtra(MAIN_SERVICE_INTENT_EXTRA_PLUGIN_VERSION, PLUGIN_PROTOCOL_VERSION);
				context.sendBroadcast(reply);
				break;
				
			case REQUEST_TYPE_REFRESH:
				Log.d("AgendaWatchfacePlugin", "Calling onRefreshRequest() on "+getPluginId()+" - expecting plugin to answer via publishData()");
				onRefreshRequest(context);
				break;
			
			case REQUEST_TYPE_SHOW_SETTINGS:
				if (intent.getStringExtra(INTENT_EXTRA_REQUEST_PLUGIN_ID).equals(getPluginId()))
					onShowSettingsRequest(context);
				break;
				
			default:
				Log.e("AgendaWatchfacePlugin", "invalid request type "+intent.getIntExtra(INTENT_EXTRA_REQUEST_TYPE, -1));
			}
		}
	}
	
	public void publishData(Context context, List<AgendaItem> items) {
		if (items == null)
			items = new ArrayList<AgendaItem>();
		
		Log.d("AgendaWatchfacePlugin", "publishing "+items.size()+" items for "+getPluginId());
		Intent dataIntent = new Intent(MAIN_SERVICE_INTENT_ACTION_ACCEPT_DATA);
		dataIntent.setClassName("de.janbo.agendawatchface", "de.janbo.agendawatchface.AgendaWatchfaceService");
		dataIntent.putExtra(MAIN_SERVICE_INTENT_EXTRA_PLUGIN_ID, getPluginId());
		dataIntent.putExtra(MAIN_SERVICE_INTENT_EXTRA_PLUGIN_VERSION, PLUGIN_PROTOCOL_VERSION);
		
		Parcelable[] parcelItems = new Parcelable[items.size()];
		for (int i=0; i<items.size();i++)
			parcelItems[i] = items.get(i).toBundle();
		dataIntent.putExtra(MAIN_SERVICE_INTENT_EXTRA_DATA, parcelItems);

		context.startService(dataIntent);
	}
	
	public abstract String getPluginId();
	public abstract String getPluginDisplayName();
	public abstract void onRefreshRequest(Context context);
	public abstract void onShowSettingsRequest(Context context);
}
