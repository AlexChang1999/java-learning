package tw.brad.tutor;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;

import javax.swing.JButton;
import javax.swing.JColorChooser;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.JPanel;

import tw.brad.apis.SignPanel;

public class MySign extends JFrame{
	private SignPanel panel;
	private JButton clear, undo, redo, color, saveObj, saveAsObj, loadObj, saveJPEG;
	private File nowFile;
	
	public MySign() {
		
		nowFile = null;
		
		setLayout(new BorderLayout());
		panel = new SignPanel();
		add(panel, BorderLayout.CENTER);
		
		JPanel top = new JPanel(new FlowLayout());
		clear = new JButton("Clear");
		undo = new JButton("Undo");
		redo = new JButton("Redo");
		color = new JButton("Color");
		saveObj = new JButton("Save Obj");
		saveAsObj = new JButton("SaveAs Obj");
		loadObj = new JButton("Load Obj");
		saveJPEG = new JButton("Save JPEG");
		top.add(clear);top.add(undo);top.add(redo);
		top.add(color);
		top.add(saveObj);top.add(saveAsObj);top.add(loadObj);
		top.add(saveJPEG);
		
		add(top, BorderLayout.NORTH);
		
		setSize(800,  600);
		setVisible(true);
		setDefaultCloseOperation(EXIT_ON_CLOSE);
		
		initEvent();
	}
	
	private void initEvent() {
		clear.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				panel.clear();
			}
		});
		undo.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				panel.undo();
			}
		});
		redo.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				panel.redo();
			}
		});
		color.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				changeColor();
			}
		});
		saveAsObj.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				saveAsObj();
			}
		});
		saveObj.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				saveObj();
			}
		});
		loadObj.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				loadObj();
			}
		});
		saveJPEG.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				saveJPEG();
			}
		});
	}
	
	private void changeColor() {
		Color newColor = JColorChooser.showDialog(this, "Change Color", panel.getColor());
		if (newColor != null) {
			panel.changeColor(newColor);
		}
	}
	
	private void saveObj() {
		if (nowFile != null) {
			try {
				panel.saveLines(nowFile);
				JOptionPane.showMessageDialog(this, "Save Success");
			} catch (Exception e) {
				JOptionPane.showMessageDialog(this, "Save Failure");
			}
		}else {
			
		}
	}

	private void saveAsObj() {
		JFileChooser jfc = new JFileChooser();
		if (jfc.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
			nowFile = jfc.getSelectedFile();
			try {
				panel.saveLines(nowFile);
				JOptionPane.showMessageDialog(this, "Save Success");
			} catch (Exception e) {
				JOptionPane.showMessageDialog(this, "Save Failure");
			}
		}
	}
	
	private void loadObj() {
		JFileChooser jfc = new JFileChooser();
		if (jfc.showOpenDialog(this)== JFileChooser.APPROVE_OPTION) {
			nowFile = jfc.getSelectedFile();
			try {
				panel.loadLine(nowFile);
			} catch (Exception e) {
				JOptionPane.showMessageDialog(this, "Load Failure");
			}
		}
	}
	
	private void saveJPEG() {
		JFileChooser jfc = new JFileChooser();
		if (jfc.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
			File saveFile = jfc.getSelectedFile();
			try {
				panel.saveJPEG(saveFile);
				JOptionPane.showMessageDialog(this, "Save Success");
			} catch (Exception e) {
				JOptionPane.showMessageDialog(this, "Save Failure");
			}
		}
	}	

	public static void main(String[] args) {
		new MySign();
	}


}
