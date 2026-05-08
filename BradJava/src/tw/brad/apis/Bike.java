package tw.brad.apis;

public class Bike extends Object{
	// has-a
	protected double speed;
	/*
	 * private :  本類別
	 * 沒寫 : 相同 package
	 * protected : 子類別 or 相同 package
	 * public: 全世界
	 */
	
	public Bike upSpeed() {
		speed = speed < 1? 1: speed * 1.4;
		return this;
	}
	
	public Bike downSpeed() {
		speed = speed < 1? 0 : speed * 0.7;
		return this;
	}
	
	public double getSpeed() {
		return speed;
	}
	
	@Override
	public String toString() {
		return String.format("Speed: %f", speed);
	}
	
}
