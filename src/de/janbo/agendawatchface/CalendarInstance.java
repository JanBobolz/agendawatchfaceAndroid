package de.janbo.agendawatchface;

/**
 * Data class for a Calendar on the phone
 * @author Jan
 *
 */
public class CalendarInstance {
	long id; //id according to the content provider
	String name; //name of calendar
	
	public CalendarInstance(long id, String name) {
		super();
		this.id = id;
		this.name = name;
	}
}
