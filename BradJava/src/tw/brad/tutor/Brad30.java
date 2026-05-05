package tw.brad.tutor;

public class Brad30 {
	public static void main(String[] args) {
		//Brad301.m2();
		//Brad301 obj1 = new Brad301();
		//Brad302 obj2 = new Brad302();
		
		System.out.println(Brad303.b);
		
		Brad303 obj3 = new Brad303();
		System.out.println(obj3.a);
		System.out.println(Brad303.b);
		
		Brad303 obj4 = new Brad303();
		System.out.println(obj4.a);
		System.out.println(Brad303.b);
		
		Brad303 obj5 = new Brad303();
		System.out.println(obj5.a);
		System.out.println(Brad303.b);
	}
}

class Brad301 {
	{System.out.println("{}");m1();}
	static {System.out.println("static{}");}
	Brad301(){System.out.println("Brad301()");}
	void m1() {System.out.println("m1()");}
	static void m2() {System.out.println("m2()");}
}
class Brad302 extends Brad301{
	static {System.out.println("static2{}");}
	Brad302(){System.out.println("Brad302()");}
}
class Brad303{
	int a;
	static int b;
	Brad303(){
		a++;
		b++;
	}
}
