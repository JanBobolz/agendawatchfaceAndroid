package de.janbo.agendawatchface;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.preference.PreferenceManager;
import android.provider.CalendarContract.Calendars;
import android.provider.CalendarContract.Events;
import android.provider.CalendarContract.Instances;
import android.util.Log;

public class CalendarReader {
	protected static Uri getContentUri() {
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
	public static ArrayList<CalendarEvent> getEvents(Context context, int maxNum) {
		ArrayList<CalendarEvent> events = new ArrayList<CalendarEvent>();
		
		Cursor cur = null;
		ContentResolver cr = context.getContentResolver();
		String selection = null;
		String[] selectionArgs = null;
		
		// Submit the query
		cur = cr.query(getContentUri(), new String[] { Instances.CALENDAR_ID, Instances.EVENT_ID, Instances.EVENT_LOCATION, Instances.BEGIN, Instances.END, Instances.TITLE, Instances.ALL_DAY }, selection, selectionArgs,
				Instances.BEGIN + " ASC");

		long now = Calendar.getInstance().getTimeInMillis();
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
		boolean ignoreAllDayEvents = !prefs.getBoolean("pref_show_all_day_events", true);
		while (cur.moveToNext() && events.size() < maxNum) {
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
			events.add(new CalendarEvent(cur.getString(cur.getColumnIndex(Instances.TITLE)), cur.getString(cur.getColumnIndex(Instances.EVENT_LOCATION)), new Date(cur.getLong(cur
					.getColumnIndex(Instances.BEGIN))), new Date(cur.getLong(cur.getColumnIndex(Instances.END))), cur.getInt(cur.getColumnIndex(Instances.ALL_DAY)) != 0));
		}
		cur.close();

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
	public static void registerCalendarObserver(Context context, ContentObserver observer) {
		context.getContentResolver().registerContentObserver(Events.CONTENT_URI, true, observer);
	}
	
	/**
	 * Unregisters observer for calendar changes
	 * @param context
	 * @param observer
	 */
	public static void unregisterCalendarObserver(Context context, ContentObserver observer) {
		context.getContentResolver().unregisterContentObserver(observer);
	}
}
