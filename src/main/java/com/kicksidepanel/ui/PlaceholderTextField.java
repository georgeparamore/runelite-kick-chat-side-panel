package com.kicksidepanel.ui;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import javax.swing.JTextField;

/**
 * A JTextField that shows gray hint text when empty and unfocused - Swing has no native
 * placeholder-text support, so this paints it directly rather than pulling in a UI kit.
 */
public class PlaceholderTextField extends JTextField
{
	private final String placeholder;

	public PlaceholderTextField(String placeholder)
	{
		this.placeholder = placeholder;
	}

	@Override
	protected void paintComponent(Graphics g)
	{
		super.paintComponent(g);
		if (!getText().isEmpty())
		{
			return;
		}

		Graphics2D g2 = (Graphics2D) g.create();
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g2.setColor(new Color(0x8a, 0x8a, 0x95));
		g2.setFont(getFont());

		Insets margin = getMargin();
		g2.setClip(margin.left, 0, Math.max(0, getWidth() - margin.left - margin.right), getHeight());

		int textY = (getHeight() - g2.getFontMetrics().getHeight()) / 2 + g2.getFontMetrics().getAscent();
		g2.drawString(placeholder, margin.left, textY);
		g2.dispose();
	}
}
