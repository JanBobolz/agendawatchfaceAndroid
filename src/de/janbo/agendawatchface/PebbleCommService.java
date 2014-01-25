package de.janbo.agendawatchface;

import java.util.Calendar;
import java.util.List;
import java.util.UUID;

import com.getpebble.android.kit.PebbleKit;
import com.getpebble.android.kit.PebbleKit.PebbleAckReceiver;
import com.getpebble.android.kit.PebbleKit.PebbleDataReceiver;
import com.getpebble.android.kit.PebbleKit.PebbleNackReceiver;
import com.getpebble.android.kit.util.PebbleDictionary;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.support.v4.app.NotificationCompat;
import android.util.Log;

/**
 * Service that handles communication with the watch.
 * @author Jan
 */
public class PebbleCommService extends Service {
	public static final UUID PEBBLE_APP_UUID = UUID.fromString("1f366804-f1d2-4288-b71a-708661777887");
	public static final byte CURRENT_WATCHAPP_VERSION_BUNDLED = 2; //expected watchapp version. If watch reports different, prompt the user to update

	public static final int MAX_NUM_EVENTS_TO_SEND = 10; //should correspond to number of items saved in the watch database

	// Protocol states
	public static final int STATE_WAIT_FOR_WATCH_REQUEST = 0; // Nothing happening
	public static final int STATE_INIT_SENT = 1; // First message (COMMAND_INIT_DATA) sent, waiting for ack
	public static final int STATE_SENT_EVENT_WAIT_FOR_ACK = 2; // sent first half of event, waiting for the watch to ack
	public static final int STATE_SENT_EVENT_TIME_WAIT_FOR_ACK = 3; // sent second half, waiting...
	public static final int STATE_SENT_DONE_MSG_WAIT_FOR_ACK = 4; // sent the done message, waiting for the watch to ack

	// Pebble dictionary keys
	public static final int PEBBLE_KEY_COMMAND = 0; // uint_8
	public static final int PEBBLE_KEY_VERSION = 1; // uint_8
	public static final int PEBBLE_KEY_NUM_EVENTS = 10; // uint_8
	public static final int PEBBLE_KEY_CAL_TITLE = 1; // String
	public static final int PEBBLE_KEY_CAL_LOC = 2; // String
	public static final int PEBBLE_KEY_CAL_START_TIME = 20; // int_32, in format: minutes + 60*hours + 60*24*weekday + 60*24*7*dayOfMonth + 60*24*7*32*(month-1) + 60*24*7*32*12*(year-1900)
	public static final int PEBBLE_KEY_CAL_END_TIME = 30; // int_32
	public static final int PEBBLE_KEY_CAL_ALLDAY = 5; // uint_8
	public static final int PEBBLE_KEY_SETTINGS_BOOLFLAGS = 40; // uint_32
	public static final int PEBBLE_KEY_SETTINGS_DESIGN = 41; //uint_32

	public static final int PEBBLE_TO_PHONE_KEY_VERSION = 0;

	// Pebble commands
	public static final byte PEBBLE_COMMAND_CAL_EVENT = 1;
	public static final byte PEBBLE_COMMAND_CAL_EVENT_TIME = 3;
	public static final byte PEBBLE_COMMAND_INIT_DATA = 0;
	public static final byte PEBBLE_COMMAND_DONE = 2;
	
	//Variables
	private int state = STATE_WAIT_FOR_WATCH_REQUEST;
	private int currentIndex = -1; //index that is currently sent
	private List<CalendarEvent> eventsToSend = null; //data we're currently sending to the watch

	private BroadcastReceiver ackReceiver = null;
	private BroadcastReceiver nackReceiver = null;
	private BroadcastReceiver dataReceiver = null;
	
	private long notificationIssued = -1; //time since epoch in ms where update prompt was issued last

