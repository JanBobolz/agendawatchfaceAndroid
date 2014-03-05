package de.janbo.agendawatchface;

import java.util.List;
import java.util.UUID;

import org.json.JSONException;

import com.getpebble.android.kit.PebbleKit;
import com.getpebble.android.kit.PebbleKit.PebbleAckReceiver;
import com.getpebble.android.kit.PebbleKit.PebbleNackReceiver;
import com.getpebble.android.kit.util.PebbleDictionary;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.support.v4.app.NotificationCompat;
import android.util.Log;

/**
 * Service that handles communication with the watch.
 * 
 * @author Jan
 */
public class PebbleCommService extends Service {
	public static final UUID PEBBLE_APP_UUID = UUID.fromString("1f366804-f1d2-4288-b71a-708661777887");
	public static final byte CURRENT_WATCHAPP_VERSION_BUNDLED = 6; // bundled watchapp version
	public static final byte CURRENT_WATCHAPP_VERSION_MINIMUM = 4; // smallest version of watchapp that is still supported

	public static final int MAX_NUM_EVENTS_TO_SEND = 10; // should correspond to number of items saved in the watch database

	// Android app internals
	public static final String INTENT_ACTION_WATCHAPP_GIVE_INFO = "de.janbo.agendawatchface.intent.action.givedata"; // answers to requests will be broadcast using this action
	public static final String INTENT_ACTION_WATCHAPP_REQUEST_INFO = "de.janbo.agendawatchface.intent.action.requestdata"; // request state data from this service
	public static final String INTENT_ACTION_HANDLE_WATCHAPP_MESSAGE = "de.janbo.agendawatchface.intent.action.handlemessage"; // handle an incoming message from the watch
	public static final String INTENT_EXTRA_WATCHAPP_VERSION = "de.janbo.agendawatchface.intent.extra.version"; // version of watchface or -1 if unknown
	public static final String INTENT_EXTRA_WATCHAPP_LAST_SYNC = "de.janbo.agendawatchface.intent.extra.lastsync"; // time since epoch in ms for last successful sync. Or -1

	// Protocol states
	public static final int STATE_WAIT_FOR_WATCH_REQUEST = 0; // Nothing happening
	public static final int STATE_INIT_SENT = 1; // First message (COMMAND_INIT_DATA) sent, waiting for ack
	public static final int STATE_SENT_EVENT_WAIT_FOR_ACK = 2; // sent first half of event, waiting for the watch to ack
	public static final int STATE_SENT_EVENT_TIME_WAIT_FOR_ACK = 3; // sent second half, waiting...
	public static final int STATE_SENT_DONE_MSG_WAIT_FOR_ACK = 4; // sent the done message, waiting for the watch to ack
	public static final int STATE_WATCH_REQUESTED_RESTART = 5; // we were in the middle of a sync, but the watch wants a restart (act on this when receiving the next ack)
	public static final int STATE_NO_NEW_DATA_MSG_SENT = 6; // we sent COMMAND_NO_NEW_DATA, waiting for ack

	// Pebble dictionary keys
	public static final int PEBBLE_KEY_COMMAND = 0; // uint_8
	public static final int PEBBLE_KEY_VERSION = 1; // uint_8, minimal watchapp version for syncing
	public static final int PEBBLE_KEY_SYNC_ID = 2; // uint_8, id for this particular sync
	public static final int PEBBLE_KEY_NUM_EVENTS = 10; // uint_8
	public static final int PEBBLE_KEY_CAL_TITLE = 1; // String
	public static final int PEBBLE_KEY_CAL_LOC = 2; // String
	public static final int PEBBLE_KEY_CAL_START_TIME = 20; // int_32, in format: minutes + 60*hours + 60*24*weekday + 60*24*7*dayOfMonth + 60*24*7*32*(month-1) + 60*24*7*32*12*(year-1900)
	public static final int PEBBLE_KEY_CAL_END_TIME = 30; // int_32
	public static final int PEBBLE_KEY_CAL_ALLDAY = 5; // uint_8
	public static final int PEBBLE_KEY_SETTINGS_BOOLFLAGS = 40; // uint_32
	public static final int PEBBLE_KEY_SETTINGS_DESIGN = 41; // uint_32

	public static final int PEBBLE_TO_PHONE_KEY_VERSION = 0; // current version of the watchface
	public static final int PEBBLE_TO_PHONE_KEY_VERSIONBACKWARD = 1; // version of bundled firmware that this app must have to support the watchface version
	public static final int PEBBLE_TO_PHONE_KEY_LAST_SYNC_ID = 2; // id of the last sync that went through correctly according to watch (0 to force)

