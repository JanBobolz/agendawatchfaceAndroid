package de.janbo.agendawatchface.calendar;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.TimeZone;

import android.app.Service;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.provider.CalendarContract.Calendars;
import android.provider.CalendarContract.Events;
import android.provider.CalendarContract.Instances;
import android.util.Log;
import de.janbo.agendawatchface.CalendarInstance;
import de.janbo.agendawatchface.api.AgendaItem;
import de.janbo.agendawatchface.api.TimeDisplayType;

public class AgendaCalendarService extends Service {	
	private ContentObserver calendarObserver = new ContentObserver(null) { // content observer looking for calendar changes
		@Override
		public void onChange(boolean selfChange) {
			Log.d("AgendaCalendarService", "Calendar changed (observer fired)");
			Intent intent = new Intent(AgendaCalendarService.this, AgendaCalendarService.class);
			startService(intent);
		}
	};
			
	public void onCreate() {
		super.onCreate();
		registerCalendarObserver();
	}
	
	@Override
	public void onDestroy() {
		super.onDestroy();
		unregisterCalendarObserver();
	}
	
	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		new AgendaCalendarProvider().publishData(this, getEvents(30));
		return START_STICKY;
	}
	
	protected Uri getContentUri() {
		Calendar beginTime = Calendar.getInstance();
		beginTime.add(Calendar.DAY_OF_MONTH, -1);
		beginTime.set(Calendar.HOUR_OF_DAY, 23);
		beginTime.set(Calendar.MINUTE, 59);
		long startMillis = beginTime.getTimeInMillis();

		Calendar endTime = Calendar.getInstance();
		endTime.set(Calendar.HOUR_OF_DAY, 23);
		endTime.set(Calendar.MINUTE, 59);
		endTime.add(Calendar.DAY_OF_MONTH, 6);
		long endMillis = endTime.getTimeInMillis();

		// Construct the query with the desired date range.
		Uri.Builder builder = Instances.CONTENT_URI.buildUpon();
		ContentUris.appendId(builder, startMillis);
		ContentUris.appendId(builder, endMillis);
		
		return builder.build();
	}
	
	/**
	 * Gives a list of at most maxNum Calendar events in the next ... days 
	 * @param context
	 * @return
	 */
	protected ArrayList<AgendaItem> getEvents(int maxNum) {
		ArrayList<AgendaItem> events = new ArrayList<AgendaItem>();
		
		Cursor cur = null;
		ContentResolver cr = getContentResolver();
		String selection = null;
		String[] selectionArgs = null;
		
		// Submit the query
		cur = cr.query(getContentUri(), new String[] { Instances.CALENDAR_ID, Instances.EVENT_ID, Instances.EVENT_LOCATION, Instances.BEGIN, Instances.END, Instances.TITLE, Instances.ALL_DAY }, selection, selectionArgs,
				Instances.BEGIN + " ASC");

		long now = Calendar.getInstance().getTimeInMillis();
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
		boolean ignoreAllDayEvents = !prefs.getBoolean("pref_show_all_day_events", true);
		while (cur.moveToNext()) {
			//Filter all-day-events if set to ignore them
			if (ignoreAllDayEvents && cur.getInt(cur.getColumnIndex(Instances.ALL_DAY)) != 0)
				continue;
			
			// Filter non-all-day events that are already gone
			if (cur.getInt(cur.getColumnIndex(Instances.ALL_DAY)) == 0 && cur.getLong(cur.getColumnIndex(Instances.END)) < now)
				continue;
			
			//Filter calendars according to settings
			if (!prefs.getBoolean("pref_cal_"+cur.getLong(cur.getColumnIndex(Instances.CALENDAR_ID))+"_picked", true))
				continue;
			
			//Add event to result
			boolean allday = cur.getInt(cur.getColumnIndex(Instances.ALL_DAY)) != 0;
			String line1textCode = prefs.getString("pref_layout"+(allday ? "_ad" : "")+"_text_1", allday ? "1" : "1");
			String line2textCode = prefs.getString("pref_layout"+(allday ? "_ad" : "")+"_text_2", allday ? "1" : "1");
			String line1text = line1textCode.equals("1") ? cur.getString(cur.getColumnIndex(Instances.TITLE)) : cur.getString(cur.getColumnIndex(Instances.EVENT_LOCATION));
			String line2text = line2textCode.equals("1") ? cur.getString(cur.getColumnIndex(Instances.TITLE)) : cur.getString(cur.getColumnIndex(Instances.EVENT_LOCATION));;
			
			AgendaItem item = new AgendaItem(new AgendaCalendarProvider().getPluginId());
			item.startTime = new Date(cur.getLong(cur.getColumnIndex(Instances.BEGIN)));
			item.endTime = new Date(cur.getLong(cur.getColumnIndex(Instances.END)));
			if (cur.getInt(cur.getColumnIndex(Instances.ALL_DAY)) != 0)  //adjust timezone 
				item.timezone = TimeZone.getTimeZone("UTC");
			
			item.line1 = new AgendaItem.Line();
			item.line1.text = line1text;
			item.line1.textBold = line1textCode.equals("1");
			if (allday)
				item.line1.timeDisplay = TimeDisplayType.NONE;
			
			if (allday && prefs.getBoolean("pref_layout_ad_show_row2", false) || !allday && prefs.getBoolean("pref_layout_show_row2", true)) {
				item.line2 = new AgendaItem.Line();
				item.line2.text = line2text;
				item.line2.textBold = line2textCode.equals("1");
				if (allday)
					item.line2.timeDisplay = TimeDisplayType.NONE;
			}
			
			events.add(item);
		}
		cur.close();
		
		//Now all events are in the events array, but may be out of order. And too many
		//Sort
		Collections.sort(events);
		
		//Trim
		if (events.size() > maxNum)
			events.subList(maxNum, events.size()).clear();

		return events;
	}
	
	/**
	 * Returns a list of calendars available on the phone
	 * @param context
	 * @return list of CalendarInstances
	 */
	public static ArrayList<CalendarInstance> getCalendars(Context context) {
		ArrayList<CalendarInstance> result = new ArrayList<CalendarInstance>();
		
		Cursor cur = null;
		ContentResolver cr = context.getContentResolver();
		Uri uri = Calendars.CONTENT_URI;   
		String selection = null;
		String[] selectionArgs = null; 
		cur = cr.query(uri, new String[]{Calendars._ID, Calendars.NAME, Calendars.CALENDAR_DISPLAY_NAME, Calendars.ACCOUNT_NAME}, selection, selectionArgs, null);
		
		while (cur.moveToNext()) {
			result.add(new CalendarInstance(cur.getLong(cur.getColumnIndex(Calendars._ID)), cur.getString(cur.getColumnIndex(Calendars.CALENDAR_DISPLAY_NAME)), cur.getString(cur.getColumnIndex(Calendars.ACCOUNT_NAME))));
		}
		
		cur.close();
		
		return result;
	}
	
	/**
	 * Registers an observer for calendar changes
	 * @param context
	 * @param observer
	 */
	public void registerCalendarObserver() {
		getContentResolver().registerContentObserver(Events.CONTENT_URI, true, calendarObserver);
	}
	
	/**
	 * Unregisters observer for calendar changes
	 * @param context
	 * @param observer
	 */
	public void unregisterCalendarObserver() {
		getContentResolver().unregisterContentObserver(calendarObserver);
	}

	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}
}
