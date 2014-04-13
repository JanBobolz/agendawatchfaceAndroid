package de.janbo.agendawatchface;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import org.json.JSONException;

import com.getpebble.android.kit.PebbleKit;
import com.getpebble.android.kit.PebbleKit.PebbleAckReceiver;
import com.getpebble.android.kit.PebbleKit.PebbleNackReceiver;
import com.getpebble.android.kit.util.PebbleDictionary;

import de.janbo.agendawatchface.api.AgendaItem;
import de.janbo.agendawatchface.api.AgendaWatchfacePlugin;
import de.janbo.agendawatchface.api.TimeDisplayType;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Parcelable;
import android.preference.PreferenceManager;
import android.support.v4.app.NotificationCompat;
import android.util.Log;

/**
 * Service that handles aggregation of items and communication with the watch.
 * 
 * @author Jan
 */
public class AgendaWatchfaceService extends Service {
	public static final UUID PEBBLE_APP_UUID = UUID.fromString("1f366804-f1d2-4288-b71a-708661777887");
	public static final byte CURRENT_WATCHAPP_VERSION_BUNDLED = 9; // bundled watchapp version
	public static final byte CURRENT_WATCHAPP_VERSION_MINIMUM = 8; // smallest version of watchapp that is still supported

	public static final long WAIT_TIME_FOR_PLUGIN_REPORTS = 2 * 1000; // maximum time to wait with first sync before all plugins report (in ms)
	public static final int PLUGIN_SYNC_INTERVAL = 30; // interval to get new data from plugins (in minutes)
	public static final int MAX_STRING_LEN_TO_SEND = 50; //how long may the strings be that we send to the watch?

	// Android app internals
	public static final String INTENT_ACTION_WATCHAPP_GIVE_INFO = "de.janbo.agendawatchface.intent.action.givedata"; // answers to requests will be broadcast using this action
	public static final String INTENT_ACTION_WATCHAPP_REQUEST_INFO = "de.janbo.agendawatchface.intent.action.requestdata"; // request state data from this service
	public static final String INTENT_ACTION_HANDLE_WATCHAPP_MESSAGE = "de.janbo.agendawatchface.intent.action.handlemessage"; // handle an incoming message from the watch
	public static final String INTENT_ACTION_REFRESH_PLUGIN_DATA = "de.janbo.agendawatchface.intent.action.refreshplugindata"; // ask plugins for fresh data, then sync with watch
	public static final String INTENT_ACTION_FORCE_WATCH_SYNC = "de.janbo.agendawatchface.intent.action.forcewatchsync"; // begins a sync with the watch, doesn't update plugin data
	public static final String INTENT_EXTRA_WATCHAPP_VERSION = "de.janbo.agendawatchface.intent.extra.version"; // version of watchface or -1 if unknown
	public static final String INTENT_EXTRA_WATCHAPP_LAST_SYNC = "de.janbo.agendawatchface.intent.extra.lastsync"; // time since epoch in ms for last successful sync. Or -1

	// Intents for communication with plugins
	public static final String INTENT_ACTION_ACCEPT_DATA = "de.janbo.agendawatchface.intent.action.acceptdata";
	public static final String INTENT_ACTION_ACCEPT_DISCOVER = "de.janbo.agendawatchface.intent.action.acceptdiscovery";

	// Protocol states
	public static final int STATE_WAIT_FOR_WATCH_REQUEST = 0; // Nothing happening
	public static final int STATE_INIT_SENT = 1; // First message (COMMAND_INIT_DATA) sent, waiting for ack
	public static final int STATE_SENT_ITEM_WAIT_FOR_ACK = 2; // sent item, waiting for the watch to ack
	public static final int STATE_SENT_ITEM_1_WAIT_FOR_ACK = 8; //sent first item half, waiting for the watck to ack
	public static final int STATE_SENT_ITEM_2_WAIT_FOR_ACK = 9; //sent second item half, waiting for the watck to ack
	public static final int STATE_SENT_DONE_MSG_WAIT_FOR_ACK = 4; // sent the done message, waiting for the watch to ack
	public static final int STATE_RESTART_SYNC_ON_ACK = 5; // we were in the middle of a sync, but the watch wants a restart (act on this when receiving the next ack)
	public static final int STATE_NO_NEW_DATA_MSG_SENT = 6; // we sent COMMAND_NO_NEW_DATA, waiting for ack
	public static final int STATE_INITIAL_POPULATING_PLUGIN_DATA = 7; // the service is fresh and we don't have recent data available. Waiting for time to pass (some Runnable on a handler will start
																		// first sync)

