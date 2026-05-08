package tw.brad.tutor;

import java.util.List;
import java.util.Map;
import java.util.Set;

public class Brad40 {

	public static void main(String[] args) {
		List<String> list = List.of("Brad", "Eric","Tony","Amy");
		System.out.println(list.get(1));
		Set<String> set = Set.of("Brad", "Eric","Tony","Amy");
		for (String name : set) System.out.println(name);
		System.out.println("---");
		Map<Integer,String> map = Map.of(1, "Brad", 4, "Peter", 7, "Mark", 3, "Kevin");
		System.out.println(map.get(4));
		
		
	}

}
