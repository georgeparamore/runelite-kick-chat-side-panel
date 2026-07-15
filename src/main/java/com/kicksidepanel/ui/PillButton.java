package com.kicksidepanel.ui;

import java.awt.Color;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.swing.JButton;

/**
 * A rounded-pill button. Must stay opaque (do NOT setContentAreaFilled(false) +
 * setOpaque(false)) - under RuneLite's runtime LAF that combination renders as literally
 * nothing, even though this same custom-paint approach works fine.
 */
public class PillButton extends JButton
{
	private static final Color DISABLED_OVERLAY = new Color(0x0e, 0x0e, 0x12, 140);

	private final Color background;
	private final Color hoverBackground;

	public PillButton(String text, Color background, Color hoverBackground)
	{
		super(text);
		this.background = background;
		this.hoverBackground = hoverBackground;
		setOpaque(true);
		setContentAreaFilled(true);
		setFocusPainted(false);
		setBorderPainted(false);
		setForeground(Color.WHITE);
	}

	@Override
	protected void paintComponent(Graphics g)
	{
		Graphics2D g2 = (Graphics2D) g.create();
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		g2.setColor(getModel().isRollover() ? hoverBackground : background);
		g2.fillRoundRect(0, 0, getWidth(), getHeight(), getHeight(), getHeight());
		if (!isEnabled())
		{
			g2.setColor(DISABLED_OVERLAY);
			g2.fillRoundRect(0, 0, getWidth(), getHeight(), getHeight(), getHeight());
		}

		g2.setColor(getForeground());
		g2.setFont(getFont());
		FontMetrics metrics = g2.getFontMetrics();
		String text = getText();
		int x = (getWidth() - metrics.stringWidth(text)) / 2;
		int y = (getHeight() - metrics.getHeight()) / 2 + metrics.getAscent();
		g2.drawString(text, x, y);

		g2.dispose();
	}
}
