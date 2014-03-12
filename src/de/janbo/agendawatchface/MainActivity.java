package de.janbo.agendawatchface;

import android.net.Uri;
import android.os.Bundle;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.text.format.DateUtils;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.TextView;

public class MainActivity extends Activity {
	private BroadcastReceiver serviceInfoReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			if (AgendaWatchfaceService.INTENT_ACTION_WATCHAPP_GIVE_INFO.equals(intent.getAction())) {
				TextView versionView = (TextView) findViewById(R.id.watchfaceVersionView); 
				TextView lastSyncView = (TextView) findViewById(R.id.lastSyncView);
				Button installButton = (Button) findViewById(R.id.updateButton);
				
				final int version = intent.getExtras().getInt(AgendaWatchfaceService.INTENT_EXTRA_WATCHAPP_VERSION, -1);
				final long lastSync = intent.getExtras().getLong(AgendaWatchfaceService.INTENT_EXTRA_WATCHAPP_LAST_SYNC, -1);
				
				//Set text views
				versionView.setText("Installed watchapp: "+(version == -1 ? "?" : 
					version >= AgendaWatchfaceService.CURRENT_WATCHAPP_VERSION_BUNDLED ? "up-to-date" : "outdated"));
				lastSyncView.setText("Last sync: "+(lastSync == -1 ? "?" : DateUtils.getRelativeTimeSpanString(MainActivity.this, lastSync)));
				
				//Set button
				installButton.setText(version == -1 && lastSync == -1 ? "Install watchapp" : //no indication that it's installed
					version == -1 ? "(Re-)install watchapp" : //last sync != -1 => it's installed, but version unknown
					version > AgendaWatchfaceService.CURRENT_WATCHAPP_VERSION_BUNDLED ? "Update android app" :
					version < AgendaWatchfaceService.CURRENT_WATCHAPP_VERSION_BUNDLED ? "Update watchapp" :
					"Reinstall watchapp"); //in this case version == CURRENT_BUNDLED
				installButton.setVisibility(version == AgendaWatchfaceService.CURRENT_WATCHAPP_VERSION_BUNDLED ? View.GONE : View.VISIBLE);
				installButton.setOnClickListener(new OnClickListener() {
					@Override
					public void onClick(View v) {
						if (version == -1 || version <= AgendaWatchfaceService.CURRENT_WATCHAPP_VERSION_BUNDLED) //send app to watch
							WatchappHandler.install(MainActivity.this);
						else { //offer updating this app
							Intent intent;
							try {
							    intent = new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + getPackageName()));
							} catch (android.content.ActivityNotFoundException anfe) {
							    intent = new Intent(Intent.ACTION_VIEW, Uri.parse("http://play.google.com/store/apps/details?id=" + getPackageName()));
							}
							startActivity(intent);
						}
					}
				});
			}
		}
	};
	
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);

		AgendaWatchfaceService.startWatchapp(getApplicationContext());
		startService(new Intent(MainActivity.this, AgendaWatchfaceService.class)); //kick off sync/start service
	}

	@Override
	protected void onResume() {
		super.onResume();
		
		//Register for info from the service
		IntentFilter filter = new IntentFilter();
		filter.addAction(AgendaWatchfaceService.INTENT_ACTION_WATCHAPP_GIVE_INFO);
		registerReceiver(serviceInfoReceiver, filter);
		
		//Ask service for news
		Intent intent = new Intent(AgendaWatchfaceService.INTENT_ACTION_WATCHAPP_REQUEST_INFO);
		sendBroadcast(intent);
	}
	
	@Override
	protected void onPause() {
		super.onPause();
		
		unregisterReceiver(serviceInfoReceiver);
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		getMenuInflater().inflate(R.menu.main, menu);
		return true;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {
		case R.id.action_install_watchapp:
			WatchappHandler.install(this);
			return true;
		case R.id.action_settings:
			startActivity(new Intent(this, SettingsActivity.class));
			return true;
		case R.id.action_show_appstore:
			Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("pebble://appstore/52e81244e822d1bdda00004a"));
			intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
			startActivity(intent);
			return true;
		default:
			return false;
		}
	}

}
