package de.janbo.agendawatchface;

/**
 * Data class for a Calendar on the phone
 * @author Jan
 *
 */
public class CalendarInstance {
	public long id; //id according to the content provider
	public String name; //name of calendar
	public String account; //account name the calendar belongs to
	
	public CalendarInstance(long id, String name, String account) {
		super();
		this.id = id;
		this.name = name;
		this.account = account;
	}
}