	// Pebble commands
	public static final byte PEBBLE_COMMAND_CAL_EVENT = 1;
	public static final byte PEBBLE_COMMAND_CAL_EVENT_TIME = 3;
	public static final byte PEBBLE_COMMAND_INIT_DATA = 0;
	public static final byte PEBBLE_COMMAND_DONE = 2;
	public static final byte PEBBLE_COMMAND_NO_NEW_DATA = 4;

	// Variables
	private int state = STATE_WAIT_FOR_WATCH_REQUEST;
	private int currentIndex = -1; // index that is currently sent
	private List<CalendarEvent> eventsToSend = null; // data we're currently sending to the watch
	private byte currentSyncId = 0; //id of the current sync process (incremented for each new COMMAND_INIT_DATA)
	private List<CalendarEvent> eventsSuccessfullySent = null; //last list we sent completely (DONE message). Data corresponds to lastSuccessfulSyncId below
	private byte lastSuccessfulSyncId = 0; //id that we gave the watchface for the last sync that went through (DONE message) (used for checking for new data) - 0 means "don't know, send anyway!"

	private BroadcastReceiver ackReceiver = null;
	private BroadcastReceiver nackReceiver = null;

	private PebbleDictionary lastSentDict = null; // data last sent. Used for retries
	private int transactionFlying = -1; // id of the transaction last sent.
	private int numRetries = 0; // number of times we tried to send this data

	private long notificationIssued = -1; // time since epoch in ms where update prompt was issued last
	private int watchfaceVersion = -1; // last version the watchface reported
	private long lastSync = -1; // time since epoch in ms where last sync went through

	private static PebbleCommService instance = null; // static reference to the service

	private ContentObserver calendarObserver = new ContentObserver(null) { // content observer looking for calendar changes
		@Override
		public void onChange(boolean selfChange) {
			Log.d("PebbleCommunication", "Calendar changed (observer fired)");
			if (state == STATE_WAIT_FOR_WATCH_REQUEST)
				beginSendingData(lastSuccessfulSyncId);
		}
	};

