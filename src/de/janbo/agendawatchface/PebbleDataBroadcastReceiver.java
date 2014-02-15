package de.janbo.agendawatchface;

import static com.getpebble.android.kit.Constants.APP_UUID;
import static com.getpebble.android.kit.Constants.MSG_DATA;
import static com.getpebble.android.kit.Constants.TRANSACTION_ID;

import java.util.UUID;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Sifts through data from watch, filters the ones destined for our app and delegates to the PebbleCommService
 * @author Jan
 *
 */
public class PebbleDataBroadcastReceiver extends BroadcastReceiver {

	@Override
	public void onReceive(final Context context, final Intent intent) {
		final UUID receivedUuid = (UUID) intent.getSerializableExtra(APP_UUID);

		// Pebble-enabled apps are expected to be good citizens and only inspect broadcasts
		// containing their UUID
		if (!PebbleCommService.PEBBLE_APP_UUID.equals(receivedUuid)) {
			return;
		}

		final int transactionId = intent.getIntExtra(TRANSACTION_ID, -1);
		final String jsonData = intent.getStringExtra(MSG_DATA);
		if (jsonData == null || jsonData.isEmpty()) {
			return;
		}

		// Redirect request to the service
		PebbleCommService.handleReceivedData(context, jsonData, transactionId);
	}

}
