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
	
	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + (allDay ? 1231 : 1237);
		result = prime * result + ((endTime == null) ? 0 : endTime.hashCode());
		result = prime * result + ((location == null) ? 0 : location.hashCode());
		result = prime * result + ((startTime == null) ? 0 : startTime.hashCode());
		result = prime * result + ((title == null) ? 0 : title.hashCode());
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (!(obj instanceof CalendarEvent))
			return false;
		CalendarEvent other = (CalendarEvent) obj;
		if (allDay != other.allDay)
			return false;
		if (endTime == null) {
			if (other.endTime != null)
				return false;
		} else if (!endTime.equals(other.endTime))
			return false;
		if (location == null) {
			if (other.location != null)
				return false;
		} else if (!location.equals(other.location))
			return false;
		if (startTime == null) {
			if (other.startTime != null)
				return false;
		} else if (!startTime.equals(other.startTime))
			return false;
		if (title == null) {
			if (other.title != null)
				return false;
		} else if (!title.equals(other.title))
			return false;
		return true;
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