	@Override
	public IBinder onBind(Intent intent) { //this is not a bound service
		return null;
	}

	
	@Override
	public void onCreate() {
		super.onCreate();

		Log.d("PebbleCommunication", "Service created");

		//Register receivers
		ackReceiver = PebbleKit.registerReceivedAckHandler(this, new PebbleAckReceiver(PEBBLE_APP_UUID) {
			@Override
			public void receiveAck(Context context, int transactionId) {
				ackReceived();
			}
		});

		nackReceiver = PebbleKit.registerReceivedNackHandler(this, new PebbleNackReceiver(PEBBLE_APP_UUID) {
			@Override
			public void receiveNack(Context context, int transactionId) {
				nackReceived();
			}
		});

		dataReceiver = PebbleKit.registerReceivedDataHandler(this, new PebbleDataReceiver(PEBBLE_APP_UUID) {
			@Override
			public void receiveData(Context context, int transactionId, PebbleDictionary data) {
				PebbleKit.sendAckToPebble(context, transactionId); //every message from the pebble must be ack'ed
				requestReceived(data.getInteger(PEBBLE_TO_PHONE_KEY_VERSION)); 
			}
		});
	}

	@Override
	public void onDestroy() {
		super.onDestroy();

		if (ackReceiver != null)
			unregisterReceiver(ackReceiver);
		if (nackReceiver != null)
			unregisterReceiver(nackReceiver);
		if (dataReceiver != null)
			unregisterReceiver(dataReceiver);
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) { //when service is started, kick off sending the watch current data
		Log.d("PebbleCommunication", "onStartService()");

		if (intent != null && state == STATE_WAIT_FOR_WATCH_REQUEST || state == STATE_INIT_SENT) { //restart process
			Log.d("PebbleCommunication", "onStartService() started forced update");
			beginSendingData();
		}

		return START_STICKY; //we want the service to persist, otherwise it cannot handle watch update requests
	}

	/**
	 * Pebble got our last message. Send next one according to current state
	 */
	private void ackReceived() {
		Log.d("PebbleCommunication", "Received ack in state " + state);
		switch (state) {
		case STATE_WAIT_FOR_WATCH_REQUEST: //we're not expecting an ack
			break;
		case STATE_INIT_SENT: //message ack'd was the initial one. Start sending events
			currentIndex = 0;
			if (eventsToSend.size() == 0) { //nothing to do if no events to show
				state = STATE_WAIT_FOR_WATCH_REQUEST;
				break;
			}

			// Begin sending first event
			sendFirstEventHalf(eventsToSend.get(currentIndex));
			state = STATE_SENT_EVENT_WAIT_FOR_ACK;
			break;

		case STATE_SENT_EVENT_WAIT_FOR_ACK: //ack was for first event half. Send second half.
			sendSecondEventHalf(eventsToSend.get(currentIndex));
			state = STATE_SENT_EVENT_TIME_WAIT_FOR_ACK;
			break;

		case STATE_SENT_EVENT_TIME_WAIT_FOR_ACK: //ack was for second event half. Send next event
			currentIndex++;
			if (currentIndex < eventsToSend.size() && currentIndex < MAX_NUM_EVENTS_TO_SEND) { // still things to send
				sendFirstEventHalf(eventsToSend.get(currentIndex));
				state = STATE_SENT_EVENT_WAIT_FOR_ACK;
			} else {
				sendDoneMessage();
				state = STATE_SENT_DONE_MSG_WAIT_FOR_ACK;
			}
			break;

		case STATE_SENT_DONE_MSG_WAIT_FOR_ACK: //ack was for done message. This concludes the sync process
			state = STATE_WAIT_FOR_WATCH_REQUEST;
			Log.d("PebbleCommunication", "Sync complete :)");
			break;
		}
	}

	/**
	 * Handle the watch requesting new data. 
	 * Checks watchapp version, issues update prompt or starts sending data
	 * @param version
	 */
	private void requestReceived(Long version) {
		Log.d("PebbleCommunication", "Received sync request in state " + state + " for version " + version);
		if (version == null || version != CURRENT_WATCHAPP_VERSION_BUNDLED) { // catch outdated watchapps
			triggerUpdateNotification();
			state = STATE_WAIT_FOR_WATCH_REQUEST;
			return;
		}
		
		beginSendingData();
	}

