package com.kicksidepanel.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.swing.JButton;

/**
 * Small icon-only button (an external-link glyph) that opens the configured channel's Kick
 * page in the system browser. Drawn directly in code rather than shipping a bundled image
 * asset, same approach as {@link KickPanelIcon}. Stays opaque with a manually painted
 * background matching its surroundings rather than using setContentAreaFilled(false) +
 * setOpaque(false) - that combination renders as literally nothing under RuneLite's runtime
 * LAF (see the same note on {@link PillButton}).
 */
public class OpenChannelButton extends JButton
{
	private static final Color ICON_COLOR = new Color(0x8a, 0x8a, 0x95);
	private static final Color ICON_HOVER_COLOR = Color.WHITE;

	private final Color background;

	public OpenChannelButton(Color background)
	{
		this.background = background;
		setOpaque(true);
		setContentAreaFilled(true);
		setFocusPainted(false);
		setBorderPainted(false);
		setPreferredSize(new Dimension(20, 20));
		setToolTipText("Open channel in browser");
		setCursor(Cursor.getDefaultCursor());
	}

	@Override
	protected void paintComponent(Graphics g)
	{
		Graphics2D g2 = (Graphics2D) g.create();
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		g2.setColor(background);
		g2.fillRect(0, 0, getWidth(), getHeight());

		g2.setColor(getModel().isRollover() ? ICON_HOVER_COLOR : ICON_COLOR);
		g2.setStroke(new BasicStroke(1.3f));

		int size = 9;
		int x = (getWidth() - size) / 2;
		int y = (getHeight() - size) / 2 + 1;

		g2.drawRect(x, y + 2, size - 2, size - 2);
		g2.drawLine(x + 3, y + size - 3, x + size, y);
		g2.drawLine(x + size - 3, y, x + size, y);
		g2.drawLine(x + size, y, x + size, y + 3);

		g2.dispose();
	}
}
