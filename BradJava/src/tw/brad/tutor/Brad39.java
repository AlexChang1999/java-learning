package tw.brad.tutor;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import tw.brad.apis.Bike;

public class Brad39 {
	public static void main(String[] args) {
		Map<String,Object> person = new HashMap<>();
		person.put("name", "Brad");
		person.put("gender", true);
		person.put("age", 18);
		person.put("bike", new Bike());
		System.out.println(person.get("name"));
		
		((Bike)person.get("bike")).upSpeed().upSpeed().upSpeed().upSpeed();
		
		System.out.println("---");
		Set<String> keys = person.keySet();
		for (String key : keys) {
			System.out.printf("%s : %s\n", key, person.get(key));
		}
		
	}
}
