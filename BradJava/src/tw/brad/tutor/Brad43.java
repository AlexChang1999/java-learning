package tw.brad.tutor;

public class Brad43 {
	public static void main(String[] args) {
		m1(3);
	}
	
	static void m1(int a) {
		if (a <10 ) {
			try {
				m2(7);
				System.out.println("OK");
				
			}catch(Exception e) {
				System.out.println("ee");
				return;
			}finally {
				System.out.println("ff");
			}
			System.out.println("END");
		}else {
			System.out.println("OK");
		}
	}
	
	static void m2(int b) throws Exception{
		if (b > 0) {
			throw new Exception();
		}
	}
}
