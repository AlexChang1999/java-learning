package tw.brad.apis;

import java.awt.Color;
import java.util.LinkedList;
import java.util.List;

public class Line {
	private List<Point> points;
	private Color color;
	private float width;
	
	public Line(Color color) {
		points = new LinkedList<>();
		this.color = color;
	}
	
	public void addXY(int x, int y) {
		Point p = new Point(x, y);
		points.add(p);
	}
	public int getSize() {
		return points.size();
	}
	public int getX(int index) {
		return points.get(index).getX();
	}
	public int getY(int index) {
		return points.get(index).getY();
	}

	public Color getColor() {
		return color;
	}


	
	
}