	private BroadcastReceiver infoRequestReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			broadcastCurrentData();
		}
	};

	@Override
	public IBinder onBind(Intent intent) { // this is not a bound service
		return null;
	}

	/**
	 * Acks and handles a received message (starts service if not currently running). Should be called by the PebbleDataBroadcastReceiver for all (data) messages from the watch
	 * 
	 * @param context
	 * @param msgData
	 *            the msgData (json String) from the original intent
	 * @param transactionId
	 */
	public static void handleReceivedData(Context context, String msgData, int transactionId) {
		// Ack the message
		PebbleKit.sendAckToPebble(context, transactionId);

		// Relay message to the service
		if (instance == null)
			Log.d("PebbleCommunication", "Reviving service because of incoming Pebble message");
		Intent intent = new Intent(context, PebbleCommService.class);
		intent.setAction(INTENT_ACTION_HANDLE_WATCHAPP_MESSAGE);
		intent.putExtra(com.getpebble.android.kit.Constants.MSG_DATA, msgData);
		context.startService(intent);
	}

	/**
	 * Checks the message's content and acts accordingly
	 * 
	 * @param dict
	 */
	private void handleReceivedDataInternal(PebbleDictionary data) {
		requestReceived(data.getInteger(PEBBLE_TO_PHONE_KEY_VERSION), data.contains(PEBBLE_TO_PHONE_KEY_VERSIONBACKWARD) ? data.getInteger(PEBBLE_TO_PHONE_KEY_VERSIONBACKWARD) : 4, data.contains(PEBBLE_TO_PHONE_KEY_LAST_SYNC_ID) ? data.getUnsignedInteger(PEBBLE_TO_PHONE_KEY_LAST_SYNC_ID).byteValue() : (byte) 0);
	}

	@Override
	public void onCreate() {
		super.onCreate();

		Log.d("PebbleCommunication", "Service created");

		// Register receivers
		ackReceiver = PebbleKit.registerReceivedAckHandler(this, new PebbleAckReceiver(PEBBLE_APP_UUID) {
			@Override
			public void receiveAck(Context context, int transactionId) {
				ackReceived(transactionId);
			}
		});

		nackReceiver = PebbleKit.registerReceivedNackHandler(this, new PebbleNackReceiver(PEBBLE_APP_UUID) {
			@Override
			public void receiveNack(Context context, int transactionId) {
				nackReceived(transactionId);
			}
		});

		// Register for calendar changes
		CalendarReader.registerCalendarObserver(this, calendarObserver);

		// Register for info requests
		IntentFilter filter = new IntentFilter();
		filter.addAction(PebbleCommService.INTENT_ACTION_WATCHAPP_REQUEST_INFO);
		registerReceiver(infoRequestReceiver, filter);

		instance = this;
	}

	@Override
	public void onDestroy() {
		super.onDestroy();

		if (ackReceiver != null)
			unregisterReceiver(ackReceiver);
		if (nackReceiver != null)
			unregisterReceiver(nackReceiver);

		CalendarReader.unregisterCalendarObserver(this, calendarObserver);

		instance = null;
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		if (intent != null && INTENT_ACTION_HANDLE_WATCHAPP_MESSAGE.equals(intent.getAction())) { // handle the watch's request
			Log.d("PebbleCommunication", "handling watch message");
			try {
				handleReceivedDataInternal(PebbleDictionary.fromJson(intent.getStringExtra(com.getpebble.android.kit.Constants.MSG_DATA)));
			} catch (JSONException e) {
				Log.e("PebbleCommunication", "Error parsing json", e);
			}
		} else if (intent != null) { // someone wants to simply start the service. Also start a sync
			Log.d("PebbleCommunication", "onStartService() started forced update");
			beginSendingData();
		}

		return START_STICKY; // we want the service to persist
	}

	/**
	 * Pebble got our last message. Send next one according to current state
	 * 
	 * @param transactionId
	 */
	private synchronized void ackReceived(int transactionId) {
		if (transactionId != transactionFlying) {
			Log.d("PebbleCommunication", "Received unexpected ack. Ignoring");
		}
		Log.d("PebbleCommunication", "Received ack in state " + state);
		switch (state) {
		case STATE_WATCH_REQUESTED_RESTART:
			beginSendingData();
			break;
		case STATE_WAIT_FOR_WATCH_REQUEST: // we're not expecting an ack
			break;
		case STATE_NO_NEW_DATA_MSG_SENT:
			state = STATE_WAIT_FOR_WATCH_REQUEST;
			break;
		case STATE_INIT_SENT: // message ack'd was the initial one. Start sending events
			currentIndex = 0;
			if (eventsToSend.size() == 0) { // nothing to do if no events to show
				state = STATE_WAIT_FOR_WATCH_REQUEST;
				break;
			}

			// Begin sending first event
			sendFirstEventHalf(eventsToSend.get(currentIndex));
			state = STATE_SENT_EVENT_WAIT_FOR_ACK;
			break;

		case STATE_SENT_EVENT_WAIT_FOR_ACK: // ack was for first event half. Send second half.
			sendSecondEventHalf(eventsToSend.get(currentIndex));
			state = STATE_SENT_EVENT_TIME_WAIT_FOR_ACK;
			break;

		case STATE_SENT_EVENT_TIME_WAIT_FOR_ACK: // ack was for second event half. Send next event
			currentIndex++;
			if (currentIndex < eventsToSend.size() && currentIndex < MAX_NUM_EVENTS_TO_SEND) { // still things to send
				sendFirstEventHalf(eventsToSend.get(currentIndex));
				state = STATE_SENT_EVENT_WAIT_FOR_ACK;
			} else {
				sendDoneMessage();
				state = STATE_SENT_DONE_MSG_WAIT_FOR_ACK;
			}
			break;

		case STATE_SENT_DONE_MSG_WAIT_FOR_ACK: // ack was for done message. This concludes the sync process
			state = STATE_WAIT_FOR_WATCH_REQUEST;
			Log.d("PebbleCommunication", "Sync complete :)");
			lastSync = System.currentTimeMillis();
			lastSuccessfulSyncId = currentSyncId;
			eventsSuccessfullySent = eventsToSend;
			
			broadcastCurrentData();
			break;
		}
	}

	/**
	 * Handle the watch requesting new data. Checks watchapp version, issues update prompt or starts sending data
	 * 
	 * @param version
	 *            version of the watchface
	 * @param minVersion
	 *            version the watchface expects of this app (bundled watchface version)
	 * @param reportedSyncId
	 * 			  id the watch reports that its synced data has
	 */
	private synchronized void requestReceived(Long version, Long minVersion, byte reportedSyncId) {
		Log.d("PebbleCommunication", "Received sync request in state " + state + " for version " + version +", watch reports having data id "+reportedSyncId);
		
		if (currentSyncId == 0)
			currentSyncId = reportedSyncId; //if we have no sync id remembered, just pretend we remember the one the watch reported
		
		if (minVersion == null || minVersion > CURRENT_WATCHAPP_VERSION_BUNDLED) { // watchface expects newer Android app
			triggerAndroidAppUpdateNotification();
			state = STATE_WAIT_FOR_WATCH_REQUEST;
		} else if (version == null || version < CURRENT_WATCHAPP_VERSION_MINIMUM) { // watchface very outdated
			triggerUpdateNotification();
			state = STATE_WAIT_FOR_WATCH_REQUEST;
		} else {
			// everything good. Give the watch its data :)
			if (state == STATE_WAIT_FOR_WATCH_REQUEST || state == STATE_WATCH_REQUESTED_RESTART) //expecting request or watch is very persistent in requesting the restart...
				beginSendingData(reportedSyncId);
			else {
				Log.d("PebbleCommunication", "Restart request during sync. Setting state to restart");
				state = STATE_WATCH_REQUESTED_RESTART; //restart the whole thing when the next ack is received (as kind of a termination of the previous process)
			}
		}

		// Notify the activity if it's listening
		watchfaceVersion = version == null ? -1 : version.intValue();
		broadcastCurrentData();
	}

	/**
	 * Handle nacks: resend if necessary
	 * 
	 * @param transactionId
	 */
	private void nackReceived(int transactionId) {
		if (transactionId == transactionFlying) {
			if (state == STATE_INIT_SENT) {
				Log.d("PebbleCommunication", "Received Nack for init message. Will not retry");
				state = STATE_WAIT_FOR_WATCH_REQUEST;
			} else {
				Log.d("PebbleCommunication", "Received Nack in state " + state + " resend counter: " + numRetries);

				if (!sendMessage(lastSentDict, true)) {
					Log.d("PebbleCommunication", "Retries exhausted. Resetting state to begin again");
					state = STATE_WAIT_FOR_WATCH_REQUEST;
				}
			}
		} else {
			Log.d("PebbleCommunication", "Received Nack for \"foreign\" transaction");
		}
	}

	/**
	 * Add user settings to a PebbleDictionary
	 * 
	 * @param dict
	 */
	private void addPebbleSettings(PebbleDictionary dict) {
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());

		// General settings
		int flags = 0;
		flags |= prefs.getBoolean("pref_show_header", true) ? 0x01 : 0; // constants are documented in the watchapp
		flags |= prefs.getBoolean("pref_12h", false) ? 0x02 : 0;
		flags |= prefs.getBoolean("pref_ampm", true) ? 0x04 : 0;
		flags |= prefs.getBoolean("pref_layout_show_row2", true) ? 0x08 : 0;
		flags |= prefs.getBoolean("pref_layout_ad_show_row2", false) ? 0x10 : 0;
		flags |= Integer.parseInt(prefs.getString("pref_layout_font_size", "0")) % 2 == 1 ? 0x20 : 0;
		flags |= Integer.parseInt(prefs.getString("pref_layout_font_size", "0")) > 1 ? 0x40 : 0;
		flags |= Integer.parseInt(prefs.getString("pref_header_time_size", "0")) % 2 == 1 ? 0x80 : 0;
		flags |= Integer.parseInt(prefs.getString("pref_header_time_size", "0")) > 1 ? 0x100 : 0;
		flags |= prefs.getBoolean("pref_separator_date", false) ? 0x200 : 0;

		dict.addUint32(PEBBLE_KEY_SETTINGS_BOOLFLAGS, flags);

		// Design settings
		int design = 0; // first 4 bits: time settings (first row), then 4 bits text settings (first row), then 4 bits time settings (2nd row), then 4 bits text settings (2nd row). Then the same again
						// for all-day events
		design |= Integer.parseInt(prefs.getString("pref_layout_time_1", "0")); // check values/strings.xml for value meanings
		design |= Integer.parseInt(prefs.getString("pref_layout_text_1", "1")) * 0x10;
		design |= Integer.parseInt(prefs.getString("pref_layout_time_2", "4")) * 0x100;
		design |= Integer.parseInt(prefs.getString("pref_layout_text_2", "2")) * 0x1000;
		design |= Integer.parseInt(prefs.getString("pref_layout_ad_text_1", "1")) * 0x100000;
		design |= Integer.parseInt(prefs.getString("pref_layout_ad_text_2", "2")) * 0x10000000;

		dict.addUint32(PEBBLE_KEY_SETTINGS_DESIGN, design);
	}

	/**
	 * Kicks off (forced) sync process by reading calendar data and sending init message
	 */
	private synchronized void beginSendingData() {
		beginSendingData((byte) 0);
	}
	
	/**
	 * Kicks off sync process by reading calendar data, checking whether it contains new data, and sending init message or noNewData message
	 * @param reportedSyncId id that the watch reported that it has (or 0 to force sync)
	 */
	private synchronized void beginSendingData(byte reportedSyncId) {
		if (state != STATE_WAIT_FOR_WATCH_REQUEST) {
			Log.d("PebbleCommunication", "Restarting sending of events");
		}

		eventsToSend = CalendarReader.getEvents(getApplicationContext(), MAX_NUM_EVENTS_TO_SEND);
		currentIndex = -1;
		
		//Check if we should report this data having been sent before
		boolean newData = true;
		if (eventsSuccessfullySent != null && lastSuccessfulSyncId != 0 && reportedSyncId == lastSuccessfulSyncId) { //if so, Compare the version we're about to send to the last successful one
			if (eventsSuccessfullySent.size() == eventsToSend.size()) {
				newData = false;
				for (int i=0;i<eventsToSend.size();i++)
					if (!eventsToSend.get(i).equals(eventsSuccessfullySent.get(i))) {
						newData = true;
						break;
					}
			}
		}
		
		if (newData) {
			currentSyncId++;
			currentSyncId = currentSyncId <= 0 ? (byte) 1 : currentSyncId;
			sendInitDataMsg(Math.min(eventsToSend.size(), MAX_NUM_EVENTS_TO_SEND), currentSyncId);
			state = STATE_INIT_SENT;
		} else {
			sendNoNewDataMsg();
			state = STATE_NO_NEW_DATA_MSG_SENT;
		}
	}
	
	private void sendNoNewDataMsg() {
		Log.d("PebbleCommunication", "Informing watch that its dataset is up-to-date");
		PebbleDictionary data = new PebbleDictionary();
		data.addUint8(PEBBLE_KEY_COMMAND, PEBBLE_COMMAND_NO_NEW_DATA);
		sendMessage(data, false);
	}

	/**
	 * Sends init message.
	 * 
	 * @param numberOfEvents
	 * @param syncId Id of this sync process to report to the watch
	 */
	private void sendInitDataMsg(int numberOfEvents, byte syncId) {
		Log.d("PebbleCommunication", "sending init message, advertising " + numberOfEvents + " events and syncId "+syncId);
		PebbleDictionary data = new PebbleDictionary();
		data.addUint8(PEBBLE_KEY_COMMAND, PEBBLE_COMMAND_INIT_DATA); // command
		data.addUint8(PEBBLE_KEY_NUM_EVENTS, (byte) numberOfEvents); // number of events we will send
		data.addUint8(PEBBLE_KEY_SYNC_ID, syncId); //id of the data we're about to send (to compare against existing data)
		data.addUint8(PEBBLE_KEY_VERSION, CURRENT_WATCHAPP_VERSION_MINIMUM); // expected minimum watchapp version
		addPebbleSettings(data); // general and design settings
		sendMessage(data, false);
	}

	/**
	 * Sends a first event half message
	 * 
	 * @param e
	 */
	private void sendFirstEventHalf(CalendarEvent e) {
		PebbleDictionary data = new PebbleDictionary();
		data.addUint8(PEBBLE_KEY_COMMAND, PEBBLE_COMMAND_CAL_EVENT); // command
		data.addString(PEBBLE_KEY_CAL_TITLE, e.title == null ? "(no title)" : e.title.length() > 30 ? e.title.substring(0, 30) : e.title);
		data.addString(PEBBLE_KEY_CAL_LOC, e.location == null ? "" : e.location.length() > 30 ? e.location.substring(0, 30) : e.location);
		data.addUint8(PEBBLE_KEY_CAL_ALLDAY, e.allDay ? (byte) 1 : (byte) 0);
		sendMessage(data, false);
	}

	/**
	 * Sends second half of an event
	 * 
	 * @param e
	 */
	private void sendSecondEventHalf(CalendarEvent e) {
		PebbleDictionary data = new PebbleDictionary();
		data.addUint8(PEBBLE_KEY_COMMAND, PEBBLE_COMMAND_CAL_EVENT_TIME);

		// Set times
		data.addInt32(PEBBLE_KEY_CAL_START_TIME, e.getStartTimeInPebbleFormat());
		data.addInt32(PEBBLE_KEY_CAL_END_TIME, e.getEndTimeInPebbleFormat());

		// Send data
		sendMessage(data, false);
	}

	/**
	 * Send message that we're done with the sync
	 */
	private void sendDoneMessage() {
		PebbleDictionary data2 = new PebbleDictionary();
		data2.addUint8(PEBBLE_KEY_COMMAND, PEBBLE_COMMAND_DONE);
		sendMessage(data2, false);
	}

	/**
	 * Sends data to the watch. Only resends once
	 * 
	 * @param resend
	 *            whether or not this message has been sent already at some point
	 * @return true iff message was sent. false if retries have been exhausted.
	 */
	private synchronized boolean sendMessage(PebbleDictionary data, boolean resend) {
		transactionFlying = (transactionFlying + 1) % 256; // new transaction
		if ((numRetries = resend ? numRetries + 1 : 0) > 1) {
			Log.d("PebbleCommunication", "Stopped retrying message sending in state " + state);
			return false;
		}

		lastSentDict = data;
		PebbleKit.sendDataToPebbleWithTransactionId(getApplicationContext(), PEBBLE_APP_UUID, data, transactionFlying);
		return true;
	}

	/**
	 * Shows a notification prompting the user to update the watchapp
	 */
	private void triggerUpdateNotification() {
		if (notificationIssued != -1 && System.currentTimeMillis() - notificationIssued < 1000 * 60 * 60) // don't spam it
			return;

		notificationIssued = System.currentTimeMillis();

		Intent intent = new Intent(getApplicationContext(), WatchappUpdateActivity.class);

		NotificationCompat.Builder builder = new NotificationCompat.Builder(this).setSmallIcon(R.drawable.ic_launcher).setContentTitle("AgendaWatchface Update")
				.setContentText("There is an update for AgendaWatchface on your Pebble! :) - Please update, otherwise synchronization will not work.");
		builder.setContentIntent(PendingIntent.getActivity(getApplicationContext(), 0, intent, Intent.FLAG_ACTIVITY_NEW_TASK));
		builder.setAutoCancel(true);
		NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

		manager.notify(1, builder.build());
	}

	/**
	 * Shows a notification prompting the user to update the Android app
	 */
	private void triggerAndroidAppUpdateNotification() {
		if (notificationIssued != -1 && System.currentTimeMillis() - notificationIssued < 1000 * 60 * 60) // don't spam it
			return;

		notificationIssued = System.currentTimeMillis();

		Intent intent;
		try {
			intent = new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + getPackageName()));
		} catch (android.content.ActivityNotFoundException anfe) {
			intent = new Intent(Intent.ACTION_VIEW, Uri.parse("http://play.google.com/store/apps/details?id=" + getPackageName()));
		}

		NotificationCompat.Builder builder = new NotificationCompat.Builder(this).setSmallIcon(R.drawable.ic_launcher).setContentTitle("AgendaWatchface Update")
				.setContentText("There is an update for the Android app. Please update, otherwise synchronization with your newer version watchapp will not work.");

		builder.setContentIntent(PendingIntent.getActivity(getApplicationContext(), 0, intent, Intent.FLAG_ACTIVITY_NEW_TASK));
		builder.setAutoCancel(true);
		NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

		manager.notify(1, builder.build());
	}

	/**
	 * Gives the current version of the watchapp, last sync time, etc... to any broadcast listeners (like the MainActivity)
	 */
	private void broadcastCurrentData() {
		Intent intent = new Intent();
		intent.setAction(INTENT_ACTION_WATCHAPP_GIVE_INFO);
		intent.putExtra(INTENT_EXTRA_WATCHAPP_VERSION, watchfaceVersion);
		intent.putExtra(INTENT_EXTRA_WATCHAPP_LAST_SYNC, lastSync);
		sendBroadcast(intent);
	}

	/**
	 * Starts the watchapp on the watch
	 * 
	 * @param context
	 *            application context
	 */
	public static void startWatchapp(Context context) {
		PebbleKit.startAppOnPebble(context.getApplicationContext(), PEBBLE_APP_UUID);
	}
}
