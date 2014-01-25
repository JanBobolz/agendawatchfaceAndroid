package de.janbo.agendawatchface;

import java.util.Date;

/**
 * Data class for calendar events
 * @author Jan
 *
 */
public class CalendarEvent {
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
}
