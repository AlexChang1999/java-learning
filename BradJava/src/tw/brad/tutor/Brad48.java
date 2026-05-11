package tw.brad.tutor;

import java.io.FileInputStream;

public class Brad48 {
	public static void main(String[] args) {
		try (FileInputStream fin = new FileInputStream("dir1/file3.txt");){
 			int c1 = fin.read();
 			System.out.println((char)c1);
 			c1 = fin.read();
 			System.out.println((char)c1);
		}catch(Exception e) {
			System.out.println(e);
		}
	}
}
