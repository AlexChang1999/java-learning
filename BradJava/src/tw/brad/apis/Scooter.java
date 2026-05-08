package tw.brad.apis;

public class Scooter extends Bike{
	private int gear;
	private String color;
	
	
	public Scooter() {
		color = "yellow";
	}
	
	public Scooter(String color) {
		this.color = color;
	}
	
	public int changeGear(int gear) {
		if (gear >= 0 && gear <= 4) {
			this.gear = gear;
		}
		return this.gear;
	}
	
	// Override
	public Scooter upSpeed() {
		speed = speed < 1? 1: speed * 1.8 * gear;
		return this;
	}
	
	// overload
	public void upSpeed(int gear) {
		changeGear(gear);
		speed = speed < 1? 1: speed * 1.8 * gear;
	}
	
	
	public String getColor() {
		return color;
	}
	
}
