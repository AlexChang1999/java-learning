package tw.brad.apis;

public class TWId {
	private String id;

	public static boolean isRight(String id) {
		boolean ret = false;
//		if (id.length() == 10) {
//			char c1 = id.charAt(0);
//			String letters = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
//			if (letters.indexOf(c1) != -1) {
//				char c2 = id.charAt(1);
//				if (c2 == '1' || c2 == '2') {
//					
//				}
//			}
//		}

		/*
		 * 04-22334567
		 * 0931-123456
		 * 2026-01-02
		 * 10:20:30
		 * 192.168.3.4
		 * A123456789
		 */
		if (id.matches("[A-Z][12][0-9]{8}")) {
			ret = true;
		}
		
		return ret;
	}
}