	// Pebble dictionary keys
	public static final int PEBBLE_KEY_COMMAND = 0; // uint_8
	public static final int PEBBLE_KEY_VERSION = 1; // uint_8, minimal watchapp version for syncing
	public static final int PEBBLE_KEY_SYNC_ID = 2; // uint_8, id for this particular sync
	public static final int PEBBLE_KEY_NUM_ITEMS = 10; // uint_8
	public static final int PEBBLE_KEY_ITEM_TEXT1 = 1; // String
	public static final int PEBBLE_KEY_ITEM_TEXT2 = 2; // String
	public static final int PEBBLE_KEY_ITEM_DESIGN1 = 3; // uint_8, format: 0 if row hidden. 0x02-0x08 TimeDisplayType, 0x10 countdown on/off, 0x12 bold text on/off
	public static final int PEBBLE_KEY_ITEM_DESIGN2 = 4; // uint_8, format like DESIGN1
	public static final int PEBBLE_KEY_ITEM_START_TIME = 20; // int_32, in format: minutes + 60*hours + 60*24*weekday + 60*24*7*dayOfMonth + 60*24*7*32*(month-1) + 60*24*7*32*12*(year-1900). Or 0 to
																// simply show "today"
	public static final int PEBBLE_KEY_ITEM_END_TIME = 30; // int_32 or 0 to make it never end
	public static final int PEBBLE_KEY_ITEM_INDEX = 5; // uint_8, index numbering the items in a sync (0 being the first item)
	public static final int PEBBLE_KEY_SETTINGS_BOOLFLAGS = 40; // uint_32
	public static final int PEBBLE_KEY_VIBRATE = 6; //uint_8, if nonzero: instructs watch to vibrate according to pattern (PEBBLE_VIBRATE_... constants). Sent in DONE message

	public static final int PEBBLE_TO_PHONE_KEY_VERSION = 0; // current version of the watchface
	public static final int PEBBLE_TO_PHONE_KEY_VERSIONBACKWARD = 1; // version of bundled firmware that this app must have to support the watchface version
	public static final int PEBBLE_TO_PHONE_KEY_LAST_SYNC_ID = 2; // id of the last sync that went through correctly according to watch (0 to force)

	// Pebble commands
	public static final byte PEBBLE_COMMAND_ITEM = 1; //sending item in one message
	public static final byte PEBBLE_COMMAND_ITEM_1 = 6; //sending first half of item
	public static final byte PEBBLE_COMMAND_ITEM_2 = 7; //sending second half of item
	public static final byte PEBBLE_COMMAND_INIT_DATA = 0;
	public static final byte PEBBLE_COMMAND_DONE = 2;
	public static final byte PEBBLE_COMMAND_NO_NEW_DATA = 4;
	public static final byte PEBBLE_COMMAND_FORCE_REQUEST = 5; // requests the watch to send a request (to update version, etc.)
	
	// Vibrate options
	public static final byte PEBBLE_VIBRATE_NONE = 0;
	public static final byte PEBBLE_VIBRATE_SHORT = 1;
	public static final byte PEBBLE_VIBRATE_TWICE_SHORT = 2;
	public static final byte PEBBLE_VIBRATE_LONG = 3;

	// Variables
	private int state = STATE_INITIAL_POPULATING_PLUGIN_DATA;
	private int currentIndex = -1; // index that is currently sent
	private List<AgendaItem> itemsToSend = null; // data we're currently sending to the watch
	private byte currentSyncId = 0; // id of the current sync process (incremented for each new COMMAND_INIT_DATA)
	private List<AgendaItem> itemsSuccessfullySent = null; // last list we sent completely (DONE message). Data corresponds to lastSuccessfulSyncId below
	private byte lastSuccessfulSyncId = 0; // id that we gave the watchface for the last sync that went through (DONE message) (used for checking for new data) - 0 means "don't know, send anyway!"
	private byte lastWatchReportedSyncId = 0; // the newest sync id reported by the watch in a request
	private boolean vibrate_on_next_done = false; // if set to true, will instruct watch to vibrate after sync. It's auto-reset after that.

