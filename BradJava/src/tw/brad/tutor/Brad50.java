package tw.brad.tutor;

import java.io.FileReader;

public class Brad50 {

	public static void main(String[] args) {
		try(FileReader reader = new FileReader("dir1/file3.txt");){
			int c;
			while ( (c = reader.read()) != -1 ) {
				System.out.print((char)c);
			}
		}catch(Exception e) {
			System.out.println(e);
		}
		
	}

}
