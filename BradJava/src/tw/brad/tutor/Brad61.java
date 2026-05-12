package tw.brad.tutor;

import java.net.DatagramPacket;
import java.net.DatagramSocket;

public class Brad61 {

	public static void main(String[] args) {
		byte[] buf = new byte[1024];
		try(DatagramSocket socket = new DatagramSocket(8888)){
			DatagramPacket packet = new DatagramPacket(buf, buf.length);
			socket.receive(packet);
			System.out.println("UDP Receive OK");
		}catch(Exception e) {
			System.out.println(e);
		}
		
	}

}
