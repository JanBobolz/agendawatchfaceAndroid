package de.janbo.agendawatchface;

/**
 * Data class for a Calendar on the phone
 * @author Jan
 *
 */
public class CalendarInstance {
	long id; //id according to the content provider
	String name; //name of calendar
	String account; //account name the calendar belongs to
	
	public CalendarInstance(long id, String name, String account) {
		super();
		this.id = id;
		this.name = name;
		this.account = account;
	}
}
