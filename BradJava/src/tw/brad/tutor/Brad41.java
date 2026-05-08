package tw.brad.tutor;

public class Brad41 {

	public static void main(String[] args) {
		int a = 10,  b = 3;
		int c;
		
		System.out.println("Brad");
		try {
			c = a/b;
			System.out.println(c);
		}catch(ArithmeticException e) {
			System.out.println(-1);
		}
		System.out.println("Finish");
	}

}
