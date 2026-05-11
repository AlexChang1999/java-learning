package tw.brad.tutor;

import java.io.File;
import java.io.FileInputStream;

public class Brad49 {

	public static void main(String[] args) {
		File file = new File("dir1/file3.txt");
		// text / binary file
		try(FileInputStream fin = new FileInputStream(file)){
 			long len = file.length();
 			byte[] buf = new byte[(int)len];
 			fin.read(buf);
 			System.out.println(new String(buf));
 			
		}catch(Exception e) {
			System.out.println(e);
		}
	}

}