	private HashMap<String, List<AgendaItem>> pluginData = new HashMap<String, List<AgendaItem>>(); // Maps pluginId -> current list of items

	private BroadcastReceiver ackReceiver = null;
	private BroadcastReceiver nackReceiver = null;

	private PebbleDictionary lastSentDict = null; // data last sent. Used for retries
	private int transactionFlying = -1; // id of the transaction last sent.
	private int numRetries = 0; // number of times we tried to send this data

	private long notificationIssued = -1; // time since epoch in ms where update prompt was issued last
	private int watchfaceVersion = -1; // last version the watchface reported
	private long lastSync = -1; // time since epoch in ms where last sync went through

	private static AgendaWatchfaceService instance = null; // static reference to the service
	private Handler handler = null;

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
		Intent intent = new Intent(context, AgendaWatchfaceService.class);
		intent.setAction(INTENT_ACTION_HANDLE_WATCHAPP_MESSAGE);
		intent.putExtra(com.getpebble.android.kit.Constants.MSG_DATA, msgData);
		context.startService(intent);
	}

	/**
	 * Checks the message's content and acts accordingly
	 * 
	 * @param dict
	 */
	private void handleReceivedWatchDataInternal(PebbleDictionary data) {
		watchRequestReceived(data.getInteger(PEBBLE_TO_PHONE_KEY_VERSION), data.contains(PEBBLE_TO_PHONE_KEY_VERSIONBACKWARD) ? data.getInteger(PEBBLE_TO_PHONE_KEY_VERSIONBACKWARD) : 4,
				data.contains(PEBBLE_TO_PHONE_KEY_LAST_SYNC_ID) ? data.getUnsignedInteger(PEBBLE_TO_PHONE_KEY_LAST_SYNC_ID).byteValue() : (byte) 0);
	}

	@Override
	public void onCreate() {
		super.onCreate();

		Log.d("PebbleCommunication", "Service created");

		watchfaceVersion = PreferenceManager.getDefaultSharedPreferences(getApplicationContext()).getInt("last_reported_watchface_version", -1);

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

		// Register for info requests
		IntentFilter filter = new IntentFilter();
		filter.addAction(AgendaWatchfaceService.INTENT_ACTION_WATCHAPP_REQUEST_INFO);
		registerReceiver(infoRequestReceiver, filter);

		instance = this;
		handler = new Handler();

		startInitialPluginDataGetting();
	}

	/**
	 * Asks plugins for data and syncs watch after some seconds
	 */
	protected synchronized void startInitialPluginDataGetting() {
		state = STATE_INITIAL_POPULATING_PLUGIN_DATA;
		issueGatherPluginData(); // ask plugins for data
		handler.postDelayed(new Runnable() { // wait some time, then issue the first sync
					public void run() {
						Log.d("AgendaWatchfaceService", "Ending STATE_INITIAL_POPULATING_PLUGIN_DATA, starting watch sync");
						state = STATE_WAIT_FOR_WATCH_REQUEST;
						sendForceRequestMessage();
					}
				}, WAIT_TIME_FOR_PLUGIN_REPORTS);
	}

	@Override
	public void onDestroy() {
		super.onDestroy();

		if (ackReceiver != null)
			unregisterReceiver(ackReceiver);
		if (nackReceiver != null)
			unregisterReceiver(nackReceiver);

		instance = null;
		handler = null;
		PreferenceManager.getDefaultSharedPreferences(getApplicationContext()).edit().putInt("last_reported_watchface_version", watchfaceVersion).commit();
	}

	@Override
	public synchronized int onStartCommand(Intent intent, int flags, int startId) {
		if (intent != null && INTENT_ACTION_HANDLE_WATCHAPP_MESSAGE.equals(intent.getAction())) { // handle the watch's request
			Log.d("PebbleCommunication", "handling watch message");
			try {
				handleReceivedWatchDataInternal(PebbleDictionary.fromJson(intent.getStringExtra(com.getpebble.android.kit.Constants.MSG_DATA)));
			} catch (JSONException e) {
				Log.e("PebbleCommunication", "Error parsing json", e);
			}
		} else if (intent != null && AgendaWatchfacePlugin.MAIN_SERVICE_INTENT_ACTION_ACCEPT_DATA.equals(intent.getAction())) { // handle plugin giving us data
			if (intent.getIntExtra(AgendaWatchfacePlugin.MAIN_SERVICE_INTENT_EXTRA_PLUGIN_VERSION, -1) != AgendaWatchfacePlugin.PLUGIN_PROTOCOL_VERSION)
				Log.e("AgendaWatchfaceService", "Plugin " + intent.getStringExtra(AgendaWatchfacePlugin.MAIN_SERVICE_INTENT_EXTRA_PLUGIN_ID)
						+ " seems outdated (or didn't supply version). Ignoring its data");
			else {
				Parcelable[] data = intent.getParcelableArrayExtra(AgendaWatchfacePlugin.MAIN_SERVICE_INTENT_EXTRA_DATA);
				ArrayList<AgendaItem> items = new ArrayList<AgendaItem>();
				try {
					for (Parcelable item : data)
						items.add(new AgendaItem((Bundle) item));
				} catch (RuntimeException e) {
					Log.e("AgendaWatchfaceService", "Plugin supplied invalid data", e);
				}
				handleReceivedPluginData(items, intent.getStringExtra(AgendaWatchfacePlugin.MAIN_SERVICE_INTENT_EXTRA_PLUGIN_ID), intent.getBooleanExtra(AgendaWatchfacePlugin.MAIN_SERVICE_INTENT_EXTRA_VIBRATE, false));
			}
		} else if (intent != null && INTENT_ACTION_REFRESH_PLUGIN_DATA.equals(intent.getAction())) {
			issueGatherPluginData();
		} else if (intent != null && INTENT_ACTION_FORCE_WATCH_SYNC.equals(intent.getAction())) {
			sendForceRequestMessage();
		} else if (intent != null && state != STATE_INITIAL_POPULATING_PLUGIN_DATA) { // someone wants to simply start the service. Also start a sync
			Log.d("PebbleCommunication", "onStartService() started forced update");
			resetPluginData();
			startInitialPluginDataGetting();
			sendForceRequestMessage();
		}

		return START_STICKY; // we want the service to persist
	}

	private void handleReceivedPluginData(List<AgendaItem> items, String pluginId, boolean vibrate) {
		if (pluginId == null) {
			Log.e("AgendaWatchfaceService", "No plugin id supplied");
			return;
		}
		if (items == null) {
			Log.e("AgendaWatchfaceService", "Cannot handle null item list from plugin " + pluginId);
			return;
		}

		Log.d("AgendaWatchfaceService", "Successfully received update from " + pluginId);

		if (pluginData.containsKey(pluginId) && items.equals(pluginData.get(pluginId))) { // skip further action if no changes...
			return;
		}

		if (vibrate)
			vibrate_on_next_done = true;
		
		pluginData.put(pluginId, items);
		doWatchSyncOnChanges();
	}

	/**
	 * Pebble got our last message. Send next one according to current state
	 * 
	 * @param transactionId
	 */
	private synchronized void ackReceived(int transactionId) {
		if (transactionId != transactionFlying) {
			Log.d("PebbleCommunication", "Received unexpected ack. Ignoring");
			return;
		}
		Log.d("PebbleCommunication", "Received ack in state " + state);
		switch (state) {
		case STATE_RESTART_SYNC_ON_ACK: //TODO recheck if this state is needed
			forceSync();
			break;
		case STATE_WAIT_FOR_WATCH_REQUEST: // we're not expecting an ack
			break;
		case STATE_NO_NEW_DATA_MSG_SENT:
			state = STATE_WAIT_FOR_WATCH_REQUEST;
			break;
		case STATE_INIT_SENT: // message ack'd was the initial one. Start sending items
			currentIndex = 0;
			if (itemsToSend.size() == 0) { // nothing to do if no items to show
				state = STATE_WAIT_FOR_WATCH_REQUEST;
				break;
			}

			// Begin sending first item
			if (canBeSentInOneMessage(itemsToSend.get(currentIndex))) {
				sendItem(itemsToSend.get(currentIndex), currentIndex);
				state = STATE_SENT_ITEM_WAIT_FOR_ACK;
			} else {
				sendFirstItemHalf(itemsToSend.get(currentIndex), currentIndex);
				state = STATE_SENT_ITEM_1_WAIT_FOR_ACK;
			}
			break;

		case STATE_SENT_ITEM_2_WAIT_FOR_ACK: // ack was for second item half. Send next item
		case STATE_SENT_ITEM_WAIT_FOR_ACK: // ack was for item. Send next item
			currentIndex++;
			if (currentIndex < itemsToSend.size()) { // still things to send
				if (canBeSentInOneMessage(itemsToSend.get(currentIndex))) {
					sendItem(itemsToSend.get(currentIndex), currentIndex);
					state = STATE_SENT_ITEM_WAIT_FOR_ACK;
				} else {
					sendFirstItemHalf(itemsToSend.get(currentIndex), currentIndex);
					state = STATE_SENT_ITEM_1_WAIT_FOR_ACK;
				}
			} else {
				sendDoneMessage();
				state = STATE_SENT_DONE_MSG_WAIT_FOR_ACK;
			}
			break;
		
		case STATE_SENT_ITEM_1_WAIT_FOR_ACK: //ack was for first item half. Send next half
			sendSecondItemHalf(itemsToSend.get(currentIndex), currentIndex);
			state = STATE_SENT_ITEM_2_WAIT_FOR_ACK;
			break;			

		case STATE_SENT_DONE_MSG_WAIT_FOR_ACK: // ack was for done message. This concludes the sync process
			state = STATE_WAIT_FOR_WATCH_REQUEST;
			Log.d("PebbleCommunication", "Sync complete :)");
			lastSync = System.currentTimeMillis();
			lastSuccessfulSyncId = currentSyncId;
			itemsSuccessfullySent = itemsToSend;
			vibrate_on_next_done = false;

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
	 *            id the watch reports that its synced data has
	 */
	private synchronized void watchRequestReceived(Long version, Long minVersion, byte reportedSyncId) {
		Log.d("PebbleCommunication", "Received sync request in state " + state + " for version " + version + ", watch reports having data id " + reportedSyncId);
		lastWatchReportedSyncId = reportedSyncId;
		if (state == STATE_INITIAL_POPULATING_PLUGIN_DATA) { // ignore watch request for the time being
			Log.d("AgendaWatchfaceService", "Ignoring watch request since we're waiting for initial plugin data");
			return;
		}

		if (currentSyncId == 0)
			currentSyncId = reportedSyncId; // if we have no sync id remembered, just pretend we remember the one the watch reported

		if (minVersion == null || minVersion > CURRENT_WATCHAPP_VERSION_BUNDLED) { // watchface expects newer Android app
			triggerAndroidAppUpdateNotification();
			state = STATE_WAIT_FOR_WATCH_REQUEST;
		} else if (version == null || version < CURRENT_WATCHAPP_VERSION_MINIMUM) { // watchface very outdated
			triggerUpdateNotification();
			state = STATE_WAIT_FOR_WATCH_REQUEST;
		} else {
			// everything good. Give the watch its data :)
			if (state == STATE_WAIT_FOR_WATCH_REQUEST) // expecting request or watch is very persistent in requesting the restart...
				doWatchSyncOnChanges();
			else {
				Log.d("PebbleCommunication", "Restart request during sync. Restarting");
				state = STATE_WAIT_FOR_WATCH_REQUEST; // restart the whole thing
				doWatchSyncOnChanges();
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
			Log.d("PebbleCommunication", "Received Nack in state " + state + " resend counter: " + numRetries);
			final PebbleDictionary dictToResend = lastSentDict;
			
			handler.postDelayed(new Runnable() {
				public void run() {
					if (dictToResend == lastSentDict && !sendMessage(dictToResend, true)) {
						Log.d("PebbleCommunication", "Retries exhausted. Resetting state to begin again");
						state = STATE_WAIT_FOR_WATCH_REQUEST;
					}
				}
			}, 3000);
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
		flags |= Integer.parseInt(prefs.getString("pref_layout_font_size", "0")) % 2 == 1 ? 0x20 : 0;
		flags |= Integer.parseInt(prefs.getString("pref_layout_font_size", "0")) > 1 ? 0x40 : 0;
		flags |= Integer.parseInt(prefs.getString("pref_header_time_size", "0")) % 2 == 1 ? 0x80 : 0;
		flags |= Integer.parseInt(prefs.getString("pref_header_time_size", "0")) > 1 ? 0x100 : 0;
		flags |= prefs.getBoolean("pref_separator_date", false) ? 0x200 : 0;
		flags |= prefs.getBoolean("pref_enable_scroll", true) ? 0x400 : 0;
		flags |= prefs.getBoolean("pref_layout_countdown", false) ? 0x800 : 0;
		flags |= prefs.getBoolean("pref_continuous_scroll", true) ? 0x1000 : 0;

		dict.addUint32(PEBBLE_KEY_SETTINGS_BOOLFLAGS, flags);
	}

	/**
	 * Asks all plugins for content updates. Updates will arrive asynchronously some time later
	 */
	private void issueGatherPluginData() {
		// Send the broadcast to notify everyone
		Intent intent = new Intent(AgendaWatchfacePlugin.INTENT_ACTION_AGENDA_PROVIDER);
		intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
		intent.putExtra(AgendaWatchfacePlugin.INTENT_EXTRA_PROTOCOL_VERSION, AgendaWatchfacePlugin.PLUGIN_PROTOCOL_VERSION);
		intent.putExtra(AgendaWatchfacePlugin.INTENT_EXTRA_REQUEST_TYPE, AgendaWatchfacePlugin.REQUEST_TYPE_REFRESH);
		sendBroadcast(intent);
	}

	/**
	 * Simply removes all plugin data we know
	 */
	private void resetPluginData() {
		pluginData.clear();
	}

	/**
	 * Kicks of a forced sync (giving the watch a complete dataset)
	 */
	private synchronized void forceSync() {
		beginWatchSync((byte) 0);
	}

	/**
	 * Checks if there are any changes in the current data and kicks of a sync iff this is the case
	 */
	private synchronized void doWatchSyncOnChanges() {
		beginWatchSync(lastWatchReportedSyncId);
	}

	/**
	 * Kicks off sync process by checking whether we have new data, and sending init message or noNewData message
	 * 
	 * @param reportedSyncId
	 *            id that the watch reported that it has (or 0 to force sync).
	 */
	private synchronized void beginWatchSync(byte reportedSyncId) {
		if (state == STATE_INITIAL_POPULATING_PLUGIN_DATA) {
			Log.d("AgendaWatchfaceService", "Almost wanted to start a sync, but we're still in the \"getting plugin data\" phase");
			return;
		}

		if (state != STATE_WAIT_FOR_WATCH_REQUEST) {
			Log.d("PebbleCommunication", "Restarting sending of items");
		}
		
		int max_num_items_to_send = Integer.parseInt(PreferenceManager.getDefaultSharedPreferences(this).getString("pref_send_num_items", "10"));

		Calendar cal = Calendar.getInstance();
		long now = cal.getTimeInMillis();

		// Calculate what to send
		itemsToSend = new ArrayList<AgendaItem>();
		for (List<AgendaItem> pluginItems : pluginData.values())
			// populate list with all plugin items
			for (AgendaItem item : pluginItems)
				if (item != null && item.endTime == null || item.endTime.getTime() > now)
					itemsToSend.add(item);

		Collections.sort(itemsToSend); // Sort
		if (itemsToSend.size() > max_num_items_to_send) // Trim
			itemsToSend.subList(max_num_items_to_send, itemsToSend.size()).clear();

		currentIndex = -1;

		// Check if we should report this data having been sent before
		boolean newData = true;
		if (itemsSuccessfullySent != null && lastSuccessfulSyncId != 0 && reportedSyncId == lastSuccessfulSyncId) { // if so, Compare the version we're about to send to the last successful one
			newData = !itemsSuccessfullySent.equals(itemsToSend);
		}

		if (newData) {
			currentSyncId++;
			currentSyncId = currentSyncId <= 0 ? (byte) 1 : currentSyncId;
			sendInitDataMsg(itemsToSend.size(), currentSyncId);
			state = STATE_INIT_SENT;
		} else {
			sendNoNewDataMsg();
			state = STATE_NO_NEW_DATA_MSG_SENT;
		}
	}

	/**
	 * Inform the watch that its dataset is up-to-date
	 */
	private void sendNoNewDataMsg() {
		Log.d("PebbleCommunication", "Informing watch that its dataset is up-to-date");
		PebbleDictionary data = new PebbleDictionary();
		data.addUint8(PEBBLE_KEY_COMMAND, PEBBLE_COMMAND_NO_NEW_DATA);
		sendMessage(data, false);
	}

	/**
	 * Sends init message.
	 * 
	 * @param numberOfItems
	 * @param syncId
	 *            Id of this sync process to report to the watch
	 */
	private void sendInitDataMsg(int numberOfItems, byte syncId) {
		Log.d("PebbleCommunication", "sending init message, advertising " + numberOfItems + " items and syncId " + syncId);
		PebbleDictionary data = new PebbleDictionary();
		data.addUint8(PEBBLE_KEY_COMMAND, PEBBLE_COMMAND_INIT_DATA); // command
		data.addUint8(PEBBLE_KEY_NUM_ITEMS, (byte) numberOfItems); // number of items we will send
		data.addUint8(PEBBLE_KEY_SYNC_ID, syncId); // id of the data we're about to send (to compare against existing data)
		data.addUint8(PEBBLE_KEY_VERSION, CURRENT_WATCHAPP_VERSION_MINIMUM); // expected minimum watchapp version
		addPebbleSettings(data); // general and design settings
		sendMessage(data, false);
	}

	/**
	 * Returns true iff the item is small enough to safely send it in one message
	 * @param e the event to send
	 */
	private boolean canBeSentInOneMessage(AgendaItem e) {
		String line1 = e.line1 == null ? "" : e.line1.text == null ? "(null)" : e.line1.text.length() >= MAX_STRING_LEN_TO_SEND ? e.line1.text.substring(0, MAX_STRING_LEN_TO_SEND) : e.line1.text;
		String line2 = e.line2 == null ? "" : e.line2.text == null ? "(null)" : e.line2.text.length() >= MAX_STRING_LEN_TO_SEND ? e.line2.text.substring(0, MAX_STRING_LEN_TO_SEND) : e.line2.text;
		
		return line1.getBytes().length+line2.getBytes().length < 40;
	}
	
	/**
	 * Sends a message with the item
	 * 
	 * @param e the item to send
	 * @param index the index in this sync
	 */
	private void sendItem(AgendaItem e, int index) {
		PebbleDictionary data = new PebbleDictionary();
		data.addUint8(PEBBLE_KEY_COMMAND, PEBBLE_COMMAND_ITEM); // command
		data.addUint8(PEBBLE_KEY_ITEM_INDEX, (byte) index);
		data.addString(PEBBLE_KEY_ITEM_TEXT1, e.line1 == null ? "" : stringToSendableString(e.line1.text));
		data.addString(PEBBLE_KEY_ITEM_TEXT2, e.line2 == null ? "" : stringToSendableString(e.line2.text));
		data.addUint8(PEBBLE_KEY_ITEM_DESIGN1, e.line1 == null ? 0 : getPebbleDesign(e.line1, 1));
		data.addUint8(PEBBLE_KEY_ITEM_DESIGN2, e.line2 == null ? 0 : getPebbleDesign(e.line2, 2));
		data.addInt32(PEBBLE_KEY_ITEM_START_TIME, e.getStartTimeInPebbleFormat());
		data.addInt32(PEBBLE_KEY_ITEM_END_TIME, e.getEndTimeInPebbleFormat());
		sendMessage(data, false);
	}
	
	/**
	 * Sends a message with the first half of an item
	 * @param e the item to send
	 * @param index the index in this sync
	 */
	private void sendFirstItemHalf(AgendaItem e, int index) {
		PebbleDictionary data = new PebbleDictionary();
		data.addUint8(PEBBLE_KEY_COMMAND, PEBBLE_COMMAND_ITEM_1); // command
		data.addUint8(PEBBLE_KEY_ITEM_INDEX, (byte) index);
		data.addString(PEBBLE_KEY_ITEM_TEXT1, e.line1 == null ? "" : stringToSendableString(e.line1.text));
		data.addUint8(PEBBLE_KEY_ITEM_DESIGN1, e.line1 == null ? 0 : getPebbleDesign(e.line1, 1));
		data.addInt32(PEBBLE_KEY_ITEM_START_TIME, e.getStartTimeInPebbleFormat());
		sendMessage(data, false);
	}
	
	/**
	 * Sends a message with the first half of an item
	 * @param e the item to send
	 * @param index the index in this sync
	 */
	private void sendSecondItemHalf(AgendaItem e, int index) {
		PebbleDictionary data = new PebbleDictionary();
		data.addUint8(PEBBLE_KEY_COMMAND, PEBBLE_COMMAND_ITEM_2); // command
		data.addUint8(PEBBLE_KEY_ITEM_INDEX, (byte) index);
		data.addString(PEBBLE_KEY_ITEM_TEXT2, e.line2 == null ? "" : stringToSendableString(e.line2.text));
		data.addUint8(PEBBLE_KEY_ITEM_DESIGN2, e.line2 == null ? 0 : getPebbleDesign(e.line2, 2));
		data.addInt32(PEBBLE_KEY_ITEM_END_TIME, e.getEndTimeInPebbleFormat());
		sendMessage(data, false);
	}
	
	private String stringToSendableString(String str) {
		if (str == null)
			return "(null)";
		if (str.length() > MAX_STRING_LEN_TO_SEND)
			return str.substring(0, MAX_STRING_LEN_TO_SEND-4)+"...";
		return str;
	}

	/**
	 * Computes the design for the pebble
	 * 
	 * @param line
	 * @param linenum
	 * @return
	 */
	private byte getPebbleDesign(AgendaItem.Line line, int linenum) {
		byte result = 1; // [sic!] to distinguish between hiding the line and simply all-zero settings
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());

		//Bold
		if (line.textBold)
			result |= 0x20;
		
		//Overflow
		result |= line.overflow.ordinal()*0x40;

		//TimeDisplayType
		TimeDisplayType time_type = line.timeDisplay;
		if (time_type == null)
			time_type = TimeDisplayType.values()[Integer.parseInt(prefs.getString("pref_layout_time_" + linenum, linenum == 1 ? "0" : "4"))];
		result |= time_type.ordinal() * 0x02;

		if (time_type != TimeDisplayType.NONE) {
			boolean showCountdown = false;
			if (line.timeShowCountdown != null)
				showCountdown = line.timeShowCountdown;
			else
				showCountdown = prefs.getBoolean("pref_layout_countdown", false);
			if (showCountdown)
				result |= 0x10;
		}

		return result;
	}

	/**
	 * Send message that we're done with the sync
	 */
	private void sendDoneMessage() {
		PebbleDictionary data2 = new PebbleDictionary();
		data2.addUint8(PEBBLE_KEY_COMMAND, PEBBLE_COMMAND_DONE);
		data2.addUint8(PEBBLE_KEY_VIBRATE, vibrate_on_next_done ? Byte.valueOf(PreferenceManager.getDefaultSharedPreferences(this).getString("pref_vibrate_type", "1")) : PEBBLE_VIBRATE_NONE);
		sendMessage(data2, false);
	}

	/**
	 * Send message that we want the watch to request an update (e.g., to get to know its version)
	 */
	private void sendForceRequestMessage() {
		PebbleDictionary data2 = new PebbleDictionary();
		data2.addUint8(PEBBLE_KEY_COMMAND, PEBBLE_COMMAND_FORCE_REQUEST);
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
		if ((numRetries = resend ? numRetries + 1 : 0) > 2) {
			Log.d("PebbleCommunication", "Stopped retrying message sending in state " + state);
			return false;
		}
		if (resend)
			Log.d("PebbleCommunication", "Resending message. This is retry number "+(numRetries));
		
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
