package tw.brad.apis;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.LinkedList;
import java.util.List;

import javax.swing.JPanel;

public class SignPanel extends JPanel{
	private List<Line> lines;
	
	public SignPanel() {
		setBackground(Color.YELLOW);
		MyMouseListener listener = new MyMouseListener();
		addMouseListener(listener);
		addMouseMotionListener(listener);
		
		lines = new LinkedList<>();
	}
	
	@Override
	protected void paintComponent(Graphics g) {
		super.paintComponent(g);
		
		Graphics2D g2d = (Graphics2D)g;
		g2d.setStroke(new BasicStroke(4));
		g2d.setColor(Color.RED);
		
		for (Line line: lines) {
			for (int i=1; i<line.getSize(); i++) {
				g2d.drawLine(line.getX(i-1), line.getY(i-1),
						line.getX(i), line.getY(i));
			}
		}
		
		
	}
	
	private class MyMouseListener extends MouseAdapter{
		@Override
		public void mousePressed(MouseEvent e) {
			Line line = new Line();
			line.addXY(e.getX(), e.getY());
			lines.add(line);
		}
		
		@Override
		public void mouseDragged(MouseEvent e) {
			lines.getLast().addXY(e.getX(), e.getY());
			repaint();
		}
	}
	
}

