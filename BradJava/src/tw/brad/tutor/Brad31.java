package tw.brad.tutor;

public class Brad31 {
	public static void main(String[] args) {
		Brad311 obj1 = new Brad311();
		obj1.m1();
		System.out.println("----");
		Brad311 obj2 = new Brad312();
		obj2.m1();
		Brad312 obj3 = (Brad312)obj2;
		obj3.m2();
		System.out.println("----");
		Brad311 obj4 = new Brad313();
		obj4.m1();
		Brad313 obj5 = (Brad313)obj4;
		obj5.m3();
		System.out.println("----");
		//Brad313 obj6 = (Brad313)obj2;
		System.out.println("----------------------");
		test(obj1);
		test(obj3);
		test(obj5);
	}
	
	static void test(Brad311 car) {
		car.m1();
	}
	
	
}
class Brad311 {
	Brad311(){System.out.println("Brad311()");}
	void m1() {System.out.println("Brad311:m1()");}
}
// Brad312 is-a Brad311
class Brad312 extends Brad311{
	Brad312(){System.out.println("Brad312()");}
	void m1() {System.out.println("Brad312:m1()");}
	void m2() {System.out.println("Brad312:m2()");}
}
class Brad313 extends Brad311{
	Brad313(){System.out.println("Brad313()");}
	void m1() {System.out.println("Brad313:m1()");}
	void m2() {System.out.println("Brad313:m2()");}
	void m3() {System.out.println("Brad313:m3()");}
}

