package tw.brad.tutor;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;

public class Brad60 {

	public static void main(String[] args) {
		String mesg = "Hello, World";
		byte[] data = mesg.getBytes();
		try (DatagramSocket socket = new DatagramSocket();){
			DatagramPacket packet = new DatagramPacket(data, data.length, 
					InetAddress.getByName("10.0.102.74"), 8888);
			socket.send(packet);
			System.out.println("UDP Send OK");
		} catch (Exception e) {
			System.out.println(e);
		}
	}

}
