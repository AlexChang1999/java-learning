package tw.brad.tutor;

import java.io.File;
import java.io.IOException;

public class Brad46 {

	public static void main(String[] args) {
		File f1 = new File("./dir1/file1.txt");
		if (!f1.exists()) {
			try {
				f1.createNewFile();
				System.out.println("OK");
			} catch (IOException e) {
				System.out.println(e);
			}
		}else {
			File f2 = new File("./dir2/file1.txt");
			f1.renameTo(f2);
		}
	}

}
