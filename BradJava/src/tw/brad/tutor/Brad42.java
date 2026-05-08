package tw.brad.tutor;

import java.io.FileNotFoundException;
import java.io.IOException;

import javax.swing.filechooser.FileNameExtensionFilter;

public class Brad42 {
	public static void main(String[] args) {
		Bird b1 = new Bird();
		try {
			b1.setLeg(5);
		} catch (IOException e) {
			System.out.println("Err");
		}
		System.out.println(b1.leg);
	}
}

class Bird {
	int leg;
	void setLeg(int n) throws IOException {
		if (n>=0 && n<=3) {
			leg = n;
		}else {
			throw new FileNotFoundException();
		}
	}
}
