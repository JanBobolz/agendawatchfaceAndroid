package de.janbo.agendawatchface;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Broadcast Receiver for any broadcasts that should trigger a calendar sync with the watch
 * @author Jan
 *
 */
public class UpdateTriggerReceiver extends BroadcastReceiver {

	@Override
	public void onReceive(Context context, Intent intent) {
		context.startService(new Intent(context, PebbleCommService.class));
	}

}
