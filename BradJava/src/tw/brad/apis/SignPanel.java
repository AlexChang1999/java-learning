package tw.brad.apis;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.LinkedList;
import java.util.List;

import javax.imageio.ImageIO;
import javax.swing.JPanel;

public class SignPanel extends JPanel{
	private List<Line> lines, recycle;
	private Color nowColor;
	
	public SignPanel() {
		setBackground(Color.YELLOW);
		MyMouseListener listener = new MyMouseListener();
		addMouseListener(listener);
		addMouseMotionListener(listener);
		
		lines = new LinkedList<>();
		recycle = new LinkedList<>();
		
		nowColor = Color.BLACK;
	}
	
	@Override
	protected void paintComponent(Graphics g) {
		super.paintComponent(g);
		
		Graphics2D g2d = (Graphics2D)g;
		g2d.setStroke(new BasicStroke(4));
		
		for (Line line: lines) {
			g2d.setColor(line.getColor());
			for (int i=1; i<line.getSize(); i++) {
				g2d.drawLine(line.getX(i-1), line.getY(i-1),
						line.getX(i), line.getY(i));
			}
		}
		
		
	}
	
	private class MyMouseListener extends MouseAdapter{
		@Override
		public void mousePressed(MouseEvent e) {
			recycle.clear();
			
			Line line = new Line(nowColor);
			line.addXY(e.getX(), e.getY());
			lines.add(line);
		}
		
		@Override
		public void mouseDragged(MouseEvent e) {
			lines.getLast().addXY(e.getX(), e.getY());
			repaint();
		}
	}
	
	public void clear() {
		lines.clear();
		repaint();
	}
	
	public void undo() {
		if (lines.size() > 0) {
			recycle.add(lines.removeLast()) ;
			repaint();
		}
	}
	
	public void redo() {
		if (recycle.size() > 0) {
			lines.add(recycle.removeLast());
			repaint();
		}
		
	}
	
	public Color getColor() {return nowColor;}
	public void changeColor(Color newColor) {nowColor = newColor;}
	
	
	public void saveLines(File saveFile) throws Exception {
		try(ObjectOutputStream oout= new ObjectOutputStream(
				new FileOutputStream(saveFile));){
			oout.writeObject(lines);
		}
	}
	
	public void loadLine(File loadFile) throws Exception{
		try(ObjectInputStream oin = new ObjectInputStream(new FileInputStream(loadFile))){
			lines = (List<Line>)oin.readObject();
			repaint();
		}
	}
	
	public void saveJPEG(File saveFile) throws Exception {
		BufferedImage img = 
			new BufferedImage(getWidth(), getHeight(), BufferedImage.TYPE_INT_RGB);
		Graphics2D g2d = img.createGraphics();
		paint(g2d);
		g2d.dispose();
		
		ImageIO.write(img, "jpg", saveFile);
	}
	
}

