package de.janbo.agendawatchface;

import java.util.Calendar;
import java.util.Date;
import java.util.TimeZone;

/**
 * Data class for calendar events
 * @author Jan
 *
 */
public class CalendarEvent implements Comparable<CalendarEvent> {
	public final String title;
	public final String location;
	public final Date startTime;
	public final Date endTime;
	public final boolean allDay;
	
	public CalendarEvent(String title, String location, Date startTime, Date endTime, boolean allDay) {
		super();
		this.title = title;
		this.location = location;
		this.startTime = startTime;
		this.endTime = endTime;
		this.allDay = allDay;
	}

	@Override
	public int compareTo(CalendarEvent another) {
		int diff = getStartTimeInPebbleFormat()-another.getStartTimeInPebbleFormat();
		if (diff == 0) {
			if (another.allDay == allDay)
				return 0;
			return allDay ? -1 : 1; //all-day events begin 'earlier' (virtually) than 00:00 events on the same day
		}
		return diff;
	}
	
	/**
	 * Gives you the start time in the format that the watchface uses (documented in PebbleCommService constants)
	 * @return minutes + 60*hours + 60*24*weekday + 60*24*7*dayOfMonth + 60*24*7*32*(month-1) + 60*24*7*32*12*(year-1900)
	 */
	public int getStartTimeInPebbleFormat() {
		return getPebbleTimeFormat(startTime, allDay);
	}
	
	/**
	 * Gives you the end time in the format that the watchface uses (documented in PebbleCommService constants)
	 * @return minutes + 60*hours + 60*24*weekday + 60*24*7*dayOfMonth + 60*24*7*32*(month-1) + 60*24*7*32*12*(year-1900)
	 */
	public int getEndTimeInPebbleFormat() {
		return getPebbleTimeFormat(endTime, allDay);
	}
	
	/**
	 * Gives a Date in the watchface's format. 
	 * @param time
	 * @param allDay all-day events are interpreted as UTC times
	 * @return minutes + 60*hours + 60*24*weekday + 60*24*7*dayOfMonth + 60*24*7*32*(month-1) + 60*24*7*32*12*(year-1900)
	 */
	public static int getPebbleTimeFormat(Date time, boolean allDay) {
		Calendar cal = Calendar.getInstance();
		cal.setTime(time);
		if (allDay)
			cal.setTimeZone(TimeZone.getTimeZone("UTC")); //all-day events must be interpreted as UTC and begin/end on day boundaries
		return cal.get(Calendar.MINUTE) + 60 * cal.get(Calendar.HOUR_OF_DAY) + 60 * 24 * ((cal.get(Calendar.DAY_OF_WEEK) + 5) % 7) + 60 * 24 * 7 * cal.get(Calendar.DAY_OF_MONTH) + 60 * 24 * 7 * 32
						* cal.get(Calendar.MONTH) + 60 * 24 * 7 * 32 * 12 * (cal.get(Calendar.YEAR) - 1900);
	}
}