	/**
	 * Just log nacks and reset process. Worst case: watch asks for new sync next minute.
	 */
	private void nackReceived() {
		Log.d("PebbleCommunication", "Received Nack in state " + state);
		state = STATE_WAIT_FOR_WATCH_REQUEST;
	}
	
	/**
	 * Add user settings to a PebbleDictionary
	 * @param dict
	 */
	private void addPebbleSettings(PebbleDictionary dict) {
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
		
		//General settings
		int flags = 0;
		flags |= prefs.getBoolean("pref_show_header", true) ? 0x01 : 0; //constants are documented in the watchapp
		flags |= prefs.getBoolean("pref_12h", false) ? 0x02 : 0;
		flags |= prefs.getBoolean("pref_ampm", true) ? 0x04 : 0;
		flags |= prefs.getBoolean("pref_layout_show_row2", true) ? 0x08 : 0;
		flags |= prefs.getBoolean("pref_layout_ad_show_row2", false) ? 0x10 : 0;
		flags |= Integer.parseInt(prefs.getString("pref_layout_font_size", "0"))%2 == 1 ? 0x20 : 0;
		flags |= Integer.parseInt(prefs.getString("pref_layout_font_size", "0")) > 1 ? 0x40 : 0;

		dict.addUint32(PEBBLE_KEY_SETTINGS_BOOLFLAGS, flags);
		
		//Design settings
		int design = 0; //first 4 bits: time settings (first row), then 4 bits text settings (first row), then 4 bits time settings (2nd row), then 4 bits text settings (2nd row). Then the same again for all-day events
		design |= Integer.parseInt(prefs.getString("pref_layout_time_1", "0")); //check values/strings.xml for value meanings
		design |= Integer.parseInt(prefs.getString("pref_layout_text_1", "1"))*0x10;
		design |= Integer.parseInt(prefs.getString("pref_layout_time_2", "4"))*0x100;
		design |= Integer.parseInt(prefs.getString("pref_layout_text_2", "2"))*0x1000;
		design |= Integer.parseInt(prefs.getString("pref_layout_ad_text_1", "1"))*0x100000;
		design |= Integer.parseInt(prefs.getString("pref_layout_ad_text_2", "2"))*0x10000000;
		
		dict.addUint32(PEBBLE_KEY_SETTINGS_DESIGN, design);
	}

	/**
	 * Kicks off sync process by reading calendar data and sending init message
	 */
	private void beginSendingData() {
		if (state != STATE_WAIT_FOR_WATCH_REQUEST) {
			Log.d("PebbleCommunication", "Restarting sending of events");
		}

		eventsToSend = CalendarReader.getEvents(getApplicationContext(), MAX_NUM_EVENTS_TO_SEND);
		currentIndex = -1;
		sendInitDataMsg(Math.min(eventsToSend.size(), MAX_NUM_EVENTS_TO_SEND));
		state = STATE_INIT_SENT;
	}

	/**
	 * Sends init message.
	 * @param numberOfEvents
	 */
	private void sendInitDataMsg(int numberOfEvents) {
		PebbleDictionary data = new PebbleDictionary();
		data.addUint8(PEBBLE_KEY_COMMAND, PEBBLE_COMMAND_INIT_DATA); //command
		data.addUint8(PEBBLE_KEY_NUM_EVENTS, (byte) numberOfEvents); //number of events we will send
		data.addUint8(PEBBLE_KEY_VERSION, CURRENT_WATCHAPP_VERSION_BUNDLED); //expected watchapp version
		addPebbleSettings(data); //general and design settings
		PebbleKit.sendDataToPebble(getApplicationContext(), PEBBLE_APP_UUID, data);
	}

