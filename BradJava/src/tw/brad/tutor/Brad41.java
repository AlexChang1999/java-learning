package tw.brad.tutor;

public class Brad41 {

	public static void main(String[] args) {
		int a = 10,  b = 3;
		int c;
		int[] d = {1, 2, 3, 4}; 
		
		System.out.println("Brad");
		try {
			c = a/b;
			System.out.println(c);
			System.out.println(d[200]);
//		}catch(ArrayIndexOutOfBoundsException e) {
//			System.out.println("Ooooop");
//		}catch(ArithmeticException e) {
//			System.out.println(-1);
		}catch(RuntimeException e) {
			System.out.println(-1);
		}
		System.out.println("Finish");
	}

}
