package de.janbo.agendawatchface;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;

public class SettingsActivity extends Activity {
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		// Display the fragment as the main content.
		getFragmentManager().beginTransaction().replace(android.R.id.content, new SettingsFragment()).commit();
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();

		startService(new Intent(this, PebbleCommService.class)); //kick off update after leaving the activity
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		// Inflate the menu items for use in the action bar
		getMenuInflater().inflate(R.menu.settingmenu, menu);

		return super.onCreateOptionsMenu(menu);
	}
	
	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {
		case R.id.menu_apply_settings:
			startService(new Intent(this, PebbleCommService.class)); //user wants to apply settings, so kick off watch update
			return true;
		default:
		}
		return super.onOptionsItemSelected(item);
	}
}
