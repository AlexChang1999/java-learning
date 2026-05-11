package tw.brad.tutor;

import java.io.File;

public class Brad45 {

	public static void main(String[] args) {
		File f1 = new File("d:/brad");
		System.out.println(f1.exists());
		File root = new File(".");
		System.out.println(root.exists());
		System.out.println(root.getAbsolutePath());
		File dir1 = new File("dir1");
		System.out.println(dir1.exists());
	}

}
