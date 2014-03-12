package de.janbo.agendawatchface.api;

import java.util.Calendar;
import java.util.Date;
import java.util.TimeZone;
import android.os.Bundle;
import android.util.Log;

/**
 * An item to display on the agenda view
 * 
 * @author Jan
 * 
 */
public class AgendaItem implements Comparable<AgendaItem> {
	public static class Line {
		/**
		 * The text for this line. Will be truncated if too long May be null
		 */
		public String text = "";

		/**
		 * Whether or not to bold the text (time is never bold).
		 */
		public boolean textBold = false;

		/**
		 * Formatting for the time to display. 
		 * (i.e. user preference)
		 */
		public TimeDisplayType timeDisplay = null;

		/**
		 * Whether or not to count down the times in this line. Assign null for
		 * global default (i.e. user preference)
		 */
		public Boolean timeShowCountdown = null;

		public Bundle toBundle() {
			Bundle result = new Bundle();

			result.putString("text", text);
			result.putBoolean("textBold", textBold);
			if (timeDisplay != null)
				result.putInt("timeDisplay", timeDisplay.ordinal());
			if (timeShowCountdown != null)
				result.putBoolean("timeShowCountdown", timeShowCountdown);

			return result;
		}

		public Line() {
			
		}

		/**
		 * Reconstructs the line from the bundle (as written by toBundle())
		 * 
		 * @param bundle
		 */
		public Line(Bundle bundle) {
			if (bundle == null)
				return;

			try {
				if (bundle.containsKey("text"))
					text = bundle.getString("text");
				if (bundle.containsKey("textBold"))
					textBold = bundle.getBoolean("textBold");
				if (bundle.containsKey("timeDisplay"))
					timeDisplay = TimeDisplayType.values()[bundle.getInt("timeDisplay")];
				if (bundle.containsKey("timeShowCountdown"))
					timeShowCountdown = bundle.getBoolean("timeShowCountdown");
			} catch (Exception e) {
				Log.e("AgendaItem",
						"Error when reconstructing a line (plugin or app outdated?)",
						e);
			}
		}

		/* (non-Javadoc)
		 * @see java.lang.Object#hashCode()
		 */
		@Override
		public int hashCode() {
			final int prime = 31;
			int result = 1;
			result = prime * result + ((text == null) ? 0 : text.hashCode());
			result = prime * result + (textBold ? 1231 : 1237);
			result = prime * result + ((timeDisplay == null) ? 0 : timeDisplay.hashCode());
			result = prime * result + ((timeShowCountdown == null) ? 0 : timeShowCountdown.hashCode());
			return result;
		}

