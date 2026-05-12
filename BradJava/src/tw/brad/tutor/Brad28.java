package tw.brad.tutor;

import tw.brad.apis.Bike;
import tw.brad.apis.TWId;

public class Brad28 {

	public static void main(String[] args) {
		System.out.println(TWId.isRight("A123456789")); 
		TWId id1 = new TWId();
		TWId id2 = new TWId(true);
		TWId id3 = new TWId('B');
		TWId id4 = new TWId(false, 'A');
		System.out.println(id1);
		System.out.println(id2);
		System.out.println(id3);
		System.out.println(id4);
		
//		TWId id5 = TWId.createTWId("Y120239847");
//		System.out.println(id5);
		
	}

}
