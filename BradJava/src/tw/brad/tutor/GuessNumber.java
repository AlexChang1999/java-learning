package tw.brad.tutor;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.JTextField;

public class GuessNumber extends JFrame implements ActionListener{
	private JButton guess;
	private JTextField input;
	private JTextArea log;
	private String answer;
	private int counter;
	
	public GuessNumber() {
		super("猜數字遊戲");
		
		guess = new JButton("猜");
		input = new JTextField();
		input.setFont(new Font(null, Font.BOLD + Font.ITALIC, 24));	// 1, 2, 4
		log = new JTextArea();
		log.setFont(new Font(null, Font.BOLD + Font.ITALIC, 24));
		log.setForeground(Color.GREEN);
		
		guess.addActionListener(this);
//		guess.addActionListener(new ActionListener() {
//			@Override
//			public void actionPerformed(ActionEvent e) {
//				System.out.println("OK");
//			}
//		});
		
		setLayout(new BorderLayout());
		
		JPanel top = new JPanel(new BorderLayout());
		
		add(top, BorderLayout.NORTH);
		add(log, BorderLayout.CENTER);
		
		top.add(guess, BorderLayout.EAST);
		top.add(input, BorderLayout.CENTER);
		
		setSize(640, 480);
		setVisible(true);
		setDefaultCloseOperation(EXIT_ON_CLOSE);
		
		initGame();
	}
	
	private static String createAnswer(int d) {
		final int nums = 10;
		int[] poker = new int[nums];
		for (int i=0; i<poker.length; i++) poker[i] = i;
		
		for (int i = nums - 1; i > 0; i--) {
			int r = (int)(Math.random()*(i+1));
			// poker[i] <-> poker[r]
			int temp = poker[i];
			poker[i] = poker[r];
			poker[r] = temp;
		}	
		
		StringBuilder sb = new StringBuilder();
		for (int i=0; i<d; i++) {sb.append(poker[i]);}
		
		return sb.toString();
	}
	
	private void initGame() {
		counter = 0;
		answer = createAnswer(3);
		log.setText("");
		input.setText("");
		System.out.println(answer);
	}
	
	public static void main(String[] args) {
		new GuessNumber();
	}

	@Override
	public void actionPerformed(ActionEvent e) {
		String g = input.getText();
		counter++;
		
		String result = checkAB(g);
		log.append(String.format("%d. %s => %s\n", counter, g, result));
		input.setText("");
		
		if (result.equals("3A0B")) {
			JOptionPane.showMessageDialog(null, "恭喜老爺");
			initGame();
		}else if (counter == 3) {
			JOptionPane.showMessageDialog(null, "魯蛇: 謎底:" + answer);
			initGame();
		}
		
	}
	
	private String checkAB(String g) {
		int a, b; a = b = 0;
		for (int i = 0; i< answer.length(); i++) {
			if (g.charAt(i) == answer.charAt(i)) {
				a++;
			}else if (answer.indexOf(g.charAt(i)) != -1) {
				b++;
			}
		}
		return String.format("%dA%dB", a, b);
	}
	
	
}
