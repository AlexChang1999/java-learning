package tw.brad.tutor;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

public class Brad47 {

	public static void main(String[] args) {
		String s1 = "\nHello, Brad";
		try(FileOutputStream fout = new FileOutputStream("dir1/file2.txt", true);) {
			fout.write(s1.getBytes());
			System.out.println("OK");
		} catch (Exception e) {
			System.out.println(e);
		}
		
	}

}