	/**
	 * Sends a first event half message
	 * @param e
	 */
	private void sendFirstEventHalf(CalendarEvent e) {
		PebbleDictionary data = new PebbleDictionary();
		data.addUint8(PEBBLE_KEY_COMMAND, PEBBLE_COMMAND_CAL_EVENT); //command
		data.addString(PEBBLE_KEY_CAL_TITLE, e.title == null ? "(no title)" : e.title.length() > 30 ? e.title.substring(0, 30) : e.title);
		data.addString(PEBBLE_KEY_CAL_LOC, e.location == null ? "" : e.location.length() > 30 ? e.location.substring(0, 30) : e.location);
		data.addUint8(PEBBLE_KEY_CAL_ALLDAY, e.allDay ? (byte) 1 : (byte) 0);
		PebbleKit.sendDataToPebble(getApplicationContext(), PEBBLE_APP_UUID, data);
	}

	/**
	 * Sends second half of an event
	 * @param e
	 */
	private void sendSecondEventHalf(CalendarEvent e) {
		PebbleDictionary data = new PebbleDictionary();
		data.addUint8(PEBBLE_KEY_COMMAND, PEBBLE_COMMAND_CAL_EVENT_TIME);
		Calendar cal = Calendar.getInstance();

		// Set start time
		cal.setTime(e.startTime);
		data.addInt32(PEBBLE_KEY_CAL_START_TIME,
				(cal.get(Calendar.MINUTE) + 60 * cal.get(Calendar.HOUR_OF_DAY) + 60 * 24 * ((cal.get(Calendar.DAY_OF_WEEK) + 5) % 7) + 60 * 24 * 7 * cal.get(Calendar.DAY_OF_MONTH) + 60 * 24 * 7 * 32
						* cal.get(Calendar.MONTH) + 60 * 24 * 7 * 32 * 12 * (cal.get(Calendar.YEAR) - 1900))); //format is documented in watchapp and above for PEBBLE_KEY_CAL_START_TIME

		// Set end time
		cal.setTime(e.endTime);
		data.addInt32(PEBBLE_KEY_CAL_END_TIME,
				(cal.get(Calendar.MINUTE) + 60 * cal.get(Calendar.HOUR_OF_DAY) + 60 * 24 * ((cal.get(Calendar.DAY_OF_WEEK) + 5) % 7) + 60 * 24 * 7 * cal.get(Calendar.DAY_OF_MONTH) + 60 * 24 * 7 * 32
						* cal.get(Calendar.MONTH) + 60 * 24 * 7 * 32 * 12 * (cal.get(Calendar.YEAR) - 1900))); //format is documented in watchapp and above for PEBBLE_KEY_CAL_START_TIME

		// Send data
		PebbleKit.sendDataToPebble(getApplicationContext(), PEBBLE_APP_UUID, data);
	}

	/**
	 * Send message that we're done with the sync
	 */
	private void sendDoneMessage() {
		PebbleDictionary data2 = new PebbleDictionary();
		data2.addUint8(PEBBLE_KEY_COMMAND, PEBBLE_COMMAND_DONE);
		PebbleKit.sendDataToPebble(getApplicationContext(), PEBBLE_APP_UUID, data2);
	}

	/**
	 * Shows a notification prompting the user to update the watchapp
	 */
	private void triggerUpdateNotification() {
		if (notificationIssued != -1 && System.currentTimeMillis()-notificationIssued < 1000*60*60) //don't spam it
			return;
		
		notificationIssued = System.currentTimeMillis();
		
		Intent intent = new Intent(getApplicationContext(), WatchappUpdateActivity.class);
		
		NotificationCompat.Builder builder = new NotificationCompat.Builder(this).setSmallIcon(R.drawable.ic_launcher).setContentTitle("AgendaWatchface Update")
				.setContentText("There is an update for AgendaWatchface on your Pebble! :)");
		builder.setContentIntent(PendingIntent.getActivity(getApplicationContext(), 0, intent, Intent.FLAG_ACTIVITY_NEW_TASK));
		builder.setAutoCancel(true);
		NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
		
		manager.notify(1, builder.build());
	}
}