		/* (non-Javadoc)
		 * @see java.lang.Object#equals(java.lang.Object)
		 */
		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (obj == null)
				return false;
			if (!(obj instanceof Line))
				return false;
			Line other = (Line) obj;
			if (text == null) {
				if (other.text != null)
					return false;
			} else if (!text.equals(other.text))
				return false;
			if (textBold != other.textBold)
				return false;
			if (timeDisplay != other.timeDisplay)
				return false;
			if (timeShowCountdown == null) {
				if (other.timeShowCountdown != null)
					return false;
			} else if (!timeShowCountdown.equals(other.timeShowCountdown))
				return false;
			return true;
		}
	}

	/**
	 * The first line for this item. 
	 */
	public Line line1 = new Line();

	/**
	 * Second line for this item. May be null (then it's a single-line item)
	 */
	public Line line2 = null;

	/**
	 * Start time for the item (may be null, then it's simply always shown for
	 * "today")
	 */
	public Date startTime = null;

	/**
	 * End time for the item (can be displayed and item vanishes after endTime).
	 * May be null, then it never vanishes
	 */
	public Date endTime = null;

	/**
	 * The priority for the item. This will determine which two items with the
	 * same times will be displayed first (or... at all, considering the limited
	 * space) Higher value is more important
	 */
	public int priority = 0;
	
	/**
	 * The id of the plugin this item was issued by.
	 */
	private String pluginId;
	
	/**
	 * The timezone to interpret the start- and endtimes. Null for default.
	 * Should rarely not be null, but for example, all-day events in Google
	 * calendar are stored wrt. UTC
	 */
	public TimeZone timezone = null;

	@Override
	public int compareTo(AgendaItem another) { //compares by start times, tie-breaks with priority, then tie-breaks with pluginId
		int diff = getStartTimeInPebbleFormat()-another.getStartTimeInPebbleFormat();
		if (diff == 0) {
			int diff2 = another.priority - priority;
			if (diff2 == 0)
				return pluginId.compareTo(another.pluginId);
			return diff2;
		}
		
		return diff;
	}

	/**
	 * Gives you the start time in the format that the watchface uses
	 * (documented in PebbleCommService constants)
	 * 
	 * @return minutes + 60*hours + 60*24*weekday + 60*24*7*dayOfMonth +
	 *         60*24*7*32*(month-1) + 60*24*7*32*12*(year-1900)
	 */
	public int getStartTimeInPebbleFormat() {
		return getPebbleTimeFormat(startTime, timezone);
	}

	/**
	 * Gives you the end time in the format that the watchface uses (documented
	 * in PebbleCommService constants)
	 * 
	 * @return minutes + 60*hours + 60*24*weekday + 60*24*7*dayOfMonth +
	 *         60*24*7*32*(month-1) + 60*24*7*32*12*(year-1900)
	 */
	public int getEndTimeInPebbleFormat() {
		return getPebbleTimeFormat(endTime, timezone);
	}

	/**
	 * Gives a Date in the watchface's format. Relative to the device's current
	 * timezone (standard way)
	 * 
	 * @param time
	 * @return minutes + 60*hours + 60*24*weekday + 60*24*7*dayOfMonth +
	 *         60*24*7*32*(month-1) + 60*24*7*32*12*(year-1900)
	 */
	public static int getPebbleTimeFormat(Date time) {
		return getPebbleTimeFormat(time, null);
	}

	/**
	 * Gives a Date in the watchface's format.
	 * 
	 * @param time
	 * @param timezone
	 *            the timezone to compute the time for (null for default)
	 * @return minutes + 60*hours + 60*24*weekday + 60*24*7*dayOfMonth +
	 *         60*24*7*32*(month-1) + 60*24*7*32*12*(year-1900)
	 */
	public static int getPebbleTimeFormat(Date time, TimeZone timezone) {
		if (time == null)
			return 0;
		
		Calendar cal = Calendar.getInstance();
		cal.setTime(time);
		if (timezone != null)
			cal.setTimeZone(timezone);
		return cal.get(Calendar.MINUTE) + 60 * cal.get(Calendar.HOUR_OF_DAY)
				+ 60 * 24 * ((cal.get(Calendar.DAY_OF_WEEK) + 5) % 7) + 60 * 24
				* 7 * cal.get(Calendar.DAY_OF_MONTH) + 60 * 24 * 7 * 32
				* cal.get(Calendar.MONTH) + 60 * 24 * 7 * 32 * 12
				* (cal.get(Calendar.YEAR) - 1900);
	}

	public Bundle toBundle() {
		Bundle result = new Bundle();

		if (line1 != null)
			result.putBundle("line1", line1.toBundle());
		if (line2 != null)
			result.putBundle("line2", line2.toBundle());
		if (startTime != null)
			result.putLong("startTime", startTime.getTime());
		if (endTime != null)
			result.putLong("endTime", endTime.getTime());
		result.putInt("priority", priority);
		if (timezone != null)
			result.putString("timezone", timezone.getID());
		result.putString("pluginId", pluginId);
		
		return result;
	}

	public AgendaItem(String pluginId) {
		if (pluginId == null)
			throw new NullPointerException("Plugin id must not be null for AgendaItems");
		this.pluginId = pluginId;
	}

	public AgendaItem(Bundle bundle) {
		line1 = bundle.containsKey("line1") ? new Line(bundle.getBundle("line1")) : null;
		line2 = bundle.containsKey("line2") ? new Line(bundle.getBundle("line2")) : null;
		startTime = bundle.containsKey("startTime") ? new Date(bundle.getLong("startTime")) : null;
		endTime = bundle.containsKey("endTime") ? new Date(bundle.getLong("endTime")) : null;
		priority = bundle.containsKey("priority") ? bundle.getInt("priority") : 0;
		timezone = bundle.containsKey("timezone") ? TimeZone.getTimeZone(bundle.getString("timezone")) : null;
		pluginId = bundle.containsKey("pluginId") ? bundle.getString("pluginId") : "noId";
	}

	/* (non-Javadoc)
	 * @see java.lang.Object#hashCode()
	 */
	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((endTime == null) ? 0 : endTime.hashCode());
		result = prime * result + ((line1 == null) ? 0 : line1.hashCode());
		result = prime * result + ((line2 == null) ? 0 : line2.hashCode());
		result = prime * result + ((pluginId == null) ? 0 : pluginId.hashCode());
		result = prime * result + priority;
		result = prime * result + ((startTime == null) ? 0 : startTime.hashCode());
		result = prime * result + ((timezone == null) ? 0 : timezone.hashCode());
		return result;
	}

	/* (non-Javadoc)
	 * @see java.lang.Object#equals(java.lang.Object)
	 */
	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (!(obj instanceof AgendaItem))
			return false;
		AgendaItem other = (AgendaItem) obj;
		if (endTime == null) {
			if (other.endTime != null)
				return false;
		} else if (!endTime.equals(other.endTime))
			return false;
		if (line1 == null) {
			if (other.line1 != null)
				return false;
		} else if (!line1.equals(other.line1))
			return false;
		if (line2 == null) {
			if (other.line2 != null)
				return false;
		} else if (!line2.equals(other.line2))
			return false;
		if (pluginId == null) {
			if (other.pluginId != null)
				return false;
		} else if (!pluginId.equals(other.pluginId))
			return false;
		if (priority != other.priority)
			return false;
		if (startTime == null) {
			if (other.startTime != null)
				return false;
		} else if (!startTime.equals(other.startTime))
			return false;
		if (timezone == null) {
			if (other.timezone != null)
				return false;
		} else if (!timezone.getID().equals(other.timezone.getID()))
			return false;
		return true;
	}
	
	
}
